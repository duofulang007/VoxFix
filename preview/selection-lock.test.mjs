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
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({channel:'msedge',headless:true});
try{
  const page=await browser.newPage({viewport:{width:1200,height:1000}}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(()=>document.querySelector('.keyboard-footer .cursor-zone'));
  const text='abcdefghijklmnopqrstuvwx';
  await page.locator('#editor').evaluate((e,t)=>{e.value=t;e.setSelectionRange(6,6);e.dispatchEvent(new Event('input'));},text);
  const caret=()=>page.locator('#editor').evaluate(e=>({start:e.selectionStart,end:e.selectionEnd,direction:e.selectionDirection,text:e.value}));
  const count=()=>page.locator('.selection-count').count()?page.locator('.selection-count').innerText():'';
  const label=()=>page.locator('.selection-mini-label').count()?page.locator('.selection-mini-label').innerText():'';
  const geometry=()=>page.locator('#keyboard').evaluate(e=>({height:e.offsetHeight,keys:[...e.querySelectorAll('.key-row .key')].map(k=>{const r=k.getBoundingClientRect();return [r.x,r.y,r.width,r.height];})}));
  const original=await geometry();
  const box=sel=>page.locator(sel).boundingBox();
  async function press(sel,fx=.5,fy=.5){const r=await box(sel);const p={x:r.x+r.width*fx,y:r.y+r.height*fy};await page.mouse.move(p.x,p.y);await page.mouse.down();return p;}
  async function longPress(sel){const p=await press(sel);await page.waitForTimeout(430);return p;}
  async function dismiss(){await page.locator('.selection-editor-backdrop').click({position:{x:8,y:8}});}

  // ── 未锁定：拖动移动光标，长按锁起点 ──────────────────────────────────────
  assert.equal(await page.locator('.selection-editor').count(),0,'No panel before any long press');
  let p=await press('.keyboard-footer .cursor-zone');
  await page.mouse.move(p.x-33,p.y);await page.mouse.up();
  let at=await caret();assert.equal(at.start,at.end,'Dragging an unlocked rail moves the caret, never extends a selection');
  assert.equal(at.start,3,'Unlocked drag still uses the same 11px step');
  // 明显拖动取消尚未触发的长按。
  p=await press('.keyboard-footer .cursor-zone');await page.mouse.move(p.x+44,p.y);await page.waitForTimeout(430);
  assert.equal(await page.locator('.selection-editor').count(),0,'An obvious drag cancels the pending long press');
  await page.mouse.up();
  const origin=(await caret()).start;
  p=await longPress('.keyboard-footer .cursor-zone');
  assert.equal(await page.locator('.selection-editor').count(),1,'Long press on the main rail locks the origin and opens the panel');
  assert.equal(await page.locator('.selection-mini-track.locked').count(),1,'The mini rail marks the locked state');
  assert.equal(await label(),'起点锁定 · 拖动调整终点','Locked label');
  assert.deepEqual(await caret(),{start:origin,end:origin,direction:'forward',text},'Locking keeps the caret where it was');
  assert.equal(await page.locator('.selection-paragraphs button:disabled').count(),0,'Paragraph jumps are available while locked');
  // 克制的颜色变化：锁定=浅蓝底 + 主色短线/标语，未锁定=灰。
  const styleOf=sel=>page.locator(sel).evaluate(e=>{const s=getComputedStyle(e);return {bg:s.backgroundColor,opacity:s.opacity,color:s.color};});
  assert.equal((await styleOf('.keyboard-footer .cursor-hint')).bg,'rgb(74, 134, 200)','The main rail hint turns into the accent colour while locked');
  assert.equal((await styleOf('.keyboard-footer .cursor-hint')).opacity,'1','No inline fade value may dim the locked hint');
  assert.equal((await styleOf('.selection-mini-track')).bg,'rgb(230, 239, 249)','Mini rail uses the soft accent background while locked');
  assert.equal((await styleOf('.selection-mini-label')).color,'rgb(74, 134, 200)');
  // 一次长按只切换一次：继续按住不会再翻回去。
  await page.waitForTimeout(600);assert.equal(await page.locator('.selection-mini-track.locked').count(),1,'One long press toggles once');
  await page.mouse.up();

  // ── 已锁定：拖动只调终点，起点不动 ────────────────────────────────────────
  p=await press('.selection-mini-track');
  await page.mouse.move(p.x+44,p.y);await page.mouse.up();
  at=await caret();assert.equal(at.start,origin,'The locked origin must not move');assert(at.end>origin,'Mini rail drag only extends the end point');
  const forward=at.end;
  // 反向拖过起点：终点落到起点左侧，活动端变成 head。
  p=await press('.selection-mini-track');
  await page.mouse.move(p.x-(44+(forward-origin)*7),p.y);await page.mouse.up();
  at=await caret();assert.equal(at.end,origin,'Reverse drag keeps the same locked origin');assert(at.start<origin,'Reverse drag puts the active end point before the origin');
  assert.equal(at.direction,'backward','The active end point is the one under the finger');
  const reverseHead=at.start;
  assert.equal(await count(),`已选 ${origin-reverseHead} 字`,'Reverse selection counts the characters between both ends');

  // ── 反向解除锁定：必须落在活动终点（手指那一端），不是较大的索引 ─────────────
  await longPress('.selection-mini-track');await page.mouse.up();
  at=await caret();
  assert.equal(at.start,reverseHead,'Unlocking a reverse selection must land on the active end point');
  assert.equal(at.start,at.end,'Unlocking collapses the selection into a caret');
  assert.equal(await page.locator('.selection-editor').count(),1,'The panel keeps standing after unlocking');
  assert.equal(await page.locator('.selection-mini-track.locked').count(),0,'The mini rail drops the locked state');
  assert.equal(await label(),'移动光标 · 长按锁定起点','Unlocked label');
  assert.equal(await count(),'已选 0 字');
  assert.equal(await page.locator('.selection-paragraphs button:disabled').count(),2,'Paragraph jumps are disabled while unlocked');
  assert.equal((await styleOf('.keyboard-footer .cursor-hint')).bg,'rgb(129, 138, 152)','The main rail hint goes back to grey while unlocked');
  assert.equal(await page.locator('.selection-editor [data-action="copy-text"]').isDisabled(),true,'An empty selection disables copy');
  assert.equal(await page.locator('.selection-editor [data-action="cut-text"]').isDisabled(),true,'An empty selection disables cut');
  assert.equal((await caret()).text,text,'Unlocking must not touch the text');

  // ── 解除锁定后自由移动 → 重新定位再锁定 ──────────────────────────────────
  p=await press('.selection-mini-track');
  await page.mouse.move(p.x+22,p.y);await page.mouse.up();
  at=await caret();assert.equal(at.start,at.end,'An unlocked rail drags the caret again');assert.equal(at.start,reverseHead+2,'Two steps to the right');
  const moved=at.start;
  await longPress('.selection-mini-track');await page.mouse.up();
  assert.equal(await page.locator('.selection-mini-track.locked').count(),1,'Long press again locks the new caret as the origin');
  assert.deepEqual(await caret(),{start:moved,end:moved,direction:'forward',text},'Re-locking pins the new position');
  assert.equal(await page.locator('.selection-paragraphs button:disabled').count(),0,'Paragraph jumps come back with the lock');
  p=await press('.selection-mini-track');await page.mouse.move(p.x+22,p.y);await page.mouse.up();
  at=await caret();assert.equal(at.start,moved,'Re-locked origin stays put');assert(at.end>moved,'Re-locked origin extends the new end point');
  const lockedAgain=at;
  // 一次长按只切换一次。
  await page.waitForTimeout(600);assert.equal(await page.locator('.selection-mini-track.locked').count(),1);

  // ── 点击框外关闭面板：那一次触摸被遮罩消费，正文与选区都不动 ───────────────
  const aKey=await box('#keyboard [data-action="letter"][data-value="A"]');
  await page.mouse.click(aKey.x+aKey.width/2,aKey.y+aKey.height/2);
  assert.equal(await page.locator('.selection-editor').count(),0,'Tapping outside closes the panel');
  assert.deepEqual(await caret(),lockedAgain,'Closing the panel must not move the caret or the selection');
  assert.equal((await caret()).text,text,'The tap that closes the panel must not reach the letter key behind it');
  assert.equal(await page.locator('#keyboard .candidate-bar').count(),0,'No composition started behind the mask');

  // ── 面板重新打开：锁定状态与收起前一致 ───────────────────────────────────
  p=await press('.keyboard-footer .cursor-zone');
  assert.equal(await page.locator('.selection-editor').count(),1,'Touching a rail while locked brings the panel back');
  assert.equal(await label(),'起点锁定 · 拖动调整终点');
  assert.equal(await count(),`已选 ${Math.abs(lockedAgain.end-lockedAgain.start)} 字`);
  assert.deepEqual(await caret(),lockedAgain,'Re-opening the panel restores the same selection');
  await page.mouse.up();

  // ── 失焦 / 断触清理 ─────────────────────────────────────────────────────
  await page.locator('#keyboard').evaluate(e=>e.dispatchEvent(new PointerEvent('pointercancel',{pointerId:1,bubbles:true})));
  p=await press('.selection-mini-track');await page.mouse.move(p.x+11,p.y);
  const beforeCancel=await caret();
  await page.locator('#keyboard').evaluate(e=>e.dispatchEvent(new PointerEvent('pointercancel',{pointerId:1,bubbles:true})));
  await page.mouse.move(p.x+33,p.y);await page.waitForTimeout(450);
  assert.deepEqual(await caret(),beforeCancel,'A broken touch must stop the drag and must not start a long press');
  assert.equal(await page.locator('.selection-editor').count(),1,'Losing the touch keeps the panel state');
  await page.mouse.up();
  p=await press('.selection-mini-track');await page.evaluate(()=>window.dispatchEvent(new Event('blur')));
  await page.mouse.move(p.x-22,p.y);await page.mouse.up();
  assert.deepEqual(await caret(),beforeCancel,'Losing focus must stop the drag as well');
  await page.locator('#editor').evaluate(e=>{e.value='';e.setSelectionRange(0,0);});
  await dismiss();
  assert.equal(await page.locator('.selection-editor').count(),0);
  assert.deepEqual(await geometry(),original,'The 26-key geometry never changes');
  assert.deepEqual(errors,[]);
  console.log('PASS: dual-rail long-press toggle, locked end-point drag, reverse active end, free move, re-lock, outside tap consumed, panel re-open, broken touch/blur cleanup, unchanged geometry');
}finally{await browser.close();await new Promise(r=>server.close(r));}
