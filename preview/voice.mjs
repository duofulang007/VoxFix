const workletCode = `
class PcmCapture extends AudioWorkletProcessor {
  constructor() {
    super(); this.buffer = new Float32Array(3200); this.used = 0;
    this.port.onmessage = event => { if(event.data === 'flush') { this.send(); this.port.postMessage({flushed:true}); } };
  }
  send() { if(!this.used)return;const samples=this.buffer.slice(0,this.used);this.used=0;this.port.postMessage({samples},[samples.buffer]); }
  process(inputs) {
    const channel=inputs[0]?.[0];
    if(channel) for(const sample of channel) { this.buffer[this.used++]=sample; if(this.used===this.buffer.length)this.send(); }
    return true;
  }
}
registerProcessor('pcm-capture',PcmCapture);`;

async function request(path, session='', body=new Uint8Array()) {
  const response=await fetch('/api/voice/'+path,{method:'POST',headers:{'Content-Type':'application/octet-stream',...(session?{'X-Voice-Session':session}:{})},body,signal:AbortSignal.timeout(20000)});
  const result=await response.json();
  if(!response.ok)throw Error(result.error||'本地语音服务请求失败');
  return result;
}

export class LocalVoice {
  constructor(onText,onError,hotwords=[],onLevel=()=>{}) { this.onText=onText;this.onError=onError;this.hotwords=hotwords;this.onLevel=onLevel;this.cancelled=false;this.tail=Promise.resolve();this.pending=0; }
  async start() {
    try {
      const info=await request('start','',new TextEncoder().encode(JSON.stringify({hotwords:this.hotwords})));this.session=info.session;
      if(this.cancelled){await this.cancel();return;}
      const media=await navigator.mediaDevices.getUserMedia({audio:{channelCount:1,echoCancellation:true,noiseSuppression:true},video:false});
      this.media=media;
      if(this.cancelled){await this.cancel();return;}
      const Context=window.AudioContext||window.webkitAudioContext;
      this.context=new Context({sampleRate:16000});
      if(this.context.sampleRate!==16000)throw Error('当前浏览器不支持 16kHz 录音，请使用 Chrome 或 Edge');
      await this.context.resume();
      const url=URL.createObjectURL(new Blob([workletCode],{type:'text/javascript'}));
      try { await this.context.audioWorklet.addModule(url); } finally { URL.revokeObjectURL(url); }
      if(this.cancelled){await this.cancel();return;}
      this.source=this.context.createMediaStreamSource(media);
      this.node=new AudioWorkletNode(this.context,'pcm-capture');
      this.node.port.onmessage=event=>{
        if(event.data.flushed){this.flushResolve?.();return;}
        if(this.cancelled)return;
        if(++this.pending>20){this.fail(Error('本机识别暂时跟不上录音，请分段说话后重试'));return;}
        const samples=event.data.samples;
        let energy=0;for(const sample of samples)energy+=sample*sample;
        this.onLevel(Math.min(1,Math.sqrt(energy/Math.max(1,samples.length))/.12));
        this.tail=this.tail.then(async()=>{if(this.cancelled)return;const result=await request('chunk',this.session,samples);if(!this.cancelled)this.onText(result.text);}).catch(error=>this.fail(error)).finally(()=>this.pending--);
      };
      this.source.connect(this.node);this.node.connect(this.context.destination);
      this.ready=true;
    } catch(error) { this.fail(error); throw error; }
  }
  fail(error) { if(this.cancelled)return;this.cancelled=true;this.closeAudio();if(this.session)request('cancel',this.session).catch(()=>{});this.onError(error); }
  closeAudio() { clearInterval(this.keepAlive);this.media?.getTracks().forEach(track=>track.stop());this.source?.disconnect();this.node?.disconnect();if(this.context && this.context.state!=='closed')this.context.close().catch(()=>{}); }
  async pause() {
    if(!this.ready||this.cancelled)return;
    await this.context.suspend();this.media.getTracks().forEach(track=>track.enabled=false);
    await this.tail;if(this.cancelled)return;
    this.keepAlive=setInterval(()=>request('keepalive',this.session).catch(error=>this.fail(error)),15000);
  }
  async resume() {
    if(this.cancelled)return;
    clearInterval(this.keepAlive);await request('keepalive',this.session);
    this.media.getTracks().forEach(track=>track.enabled=true);await this.context.resume();
  }
  async finish() {
    if(!this.ready||this.cancelled){await this.cancel();return '';}
    await this.context.resume();this.source.disconnect();
    try { await new Promise((resolve,reject)=>{const timeout=setTimeout(()=>reject(Error('录音收尾超时')),3000);this.flushResolve=()=>{clearTimeout(timeout);resolve();};this.node.port.postMessage('flush');}); }
    finally { this.closeAudio(); }
    await this.tail;
    if(this.cancelled)return '';
    const result=await request('finish',this.session);this.cancelled=true;return result.text;
  }
  async cancel() { this.cancelled=true;this.flushResolve?.();this.closeAudio();if(this.session)await request('cancel',this.session).catch(()=>{}); }
}

export async function voiceStatus() {
  if(!['http:','https:'].includes(location.protocol))return {ready:false,error:'真实语音需打开本机服务版试用页'};
  try { const response=await fetch('/api/voice/status');if(!response.ok)throw Error();return await response.json(); }
  catch {return {ready:false,error:'尚未连接本机离线语音服务'};}
}

export async function enableMicrophone() {
  if(!navigator.mediaDevices?.getUserMedia)throw Error('当前浏览器不支持录音，请使用 Chrome 或 Edge 打开本机试用页');
  const media=await navigator.mediaDevices.getUserMedia({audio:true,video:false});
  media.getTracks().forEach(track=>track.stop());
}
