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
  const page=await browser.newPage({viewport:{width:1200,height:1000},hasTouch:true}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(()=>document.querySelector('.keyboard-footer .cursor-zone'));
  const text='第一行甲乙丙丁戊己庚辛\n第二行甲乙丙丁戊己庚辛\n第三行甲乙丙丁戊己庚辛\n第四行甲乙丙丁戊己庚辛';
  await page.locator('#editor').evaluate((e,text)=>{e.value=text;e.setSelectionRange(text.length,text.length);e.dispatchEvent(new Event('input'));},text);
  const selection=()=>page.locator('#editor').evaluate(e=>({start:e.selectionStart,end:e.selectionEnd,text:e.value,scroll:e.scrollTop}));
  const cursorTrack=await page.locator('.keyboard-footer .cursor-zone').boundingBox(),center=cursorTrack.x+cursorTrack.width/2,trackY=cursorTrack.y+cursorTrack.height/2;
  const longText='甲😀乙'.repeat(100);
  await page.locator('#editor').evaluate((e,text)=>{e.value=text;e.setSelectionRange(200,200);},longText);
  for(const direction of [-1,1]){
    await page.mouse.move(center,trackY);await page.mouse.down();await page.mouse.move(direction<0?cursorTrack.x+5:cursorTrack.x+cursorTrack.width-5,trackY);
    const initial=(await selection()).end;await page.waitForTimeout(850);const advanced=(await selection()).end;
    assert(direction*(advanced-initial)>=3,'Local cursor must keep moving in either direction without splitting emoji');
    // 摇杆速率档下光标是持续移动的：起点/终点必须取自同一次读取，否则两次读之间它又走了一步。
    const during=await selection();assert.equal(during.start,during.end,'Dragging the rail must move the caret, never extend a selection');
    await page.mouse.up();
    const stopped=(await selection()).end;await page.waitForTimeout(650);assert.equal((await selection()).end,stopped,'Releasing the rail must stop the caret');
  }
  await page.locator('#editor').evaluate((e,text)=>{e.value=text;e.setSelectionRange(text.length,text.length);},text);
  // 锁定与面板是两件事：收起面板不等于解锁。要回到「未锁定」得先在面板里长按小轨解除。
  async function unlock(){
    if(!await page.locator('.selection-editor').count())return;
    if(await page.locator('.selection-mini-track.locked').count()){
      const r=await page.locator('.selection-mini-track').boundingBox();
      await page.mouse.move(r.x+r.width/2,r.y+r.height/2);await page.mouse.down();await page.waitForTimeout(400);await page.mouse.up();
    }
    await page.locator('.selection-editor-backdrop').click({position:{x:8,y:8}});
  }
  async function hold(mini=false){
    if(!mini)await unlock();
    const r=await page.locator(mini?'.selection-mini-track':'.keyboard-footer .cursor-zone').boundingBox();
    const p={x:r.x+r.width/2,y:r.y+r.height/2};await page.mouse.move(p.x,p.y);await page.mouse.down();await page.waitForTimeout(mini?50:380);
    assert.equal(await page.locator('.selection-count').count(),1);return p;
  }
  let p=await hold();const height=await page.locator('#keyboard').evaluate(e=>e.offsetHeight);
  await page.mouse.move(p.x-22,p.y-28);const first=await selection();assert(first.start<text.length-2);assert.equal(first.end,text.length);
  const count=await page.locator('.selection-count').innerText();assert.equal(count,`已选 ${[...new Intl.Segmenter('zh',{granularity:'grapheme'}).segment(text.slice(first.start))].length} 字`);
  await page.mouse.up();assert.equal(await page.locator('.selection-pad').count(),0);assert.equal(await page.locator('.selection-editor').count(),1);
  assert.equal(await page.locator('#keyboard').evaluate(e=>e.offsetHeight),height);
  p=await hold(true);assert.deepEqual(await selection(),first,'Mini touch resumes the same anchored selection');
  await page.mouse.move(p.x-22,p.y-28);const resumed=await selection();assert(resumed.start<first.start);assert.equal(resumed.end,text.length);
  await page.mouse.move(p.x+11,p.y-56);const diagonal=await selection();assert(diagonal.start<first.start);assert.equal(diagonal.end,text.length);
  await page.mouse.move(p.x-11,p.y-28);assert((await selection()).start>diagonal.start,'Changing direction during the hold shrinks the same selection');
  await page.mouse.up();
  // 清楚的竖向移动也要取消长按，不能因为横向没动就误入框选。
  await unlock();const r=await page.locator('.keyboard-footer .cursor-zone').boundingBox();await page.mouse.move(r.x+r.width/2,r.y+r.height/2);await page.mouse.down();await page.mouse.move(r.x+r.width/2,r.y+r.height/2-18);await page.waitForTimeout(400);assert.equal(await page.locator('.selection-editor').count(),0);await page.mouse.up();
  // 长按后原地停在手机底部，不能自动向下续移。
  p=await hold();const stationary=await selection();await page.waitForTimeout(800);assert.deepEqual(await selection(),stationary);
  await page.mouse.up();
  // 向上边缘拖动才开始续移；断触停止，但选区留下。
  p=await hold();const kb=await page.locator('#keyboard').boundingBox();await page.mouse.move(p.x,kb.y+48);await page.waitForTimeout(700);const expanded=await selection();
  await page.locator('#keyboard').evaluate(e=>e.dispatchEvent(new PointerEvent('pointercancel',{pointerId:1,bubbles:true})));
  await page.mouse.up();const stopped=await selection();await page.waitForTimeout(650);assert.deepEqual(await selection(),stopped);assert.equal(await page.locator('.selection-pad').count(),0);assert(expanded.start<=stationary.start);
  // 摇杆：只推到键盘上部的中间地带（离边缘还有 70px，旧实现这里不会续移），停住不动也要继续扩展选区。
  // 注意不能用 start 判断 —— 向上扩展时动的是 head（end），锚点在文末不动。
  const selectedChars=async()=>Number((await page.locator('.selection-count').innerText()).replace(/[^\d]/g,''));
  // 先把选区收成末端的光标点，否则上一段的选区锚点在左端、向上拖反而会让选区变小。
  await page.locator('#editor').evaluate((e,t)=>{e.setSelectionRange(t.length,t.length);},text);
  p=await hold();const kbMid=await page.locator('#keyboard').boundingBox();
  await page.mouse.move(p.x,kbMid.y+70);await page.waitForTimeout(150);
  const midStart=await selection(),midCount=await selectedChars();
  await page.waitForTimeout(700);const midEnd=await selection();
  assert(await selectedChars()>midCount,'Stopping away from any edge must keep extending the selection');
  assert.equal(midEnd.end,midStart.end,'The fixed anchor must not move');
  await page.mouse.up();
  // 点击字母立即替换选区；下一次长按从新的光标开始。
  await unlock();await page.locator('#keyboard [data-action="language"]').click();await page.locator('#keyboard [data-action="letter"][data-value="a"]').click();assert((await selection()).text.includes('a'));assert.equal((await selection()).start,(await selection()).end);
  await page.screenshot({path:new URL('../.android/selection-browser.png',import.meta.url).pathname.replace(/^\/([A-Za-z]:)/,'$1')});
  assert.deepEqual(errors,[]);
  const native=await browser.newPage({viewport:{width:393,height:351},hasTouch:true});
  await native.addInitScript(()=>{
    window.calls=[];window.VoxFixIme=Object.fromEntries(['ready','resize','refreshClipboard','compose','commit','moveCursor','cursorPosition','gestureStart','gestureLock','gestureUnlock','gestureMove','gestureMove2D','gestureEnd','haptic','edit'].map(name=>[name,(...args)=>calls.push([name,...args])]));
    VoxFixIme.voiceReady=()=>true;VoxFixIme.handReady=()=>false;
  });
  await native.goto(`http://127.0.0.1:${server.address().port}/`);await native.waitForSelector('.keyboard-footer .cursor-zone');
  const track=await native.locator('.keyboard-footer .cursor-zone').boundingBox(),x=track.x+track.width/2,y=track.y+track.height/2;
  const nativeHold=async()=>{if(await native.locator('.selection-editor').count())await native.locator('.selection-editor-backdrop').click({position:{x:8,y:8}});await native.mouse.move(x,y);await native.mouse.down();await native.waitForTimeout(380);};
  await nativeHold();await native.evaluate(()=>voxfixSelection(7,22,8));assert.equal(await native.locator('.selection-count').innerText(),'已选 8 字');
  await native.mouse.move(x+22,y-28);assert((await native.evaluate(()=>calls)).some(row=>row[0]==='gestureMove2D'&&row[1]===22&&row[2]===-28));
  await native.mouse.up();
  // 已锁定时再长按主轨 = 解除锁定：收到 gestureUnlock，选区收成活动终点。
  await nativeHold();assert((await native.evaluate(()=>calls)).some(row=>row[0]==='gestureUnlock'),'A main long hold while locked unlocks the origin');
  assert.equal(await native.locator('.selection-count').innerText(),'已选 0 字','Unlocking collapses the selection onto the active end');
  assert.equal(await native.locator('.selection-mini-track.locked').count(),0);
  await native.mouse.up();
  // 未锁定时长按主轨 = 标定新起点：收到 gestureLock。
  await nativeHold();assert((await native.evaluate(()=>calls)).some(row=>row[0]==='gestureLock'),'A main long hold while unlocked locks a fresh origin');
  await native.mouse.move(x-22,y-28);await native.mouse.move(x-11,y-12);assert((await native.evaluate(()=>calls)).some(row=>row[0]==='gestureMove2D'&&row[1]===11&&row[2]===16),'Bridge must receive new movement, not the old absolute displacement');
  await native.mouse.up();
  // 摇杆续移：先长按解除上一次锁定，抬手后再干净地长按标定新起点，然后把手指推到键盘上部中间地带。
  await nativeHold();await native.mouse.up();await nativeHold();
  const rNative=await native.locator('#keyboard').boundingBox();await native.mouse.move(x+12,rNative.y+58);await native.waitForTimeout(850);
  const beforeStop=(await native.evaluate(()=>calls)).filter(row=>row[0]==='gestureMove2D').length;assert(beforeStop>3);
  // 摇杆按帧推进（~16ms），读数与 reset 之间必然还会再走几帧 ⇒ 基线只能在 reset 之后再取，
  // 否则比较的是两个不同时刻的快照（旧实现 260ms 一次间隔时侥幸没暴露）。
  await native.evaluate(()=>voxfixReset());await native.waitForTimeout(600);
  const afterReset=(await native.evaluate(()=>calls)).filter(row=>row[0]==='gestureMove2D').length;await native.waitForTimeout(500);
  assert.equal((await native.evaluate(()=>calls)).filter(row=>row[0]==='gestureMove2D').length,afterReset,'Reset must stop the repetition');await native.mouse.up();
  console.log('PASS: diagonal movement, count, fixed anchor/resume, reverse drag, stationary bottom, cancel, immediate edit, native 2D bridge/lock toggle/reset');
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
