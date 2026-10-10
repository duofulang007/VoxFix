import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {execFileSync} from 'node:child_process';
import {readFile,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const {_android}=createRequire(import.meta.url)('playwright');
const adb=fileURLToPath(new URL('../.android/sdk/platform-tools/adb.exe',import.meta.url));
const serial='emulator-5554',base='http://127.0.0.1:18745';
execFileSync(adb,['-s',serial,'reverse','tcp:18745','tcp:18745']);
const device=(await _android.devices()).find(device=>device.serial()===serial);assert(device);
await device.shell('am force-stop com.android.chrome');
const context=await device.launchBrowser({viewport:null,hasTouch:true,args:['--autoplay-policy=no-user-gesture-required','--use-fake-device-for-media-stream']});
const page=await context.newPage(),errors=[],cases=[];let chunks=0,keepalives=0,lastSession='';
page.on('pageerror',error=>errors.push(error.message));
page.on('request',request=>{if(request.url().endsWith('/api/voice/chunk'))chunks++;if(request.url().endsWith('/api/voice/keepalive'))keepalives++;});
page.on('response',async response=>{if(response.url().endsWith('/api/voice/start')&&response.ok())lastSession=(await response.json()).session;});
async function seed(text){await page.evaluate(text=>{const editor=document.querySelector('#editor');document.activeElement?.blur();editor.value=text;editor.setSelectionRange(text.length,text.length);editor.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertFromPaste',data:text}));},text);}
async function clean(){await page.evaluate(async()=>{for(const audio of window.testAudioContexts||[])await audio.close().catch(()=>{});window.testAudioContexts=[];});}
async function started(original){await seed(original);await page.locator('#long-voice').tap();await page.waitForFunction(original=>document.querySelector('#editor').readOnly&&document.querySelector('#editor').value!==original,original,{timeout:20000});}
async function released(){return page.evaluate(()=>window.testTracks.every(track=>track.readyState==='ended'));}
try{
  await page.goto(base);await page.waitForFunction(()=>!document.querySelector('#long-voice').disabled);
  const cdp=await context.newCDPSession(page);
  await cdp.send('Browser.setPermission',{permission:{name:'microphone'},setting:'denied',origin:base});
  await seed('授权失败前原文');await page.locator('#long-voice').tap();
  await page.waitForFunction(()=>document.querySelector('#feedback').textContent.includes('麦克风未获授权')&&!document.querySelector('#editor').readOnly,{},{timeout:10000});
  assert.equal(await page.locator('#editor').inputValue(),'授权失败前原文');cases.push({name:'Chrome microphone permission denied',passed:true,source:'Browser permission override; no physical microphone capture'});console.log('PERMISSION_DENIAL_PASSED');
  await cdp.send('Browser.setPermission',{permission:{name:'microphone'},setting:'granted',origin:base});
  const fakeLabels=await page.evaluate(async()=>(await navigator.mediaDevices.enumerateDevices()).filter(device=>device.kind==='audioinput').map(device=>device.label));
  if(fakeLabels.length&&fakeLabels.every(label=>/fake/i.test(label))){
    await page.evaluate(()=>{window.testTracks=[];const native=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);navigator.mediaDevices.getUserMedia=async options=>{const media=await native(options);window.testTracks.push(...media.getTracks());return media;};});
    await page.locator('#enable-mic').tap();await page.waitForFunction(()=>document.querySelector('#feedback').textContent.includes('麦克风已启用'),{},{timeout:10000});
    assert(await released());cases.push({name:'Chrome granted fake device and immediate release',passed:true,source:'Browser generated fake audio device',labels:fakeLabels});
  }else cases.push({name:'Chrome granted device',passed:false,reason:'No verified fake audio device; physical microphone was not requested'});
  const wav=(await readFile(new URL('../models/test/0.wav',import.meta.url))).toString('base64');
  await page.evaluate(wav=>{window.testTracks=[];window.testAudioContexts=[];navigator.mediaDevices.getUserMedia=async()=>{const audio=new AudioContext();window.testAudioContexts.push(audio);await audio.resume();const bytes=Uint8Array.from(atob(wav),char=>char.charCodeAt(0)),source=audio.createBufferSource();source.buffer=await audio.decodeAudioData(bytes.buffer);source.loop=true;const dest=audio.createMediaStreamDestination();source.connect(dest);window.testTracks.push(...dest.stream.getTracks());source.start(audio.currentTime+.3);return dest.stream;};},wav);
  await started('暂停前文字');await page.locator('#pause-voice').tap();await page.waitForFunction(()=>document.querySelector('#pause-voice').textContent==='继续'&&!document.querySelector('#pause-voice').disabled);
  const pausedText=await page.locator('#editor').inputValue(),pausedChunks=chunks,pausedKeep=keepalives;
  for(const wait of [30000,30000,5000]){await page.waitForTimeout(wait);console.log('PAUSE_KEEPALIVE_PROGRESS',keepalives-pausedKeep);}
  assert.equal(await page.locator('#editor').inputValue(),pausedText);assert.equal(chunks,pausedChunks);assert(keepalives-pausedKeep>=4);
  await page.locator('#pause-voice').tap();await page.waitForFunction(()=>document.querySelector('#pause-voice').textContent==='暂停');await page.waitForTimeout(2500);
  await page.locator('#finish-voice').tap();await page.waitForFunction(()=>!document.querySelector('#editor').readOnly);assert(await released());assert((await page.locator('#editor').inputValue()).length>'暂停前文字'.length);
  cases.push({name:'65 second pause, keepalive, resume and finish',passed:true,keepaliveRequests:keepalives-pausedKeep,noChunksWhilePaused:true});await clean();console.log('PAUSE_RESUME_PASSED');
  await started('取消前原文');await page.locator('#cancel-voice').tap();await page.waitForFunction(()=>!document.querySelector('#editor').readOnly);assert.equal(await page.locator('#editor').inputValue(),'取消前原文');assert(await released());
  const session=lastSession;let rejected=false;
  for(let attempt=0;attempt<10&&!rejected;attempt++){rejected=await page.evaluate(async session=>(await fetch('/api/voice/chunk',{method:'POST',headers:{'Content-Type':'application/octet-stream','X-Voice-Session':session},body:new Float32Array(3200)})).status===400,session);if(!rejected)await page.waitForTimeout(100);}
  assert(rejected);cases.push({name:'Explicit cancel restores original and releases backend session',passed:true});await clean();console.log('CANCEL_PASSED');
  let fault=false;await page.route('**/api/voice/chunk',route=>fault?route.fulfill({status:500,contentType:'application/json',body:JSON.stringify({error:'Injected validation failure'})}):route.continue());
  await started('故障前文字');fault=true;await page.waitForFunction(()=>!document.querySelector('#editor').readOnly&&document.querySelector('#feedback').textContent.includes('已保留收到的识别文字'));
  const kept=await page.locator('#editor').inputValue();assert(kept.startsWith('故障前文字')&&kept.length>'故障前文字'.length);assert(await released());cases.push({name:'Injected request failure preserves received text',passed:true});fault=false;await page.unroute('**/api/voice/chunk');await clean();console.log('FAILURE_PRESERVATION_PASSED');
  await cdp.send('Emulation.setFocusEmulationEnabled',{enabled:false});
  await page.evaluate(()=>{window.testVisibility=[];document.addEventListener('visibilitychange',()=>window.testVisibility.push(document.visibilityState));});
  await started('切换前文字');await device.shell('input keyevent 3');await page.waitForTimeout(800);
  const homeActivity=(await device.shell('dumpsys activity activities')).toString().split('\n').find(line=>line.includes('topResumedActivity'))?.trim();
  await device.shell('am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER com.android.chrome');await page.bringToFront();
  const visibility=await page.evaluate(()=>window.testVisibility);console.log('BACKGROUND_VISIBILITY',visibility);
  const stopped=await page.evaluate(()=>!document.querySelector('#editor').readOnly);
  cases.push({name:'Android home and return visibility',passed:stopped,visibility,homeActivity,reason:stopped?undefined:'Android Home was observed, but instrumented Chrome did not emit a hidden event; native lifecycle/device validation remains required'});
  if(!stopped)await page.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,get:()=>true});document.dispatchEvent(new Event('visibilitychange'));delete document.hidden;});
  await page.waitForFunction(()=>!document.querySelector('#editor').readOnly);const hiddenKept=await page.locator('#editor').inputValue();assert(hiddenKept.startsWith('切换前文字')&&hiddenKept.length>'切换前文字'.length);assert(await released());cases.push({name:'Visibility handler preserves text and stops capture',passed:true,source:stopped?'Native event':'Explicit synthetic visibility event; not real Android background proof'});await clean();console.log('VISIBILITY_HANDLER_PASSED');
  await started('');const start=performance.now(),startChunks=chunks;let limitHit=false;
  while(performance.now()-start<450000){
    await page.waitForTimeout(10000);limitHit=await page.evaluate(()=>!document.querySelector('#editor').readOnly&&document.querySelector('#feedback').textContent.includes('最长六分钟'));
    if(limitHit)break;
    const seconds=Math.round((performance.now()-start)/1000);if(seconds%30<10)console.log('LONG_RECORDING_PROGRESS',seconds,'seconds',chunks-startChunks,'chunks');
  }
  assert(limitHit,'Real-time recording must hit the six-minute sample limit');assert((await page.locator('#editor').inputValue()).length>0);assert(await released());cases.push({name:'Six minute continuous real-time audio',passed:true,elapsedSeconds:Math.round((performance.now()-start)/1000),chunkRequests:chunks-startChunks,limitStopsCapture:true,preservesTranscript:true});await clean();console.log('SIX_MINUTE_LIMIT_PASSED');
  assert.deepEqual(errors,[]);const report={scope:'Android 15 Chrome lifecycle; synthetic/official audio, isolated desktop ASR; not native IME, physical microphone, K60 performance or HyperOS',cases,errors};await writeFile(new URL('../docs/android-lifecycle-validation.json',import.meta.url),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
}catch(error){await writeFile(new URL('../docs/android-lifecycle-validation.json',import.meta.url),JSON.stringify({scope:'Android Chrome lifecycle validation',cases,error:error.message},null,2));throw error;}
finally{await clean().catch(()=>{});await context.close();await device.close();}
