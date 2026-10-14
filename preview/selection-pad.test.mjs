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
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({channel:'msedge',headless:true});
try{
  const page=await browser.newPage({viewport:{width:393,height:351},hasTouch:true});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(()=>{
    globalThis.calls=[];
    globalThis.VoxFixIme=Object.fromEntries(['ready','resize','refreshClipboard','voiceReady','cursorPosition','gestureStart','gestureLock','gestureUnlock','gestureMove','gestureMove2D','gestureEnd','selectionStep','edit','haptic'].map(name=>[name,(...args)=>{calls.push([name,...args]);return name==='voiceReady';}]));
  });
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(()=>typeof voxfixSelectionOrigin==='function');
  for(const mini of [false,true]){
    await page.evaluate(mini=>{voxfixSelectionReleased();if(mini){voxfixSelectionOrigin();voxfixSelection(6,12,6);}calls.length=0;},mini);
    const box=await page.locator(mini?'.selection-mini-track':'.keyboard-footer .cursor-zone').boundingBox();
    const x=box.x+box.width/2,y=box.y+box.height/2,push=box.width*.46;
    await page.mouse.move(x,y);await page.mouse.down();await page.mouse.move(x+push,y);await page.waitForTimeout(90);
    await page.mouse.move(x+push,y+1);await page.evaluate(()=>calls.length=0);await page.waitForTimeout(250);
    const held=await page.evaluate(()=>calls.filter(c=>c[0]==='gestureMove2D'));
    assert(held.some(c=>c[1]>0),'A 1px perpendicular tremor must not stop the held joystick: '+(mini?'mini':'main'));
    assert(held.every(c=>Math.abs(c[2])<.01),'A tremor must not change the held direction');
    if(mini){
      await page.evaluate(()=>{voxfixSelectionBlocked();calls.length=0;});
      await page.mouse.move(x+push,y+2);await page.waitForTimeout(180);
      assert.equal(await page.evaluate(()=>calls.filter(c=>c[0]==='gestureMove2D'&&(Math.abs(c[1])>.01||Math.abs(c[2])>.01)).length),0,'A 1px tremor at a blocked edge must not restart navigation');
    }
    await page.mouse.up();
    const heldBox=await page.locator(mini?'.selection-mini-track':'.keyboard-footer .cursor-zone').boundingBox();
    const hx=heldBox.x+heldBox.width/2,hy=heldBox.y+heldBox.height/2;
    await page.mouse.move(hx,hy);await page.mouse.down();
    // 主轨取消跨段（纵向）：只沿滑轨横向推；编辑框小轨仍保留上下。
    await page.mouse.move(mini?hx:hx+heldBox.width*.46,mini?hy-80:hy,{steps:8});
    await page.evaluate(()=>calls.length=0);await page.waitForTimeout(700);
    const heldMove=await page.evaluate(()=>calls.filter(c=>c[0]==='gestureMove2D'));
    assert(mini?heldMove.reduce((sum,c)=>sum-c[2],0)>28:heldMove.reduce((sum,c)=>sum+Math.abs(c[1]),0)>28,'Holding the rail away from the origin must keep sending without finger movement: '+(mini?'mini':'main'));
    assert(mini||heldMove.every(c=>Math.abs(c[2])<.01),'The main rail must never emit vertical movement');
    await page.mouse.move(hx,hy);await page.evaluate(()=>calls.length=0);await page.waitForTimeout(180);
    assert.equal(await page.evaluate(()=>calls.filter(c=>c[0]==='gestureMove2D'&&(Math.abs(c[1])>.01||Math.abs(c[2])>.01)).length),0,'Returning to the press origin stops repeated movement');
    await page.mouse.up();
  }
  await page.evaluate(()=>voxfixSelectionReleased());
  await page.evaluate(()=>{voxfixSelectionOrigin();voxfixSelection(6,12,6);});
  const r=await page.locator('.selection-mini-track').boundingBox();
  await page.mouse.move(r.x+r.width/2,r.y+r.height/2);await page.mouse.down();
  await page.mouse.move(r.x+r.width/2,r.y-180,{steps:8});
  await page.mouse.move(r.x+r.width*.9,r.y+r.height/2,{steps:5});
  await page.evaluate(()=>calls.length=0);
  await page.waitForTimeout(150);
  const afterTurn=await page.evaluate(()=>calls.filter(c=>c[0]==='gestureMove2D'));
  const drift=afterTurn.filter(c=>Math.abs(c[2])>.01);
  assert.deepEqual(drift,[],'After turning horizontally, the held joystick must stop sending old vertical movement');
  assert(afterTurn.some(c=>c[1]>0),'A real turn must preserve held movement in the new direction');
  await page.mouse.up();
  const arrow=await page.locator('[data-direction="0,-1"]').boundingBox();
  await page.evaluate(()=>calls.length=0);
  await page.mouse.move(arrow.x+arrow.width/2,arrow.y+arrow.height/2);await page.mouse.down();
  await page.mouse.move(arrow.x+arrow.width/2,arrow.y-100);await page.waitForTimeout(120);await page.mouse.up();
  assert.equal(await page.evaluate(()=>calls.filter(c=>c[0]==='gestureMove2D'||c[0]==='selectionStep').length),0,'Dragging an arrow cannot start joystick selection or trigger a tap');
  const middle=await page.locator('.selection-center').boundingBox();
  await page.mouse.move(middle.x+middle.width/2,middle.y+middle.height/2);await page.mouse.down();
  await page.mouse.move(middle.x+middle.width/2,middle.y-1);
  await page.evaluate(()=>calls.length=0);await page.waitForTimeout(300);
  assert(await page.evaluate(()=>calls.some(c=>c[0]==='gestureMove2D'&&c[2]<0)),'Entering the upward button area from the center immediately starts held movement');
  assert.notEqual(await page.locator('.selection-center span').evaluate(e=>e.style.transform),'','The joystick dot follows the push inside its center target');
  await page.mouse.up();
  assert.equal(await page.locator('.selection-center span').evaluate(e=>e.style.transform),'','Releasing the joystick returns the dot to center');
  assert.equal(await page.evaluate(()=>calls.filter(c=>c[0]==='gestureLock').length),0,'Pushing the center cannot reset the origin');
  await page.evaluate(()=>calls.length=0);
  await page.locator('.selection-center').tap();
  assert.equal(await page.evaluate(()=>calls.filter(c=>c[0]==='gestureLock').length),1,'One center tap resets the origin exactly once');
  assert.equal(await page.locator('.selection-editor').count(),1,'A center tap cannot retarget its compatibility click to the backdrop and close the editor');
  await page.evaluate(()=>voxfixSelection(6,12,6));
  await page.locator('[data-action="copy-text"]').dispatchEvent('click',{detail:1,pointerId:1});
  assert.equal(await page.evaluate(()=>calls.filter(c=>c[0]==='edit'&&c[1]==='copy-text').length),1,'A new control click must not be swallowed by the previous drag');
  await page.evaluate(()=>calls.length=0);
  await page.locator('[data-action="copy-text"]').tap();
  assert.equal(await page.evaluate(()=>calls.filter(c=>c[0]==='edit'&&c[1]==='copy-text').length),1,'The first copy tap after dragging must execute');
  assert.equal(await page.locator('.selection-direction').count(),8);
  assert.equal(await page.locator('[data-action="selection-paragraph"]').count(),0);
  for(const width of [320,393]){
    await page.setViewportSize({width,height:351});
    const targets=await page.locator('.selection-editor button').evaluateAll(es=>es.map(e=>{const r=e.getBoundingClientRect();return {x:r.x,y:r.y,w:r.width,h:r.height};}));
    for(const t of targets)assert(t.w>=48&&t.h>=48,'Every tap target must be at least 48 CSS pixels');
    for(let i=0;i<targets.length;i++)for(let j=i+1;j<targets.length;j++){const a=targets[i],b=targets[j];assert(!(a.x<b.x+b.w&&a.x+a.w>b.x&&a.y<b.y+b.h&&a.y+a.h>b.y),'Tap targets cannot overlap');}
  }
  await page.locator('[data-direction="1,1"]').tap();
  assert((await page.evaluate(()=>calls)).some(c=>c[0]==='selectionStep'&&c[1]===1&&c[2]===1));
  await page.screenshot({path:new URL('../.android/selection-pad-concept.png',import.meta.url).pathname.slice(1)});
  assert.deepEqual(errors,[]);
  await page.evaluate(()=>{calls.length=0;voxfixCursor(18,40);voxfixSelectionReleased();voxfixSelection(18,18,0);});
  assert.equal(await page.locator('.selection-editor').count(),0,'A native manual cursor move closes the old editor');
  assert.equal(await page.locator('.keyboard-footer .cursor-zone.locked').count(),0);
  assert(!(await page.evaluate(()=>calls)).some(c=>c[0]==='gestureUnlock'),'Releasing an old lock must not restore the old selection head');
  const main=await page.locator('.keyboard-footer .cursor-zone').boundingBox(),mx=main.x+main.width/2,my=main.y+main.height/2;
  await page.mouse.move(mx,my);await page.mouse.down();await page.mouse.move(mx+22,my);await page.mouse.up();
  assert((await page.evaluate(()=>calls)).some(c=>c[0]==='gestureStart'&&c[1]===false),'The next short drag starts free cursor movement');
  assert.equal(await page.locator('.selection-editor').count(),0);
  await page.mouse.move(mx,my);await page.mouse.down();await page.waitForTimeout(390);await page.mouse.up();
  assert((await page.evaluate(()=>calls)).some(c=>c[0]==='gestureLock'),'A fresh long press explicitly locks the new position');
  const local=await browser.newPage({viewport:{width:1200,height:1000},hasTouch:true});
  local.on('pageerror',e=>errors.push(e.message));
  await local.goto(`http://127.0.0.1:${server.address().port}/`);
  await local.waitForSelector('.keyboard-footer .cursor-zone');
  await local.locator('#editor').evaluate(e=>{e.value='甲乙丙丁\n戊己庚辛\n壬癸子丑\n寅卯辰巳';e.setSelectionRange(7,7);e.dispatchEvent(new Event('input'));});
  const rail=await local.locator('.keyboard-footer .cursor-zone').boundingBox();
  await local.mouse.move(rail.x+rail.width/2,rail.y+rail.height/2);await local.mouse.down();await local.waitForTimeout(390);await local.mouse.up();
  for(const [direction,head] of [['1,1',14],['-1,1',15],['1,-1',14],['1,-1',9],['-1,-1',0],['1,0',1]]){
    await local.locator(`[data-direction="${direction}"]`).tap();
    assert.deepEqual(await local.locator('#editor').evaluate(e=>[e.selectionStart,e.selectionEnd]),[Math.min(7,head),Math.max(7,head)],'Every diagonal starts from the latest endpoint and keeps anchor 7: '+direction);
  }
  const right=await local.locator('[data-direction="1,0"]').boundingBox();
  await local.mouse.move(right.x+right.width/2,right.y+right.height/2);await local.mouse.down();await local.waitForTimeout(450);
  assert.equal(await local.locator('.selection-mini-track.locked').count(),1,'Holding an arrow must not unlock the origin');
  await local.mouse.up();
  assert.deepEqual(await local.locator('#editor').evaluate(e=>[e.selectionStart,e.selectionEnd]),[2,7],'Holding without dragging still performs one arrow tap');
  await local.locator('.selection-center').tap();
  assert.deepEqual(await local.locator('#editor').evaluate(e=>[e.selectionStart,e.selectionEnd]),[2,2],'A center tap relocks the current backward head, not the old origin');
  await local.locator('[data-direction="1,0"]').tap();
  assert.deepEqual(await local.locator('#editor').evaluate(e=>[e.selectionStart,e.selectionEnd]),[2,3],'The next arrow starts from the new center-tap origin');
  assert.deepEqual(errors,[]);
  console.log('PASS: turn stops vertical drift, first copy tap, eight large targets, diagonal native bridge');
}finally{await browser.close();await new Promise(r=>server.close(r));}

