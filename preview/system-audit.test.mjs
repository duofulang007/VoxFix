import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
const {chromium}=createRequire(import.meta.url)('playwright');
const server=createServer(async(req,res)=>{
  const name=req.url==='/'?'index.html':req.url.slice(1);
  if(!/^[\w.-]+$/.test(name)){res.writeHead(404);res.end();return;}
  try{res.setHeader('Content-Type',name.endsWith('.mjs')?'text/javascript':name.endsWith('.css')?'text/css':name.endsWith('.json')?'application/json':'text/html');res.end(await readFile(new URL(name,import.meta.url)));}catch{res.writeHead(404);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({channel:'msedge',headless:true}),failures=[];
async function check(name,run,{motion=false,reducedMotion='no-preference'}={}){
  const page=await browser.newPage({viewport:{width:393,height:531},hasTouch:true,reducedMotion}),errors=[];
  page.setDefaultTimeout(3000);page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(({motion})=>{
    if(motion)Object.defineProperty(navigator,'webdriver',{get:()=>false});
    window.calls=[];
    window.VoxFixIme=Object.fromEntries(['compose','commit','completeEnglish','copy','delete','enter','hide','switcher','startVoice','startLocalVoice','stopVoice','cancelVoice','moveCursor','cursorPosition','gestureStart','gestureLock','gestureEnd','edit','haptic','ready','resize','refreshClipboard','importClipboard','clipHint'].map(name=>[name,(...args)=>calls.push([name,...args])]));
    VoxFixIme.voiceReady=()=>true;VoxFixIme.pinyinReady=()=>true;
    VoxFixIme.pinyinCandidates=()=>JSON.stringify({words:['你好','你']});
    VoxFixIme.pinyinChoose=(...args)=>{calls.push(['pinyinChoose',...args]);return JSON.stringify({committed:true,remaining:''});};
    VoxFixIme.commitAccepted=()=>JSON.stringify({committed:true});
  },{motion});
  const key=(action,value)=>page.locator(`#keyboard [data-action="${action}"]${value===undefined?'':`[data-value="${value}"]`}`).first();
  try{
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForFunction(()=>typeof voxfixStartInput==='function'&&document.querySelector('#pinyin-status').textContent.includes('已就绪'));
    await run(page,key);assert.deepEqual(errors,[]);console.log('PASS '+name);
  }catch(error){failures.push(name+': '+error.message);console.log('FAIL '+name+': '+error.message.slice(0,300));}
  finally{await page.close();}
}
try{
  await check('password restart immediately removes existing English suggestions',async(page,key)=>{
    await page.evaluate(()=>voxfixStartInput(33,false));
    await key('letter','h').click();await key('letter','e').click();
    await page.waitForFunction(()=>document.querySelector('[data-action="english-candidate"]'));
    await page.evaluate(()=>voxfixStartInput(129,true));
    assert.equal(await page.locator('[data-action="english-candidate"]').count(),0);
    await key('letter','l').click();
    assert.equal(await page.locator('[data-action="english-candidate"]').count(),0);
  });
  await check('no personalized learning flag reaches native learning and resets per field',async(page,key)=>{
    await page.evaluate(()=>voxfixStartInput(1,false,0x1000000));
    await key('letter','N').click();await key('letter','I').click();await key('candidate').click();
    assert.equal(await page.evaluate(()=>calls.find(row=>row[0]==='pinyinChoose')?.[3]),false);
    assert.equal(await page.evaluate(()=>localStorage.getItem('voxfix-learning-v1')),null);
    await page.evaluate(()=>voxfixStartInput(1,false,0));
    await key('letter','N').click();await key('letter','I').click();await key('candidate').click();
    assert.equal(await page.evaluate(()=>calls.filter(row=>row[0]==='pinyinChoose').at(-1)?.[3]),true);
  });
  await check('no personalized learning flag prevents fallback history updates',async(page,key)=>{
    await page.evaluate(()=>{VoxFixIme.pinyinReady=()=>false;voxfixPinyinReady(true);voxfixStartInput(1,false,0x1000000);});
    await key('letter','N').click();await key('letter','I').click();await key('candidate').click();
    assert.equal(await page.evaluate(()=>localStorage.getItem('voxfix-learning-v1')),null);
    await page.evaluate(()=>voxfixStartInput(1,true,0));
    await key('letter','N').click();await key('letter','I').click();await key('candidate').click();
    assert(await page.evaluate(()=>JSON.parse(localStorage.getItem('voxfix-learning-v1')||'[]').length>0));
  });
  await check('editor entrance survives an ordinary native redraw',async(page)=>{
    const result=await page.evaluate(()=>{
      voxfixSelectionOrigin();
      const editor=document.querySelector('.selection-editor'),animation=editor.getAnimations()[0];
      if(!animation)throw Error('Entrance was not exercised');
      animation.pause();animation.currentTime=130;
      voxfixRedraw();
      const current=document.querySelector('.selection-editor');
      return {same:current===editor,running:current.getAnimations().includes(animation),time:animation.currentTime};
    });
    assert.equal(result.same,true,'Redraw replaced the animated panel');
    assert.equal(result.running,true);assert.equal(result.time,130);
  },{motion:true});
  await check('closing or changing input field cancels the keyboard entrance',async(page)=>{
    const result=await page.evaluate(()=>{
      voxfixSelectionOrigin();
      const keyboard=document.querySelector('#keyboard'),animation=keyboard.getAnimations()[0];
      if(!animation)throw Error('Keyboard entrance was not exercised');
      animation.pause();animation.currentTime=120;
      voxfixStartInput(2,false);
      return {animations:keyboard.getAnimations().length,transform:getComputedStyle(keyboard).transform,numbers:!!keyboard.querySelector('.number-body')};
    });
    assert.equal(result.animations,0,'Old field animation still moves the new keyboard');
    assert.equal(result.transform,'none');assert.equal(result.numbers,true);
  },{motion:true});
  await check('reduced motion skips editor and keyboard entrance',async(page)=>{
    await page.evaluate(()=>voxfixSelectionOrigin());
    assert.equal(await page.evaluate(()=>document.querySelector('#keyboard').getAnimations({subtree:true}).length),0);
  },{motion:true,reducedMotion:'reduce'});
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
assert.deepEqual(failures,[]);
