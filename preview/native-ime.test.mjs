import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
const {chromium}=createRequire(import.meta.url)('playwright');
const server=createServer(async(req,res)=>{
  const path=req.url==='/'?'index.html':req.url.slice(1);
  if(!/^[a-zA-Z0-9.-]+$/.test(path)){res.writeHead(404);res.end();return;}
  try{res.setHeader('Content-Type',path.endsWith('.mjs')?'text/javascript':path.endsWith('.css')?'text/css':path.endsWith('.json')?'application/json':'text/html');res.end(await readFile(new URL(path,import.meta.url)));}
  catch{res.writeHead(404);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({channel:'msedge',headless:true});
try{
  const page=await browser.newPage({viewport:{width:393,height:340},hasTouch:true}),errors=[],requests=[];
  page.on('pageerror',error=>errors.push(error.message));page.on('request',request=>requests.push(request.url()));
  await page.addInitScript(()=>{
    window.calls=[];window.VoxFixIme=Object.fromEntries(['compose','commit','delete','enter','hide','switcher','startVoice','stopVoice','cancelVoice','moveCursor','edit','ready','resize','refreshClipboard'].map(name=>[name,(...args)=>window.calls.push([name,...args])]));
    window.VoxFixIme.voiceReady=()=>true;
    window.VoxFixIme.handReady=()=>true;
    window.VoxFixIme.recognizeInk=(serial,payload)=>{window.calls.push(['recognizeInk',serial,payload]);setTimeout(()=>window.voxfixInkResult(serial,['一'],null),10);};
    for(const name of ['commit','delete'])window.VoxFixIme[name]=(...args)=>{window.calls.push([name,...args]);setTimeout(()=>window.voxfixClearComposition?.(),10);};
  });
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(()=>typeof window.voxfixReset==='function'&&document.querySelector('#pinyin-status').textContent.includes('已就绪'));
  const key=action=>page.locator(`#keyboard [data-action="${action}"]`).first();
  await page.locator('#keyboard [data-action="letter"][data-value="N"]').click();
  await page.locator('#keyboard [data-action="letter"][data-value="I"]').click();
  const word=await page.locator('.candidate-visible button').first().getAttribute('data-value');
  await page.locator('.candidate-visible button').first().click();
  let calls=await page.evaluate(()=>window.calls);
  assert(calls.some(row=>row[0]==='compose'&&row[1]==='ni'));
  assert(calls.some(row=>row[0]==='commit'&&row[1]===word));
  await key('delete').click();await key('enter').click();
  await key('language').click();await page.locator('#keyboard [data-action="letter"]').first().click();
  calls=await page.evaluate(()=>window.calls);assert(calls.some(row=>row[0]==='commit'&&row[1]==='q'));
  assert(calls.some(row=>row[0]==='delete'));assert(calls.some(row=>row[0]==='enter'));
  await key('numbers').click();
  for(const number of ['1','2','3']){await page.locator(`#keyboard [data-action="insert"][data-value="${number}"]`).first().click();await page.waitForTimeout(30);}
  assert(await page.locator('.number-body').isVisible(),'Selection echoes must keep the numeric keyboard open');
  await page.evaluate(()=>{window.voxfixReset();window.voxfixInputType(2);});assert(await page.locator('.number-body').isVisible());
  await page.evaluate(()=>{window.voxfixReset();window.voxfixInputType(33);});assert.equal(await page.locator('#keyboard [data-action="letter"]').first().getAttribute('data-value'),'q');
  await page.evaluate(()=>window.voxfixReset());
  const box=await key('space').boundingBox(),x=box.x+box.width/2,y=box.y+box.height/2;
  await page.mouse.move(x,y);await page.mouse.down();await page.waitForTimeout(500);await page.mouse.up();
  calls=await page.evaluate(()=>window.calls);assert.equal(calls.filter(row=>row[0]==='startVoice').length,1);assert.equal(calls.filter(row=>row[0]==='stopVoice').length,1);
  await page.evaluate(()=>window.voxfixVoiceDone(''));
  await page.mouse.move(x,y);await page.mouse.down();await page.waitForTimeout(500);await page.mouse.move(x,y-70);await page.mouse.up();
  calls=await page.evaluate(()=>window.calls);assert.equal(calls.filter(row=>row[0]==='cancelVoice').length,1);
  await page.mouse.move(x,y);await page.mouse.down();await page.waitForTimeout(500);
  await page.evaluate(()=>window.voxfixVoiceLevel(12));
  assert.equal(await page.locator('.waveform').evaluate(element=>element.style.getPropertyValue('--mic-level')),'1');
  await page.mouse.up();await page.evaluate(()=>window.voxfixVoiceDone(''));
  assert.equal(await page.locator('.voice-panel').count(),0,'Completion must clear the voice animation');
  await page.evaluate(()=>window.voxfixReset());await key('switcher').click();
  await page.evaluate(()=>window.voxfixClipboard('测试剪贴板'));await key('clipboard').click();
  assert(await page.locator('#keyboard').textContent().then(text=>text.includes('测试剪贴板')));
  await page.evaluate(()=>window.voxfixReset());
  await key('menu').click();await key('custom').click();assert(await page.locator('dialog[open] #custom-panel').isVisible());await page.getByRole('button',{name:'返回键盘',exact:true}).last().click();
  await page.evaluate(()=>window.voxfixReset());
  assert.equal(await page.locator('.composer').isVisible(),false);
  assert.equal(await page.locator('.controls').isVisible(),false);
  const keyboard=await page.locator('#keyboard').boundingBox();assert(keyboard.height>250&&keyboard.height<=340&&keyboard.width===393);
  await key('layouts').click();await page.locator('[data-action="layout"][data-value="fullhand"]').click();
  const canvas=await page.locator('#ink-canvas').boundingBox();assert(canvas&&canvas.height>100,JSON.stringify(await page.locator('#handwriting-surface').evaluate(element=>({hidden:element.hidden,display:getComputedStyle(element).display,rect:element.getBoundingClientRect().toJSON(),canvas:element.querySelector('canvas').getBoundingClientRect().toJSON()}))));
  await page.mouse.move(canvas.x+30,canvas.y+canvas.height/2);await page.mouse.down();await page.mouse.move(canvas.x+canvas.width-30,canvas.y+canvas.height/2,{steps:8});await page.mouse.up();
  await page.locator('[data-action="ink-candidate"]').first().waitFor();await page.locator('[data-action="ink-candidate"]').first().click();
  assert((await page.evaluate(()=>window.calls)).some(row=>row[0]==='commit'&&row[1]==='一'));
  await page.locator('[data-action="last-pinyin"]').click();
  assert(!requests.some(url=>url.includes('/api/')),'Native keyboard must not call the PC service');
  assert.deepEqual(errors,[]);
  await page.screenshot({path:new URL('../docs/native-ime-browser.png',import.meta.url).pathname.replace(/^\/([A-Za-z]:)/,'$1')});
  console.log('PASS: native bridge composition/candidates, English, editing, hold/release/cancel, clipboard, layout and no desktop service');
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
