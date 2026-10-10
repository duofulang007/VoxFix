import assert from 'node:assert/strict';
import { LocalVoice } from './voice.mjs';

const calls=[];
globalThis.fetch=async(url,options)=>{calls.push(url);return {ok:true,json:async()=>url.endsWith('start')?{session:'test-session'}:url.endsWith('finish')?{text:'你好。'}:{text:'你好'}};};
const track={stopped:0,stop(){this.stopped++;}};
Object.defineProperty(globalThis,'navigator',{value:{mediaDevices:{getUserMedia:async()=>({getTracks:()=>[track]})}},configurable:true});
class Context {
  constructor(){this.sampleRate=16000;this.state='running';this.destination={};this.audioWorklet={addModule:async()=>{}};}
  async resume(){}
  async suspend(){}
  createMediaStreamSource(){return {connect(){},disconnect(){}};}
  async close(){this.state='closed';}
}
globalThis.window={AudioContext:Context};
globalThis.AudioWorkletNode=class{
  constructor(){this.port={postMessage:()=>queueMicrotask(()=>this.port.onmessage({data:{flushed:true}}))};}
  connect(){}
  disconnect(){}
};
const text=[];
const voice=new LocalVoice(value=>text.push(value),error=>{throw error;});
await voice.start();
await voice.pause();assert.equal(track.enabled,false);await voice.resume();assert.equal(track.enabled,true);
voice.node.port.onmessage({data:{samples:new Float32Array(3200)}});
assert.equal(await voice.finish(),'你好。');
assert.deepEqual(text,['你好']);
assert.equal(track.stopped,1);
assert.ok(calls.some(url=>url.endsWith('chunk')));
const cancelled=new LocalVoice(()=>{throw Error('Cancelled recording updated text');},error=>{throw error;});
await cancelled.start();await cancelled.cancel();
cancelled.node.port.onmessage({data:{samples:new Float32Array(3200)}});
assert.equal(await cancelled.finish(),'');
assert.equal(track.stopped,3);
assert.ok(calls.some(url=>url.endsWith('cancel')));
console.log('PASS: capture lifecycle, queued PCM, final flush, microphone release and cancellation');
