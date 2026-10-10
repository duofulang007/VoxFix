import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {execFileSync} from 'node:child_process';
import {readFile,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const {_android}=createRequire(import.meta.url)('playwright');
const adb=fileURLToPath(new URL('../.android/sdk/platform-tools/adb.exe',import.meta.url));
const shell=(...args)=>execFileSync(adb,['-s','emulator-5554','shell',...args],{encoding:'utf8',timeout:20000}).trim();
const device=(await _android.devices()).find(device=>device.serial()==='emulator-5554');
assert(device,'Start the dedicated emulator first');
const reports=[],errors=[];
let context,page;
async function launch(){
  await device.shell('am force-stop com.android.chrome');
  context=await device.launchBrowser({viewport:null,hasTouch:true,args:['--autoplay-policy=no-user-gesture-required']});
  page=await context.newPage();page.on('pageerror',error=>errors.push(error.message));
  await page.goto('http://127.0.0.1:18743/');
  await page.waitForFunction(()=>document.querySelector('#pinyin-status')?.textContent.includes('已就绪'));
}
try{
  for(const [label,size,density] of [['K60-2K','1440x3200','560'],['1080-mode','1080x2400','420'],['narrow-layout','1440x3200','700']]){
    if(context)await context.close();shell('wm','size',size);shell('wm','density',density);await launch();
    await page.locator('#quick-pages button[data-value="letters"]').tap();
    await page.locator('#clear-input').tap();
    for(const letter of 'WZDL')await page.locator('#keyboard').getByRole('button',{name:letter,exact:true}).tap();
    assert.equal(await page.locator('#editor').inputValue(),'wzdl');
    assert.equal(await page.locator('.candidate-visible button').first().textContent(),'我知道了');
    await page.locator('.candidate-visible button').first().tap();assert.equal(await page.locator('#editor').inputValue(),'我知道了');
    await page.locator('#clear-input').tap();for(const letter of 'NI')await page.locator('#keyboard').getByRole('button',{name:letter,exact:true}).tap();
    const row=page.locator('.candidate-visible');await row.scrollIntoViewIfNeeded();const box=await row.boundingBox(),cdp=await context.newCDPSession(page),y=box.y+box.height/2;
    await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:box.x+box.width-15,y}]});
    for(let step=1;step<=8;step++)await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:box.x+box.width-15-(box.width-30)*step/8,y}]});
    await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await cdp.detach();
    assert((await row.evaluate(element=>element.scrollLeft))>20,'Touch swipe must scroll candidates');assert.equal(await page.locator('#editor').inputValue(),'ni','Swipe must not select a word');
    const metrics=await page.evaluate(()=>({width:innerWidth,dpr:devicePixelRatio,bodyWidth:document.body.scrollWidth}));assert(metrics.bodyWidth<=metrics.width+1);
    reports.push({label,size,density,...metrics,pinyin:'passed',touchSwipe:'passed'});console.log('PROFILE_PASSED',label);
    await page.locator('#phone').scrollIntoViewIfNeeded();await page.screenshot({path:fileURLToPath(new URL(`../docs/android-${label}.png`,import.meta.url))});
  }
  await writeFile(new URL('../docs/android-emulator-matrix.json',import.meta.url),JSON.stringify({scope:'Android 15 emulator Chrome touch/layout validation',profiles:reports},null,2));
  await context.close();shell('wm','size','reset');shell('wm','density','reset');await launch();
  await page.waitForFunction(()=>!document.querySelector('#long-voice').disabled);
  const wav=(await readFile(new URL('../models/test/0.wav',import.meta.url))).toString('base64');
  await page.evaluate(wav=>{
    window.testVoiceUpdates=[];window.testVoiceTimer=setInterval(()=>{const text=document.querySelector('#editor').value;if(text&&window.testVoiceUpdates.at(-1)!==text)window.testVoiceUpdates.push(text);},100);
    navigator.mediaDevices.getUserMedia=async()=>{
      const audio=new AudioContext(),bytes=Uint8Array.from(atob(wav),char=>char.charCodeAt(0));await audio.resume();
      const source=audio.createBufferSource();source.buffer=await audio.decodeAudioData(bytes.buffer);const destination=audio.createMediaStreamDestination();source.connect(destination);
      source.onended=()=>{window.testAudioFinished=true;audio.close();};source.start(audio.currentTime+.5);return destination.stream;
    };
  },wav);
  await page.locator('#long-voice').tap();await page.waitForFunction(()=>window.testAudioFinished,{},{timeout:30000});
  await page.locator('#finish-voice').tap();await page.waitForFunction(()=>!document.querySelector('#editor').readOnly,{},{timeout:30000});
  const voice={source:'Official WAV through actual Android Web Audio/AudioWorklet, not microphone capture',engineLocation:'Windows host via adb reverse',updates:await page.evaluate(()=>{clearInterval(window.testVoiceTimer);return window.testVoiceUpdates.length;}),text:await page.locator('#editor').inputValue()};assert(voice.updates>=2&&voice.text.includes('星期三'));
  console.log('VOICE_PASSED',voice.updates);await writeFile(new URL('../docs/android-emulator-voice.json',import.meta.url),JSON.stringify(voice,null,2));
  const original='我明天跑10公里不要跑15公里然后然后打开Obsidian';
  // Seed pasted text, not unfinished pinyin from the trailing English name.
  await page.evaluate(text=>{document.activeElement?.blur();const editor=document.querySelector('#editor');editor.value=text;editor.setSelectionRange(text.length,text.length);editor.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertFromPaste',data:text}));},original);
  await page.locator('#polish-text').tap();
  console.log('POLISH_UI',await page.evaluate(()=>({open:document.querySelector('#polish-dialog').open,message:document.querySelector('#polish-message').textContent,feedback:document.querySelector('#feedback').textContent})));
  await page.waitForFunction(()=>document.querySelector('#polish-dialog').open,{},{timeout:5000});
  await page.waitForFunction(()=>!document.querySelector('#polish-apply').disabled||document.querySelector('#polish-message').textContent.includes('原文未修改'),{},{timeout:120000});assert(!await page.locator('#polish-apply').isDisabled(),await page.locator('#polish-message').textContent);const polished=await page.locator('#polish-preview').inputValue();assert.equal(await page.locator('#editor').inputValue(),original);
  await page.locator('#polish-apply').tap();assert.equal(await page.locator('#editor').inputValue(),polished);await page.locator('#undo-polish').tap();assert.equal(await page.locator('#editor').inputValue(),original);assert.deepEqual(errors,[]);
  const report={scope:'Official Android 15 x86_64 emulator, Playwright Android API and Chrome; desktop ASR/LLM backend; not native IME, ARM64 or HyperOS',android:shell('getprop','ro.build.version.release'),profiles:reports,voice,polish:{original,polished,previewApplyUndo:'passed',engineLocation:'Windows host'},errors};
  await writeFile(new URL('../docs/android-emulator-validation.json',import.meta.url),JSON.stringify(report,null,2));await page.locator('#phone').scrollIntoViewIfNeeded();await page.screenshot({path:fileURLToPath(new URL('../docs/android-emulator-ready.png',import.meta.url))});console.log(JSON.stringify(report,null,2));
}catch(error){
  await writeFile(new URL('../docs/android-failure.json',import.meta.url),JSON.stringify({message:error.message,profiles:reports},null,2));throw error;
}finally{if(context)await context.close().catch(()=>{});shell('wm','size','reset');shell('wm','density','reset');await device.close();}
