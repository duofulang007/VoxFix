import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
const {chromium}=createRequire(import.meta.url)('playwright');
const server=createServer(async(req,res)=>{
  const path=req.url==='/'?'index.html':req.url.slice(1);
  if(!/^[a-zA-Z0-9.-]+$/.test(path)){res.writeHead(404);res.end();return;}
  try{res.setHeader('Content-Type',path.endsWith('.mjs')?'text/javascript':path.endsWith('.css')?'text/css':path.endsWith('.json')?'application/json':'text/html');res.end(await readFile(new URL(path,import.meta.url)));}catch{res.writeHead(404);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({channel:'msedge',headless:true});
try{
  const page=await browser.newPage({viewport:{width:393,height:550}}),errors=[];
  page.setDefaultTimeout(3000);
  page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(()=>{
    window.calls=[];
    window.VoxFixIme=Object.fromEntries(['compose','commit','delete','enter','hide','switcher','stopVoice','cancelVoice','cursorPosition','gestureStart','gestureEnd','gestureLock','gestureUnlock','gestureDelta','selectionStep','haptic','edit','ready','resize','refreshClipboard','candidatesVisible','selectionEditing'].map(name=>[name,(...args)=>calls.push([name,...args])]));
    VoxFixIme.voiceReady=()=>true;
    VoxFixIme.startVoice=()=>{calls.push(['startVoice']);setTimeout(()=>voxfixVoiceListening(),0);};
  });
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.locator('[data-action="voice-toggle"]').click();
  const rail=await page.locator('.keyboard-footer .cursor-zone').boundingBox();
  await page.mouse.move(rail.x+rail.width/2,rail.y+rail.height/2);await page.mouse.down();
  await page.waitForTimeout(430);
  assert.equal(await page.locator('.selection-editor').count(),1,'Sticky voice must allow the rail long press to open the editor');
  await page.mouse.move(rail.x+rail.width/2+25,rail.y+rail.height/2);await page.mouse.up();
  await page.evaluate(()=>voxfixSelection(2,5,3));
  assert.equal(await page.locator('.selection-count').innerText(),'已选 3 字');
  await page.locator('.selection-direction[data-direction="1,0"]').click();
  await page.locator('[data-action="copy-text"]').click();
  let calls=await page.evaluate(()=>window.calls);
  assert(calls.some(row=>row[0]==='selectionEditing'&&row[1]===true),'Selection editing must pause native voice');
  assert(calls.some(row=>row[0]==='selectionStep'&&row[1]===1),'Direction buttons remain usable');
  assert(calls.some(row=>row[0]==='edit'&&row[1]==='copy-text'),'Copy remains usable');
  assert(!calls.some(row=>['stopVoice','cancelVoice'].includes(row[0])),'Editing preserves sticky voice');
  await page.locator('.selection-editor-backdrop').click({position:{x:5,y:5}});
  calls=await page.evaluate(()=>window.calls);
  assert.deepEqual(calls.filter(row=>row[0]==='selectionEditing').at(-1),['selectionEditing',false],'Closing releases the voice pause');
  await page.evaluate(()=>{voxfixSelectionReleased();voxfixSelectionOrigin();voxfixSelection(1,3,2);});
  assert.equal(await page.locator('.selection-editor').count(),1,'Host selection opens the editor during sticky voice too');
  assert.equal(await page.locator('.selection-count').innerText(),'已选 2 字');
  assert.deepEqual(errors,[]);
  console.log('PASS: sticky voice + rail lock/drag, direction, copy, close/resume signal, host selection');
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
