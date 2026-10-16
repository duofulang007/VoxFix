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
  const text='abcdefghijkl\nmnopqrstuvwx\nyz0123456789';
  await page.locator('#editor').evaluate((e,text)=>{e.value=text;e.setSelectionRange(4,4);e.dispatchEvent(new Event('input'));},text);
  const points=()=>page.locator('#editor').evaluate(e=>({start:e.selectionStart,end:e.selectionEnd,direction:e.selectionDirection}));
  const geometry=()=>page.locator('#keyboard').evaluate(e=>({height:e.offsetHeight,keys:[...e.querySelectorAll('.key-row .key')].map(k=>{const r=k.getBoundingClientRect();return [r.x,r.y,r.width,r.height];})}));
  const original=await geometry();
  async function hold(selector){const r=await page.locator(selector).boundingBox();const p={x:r.x+r.width/2,y:r.y+r.height/2};await page.mouse.move(p.x,p.y);await page.mouse.down();await page.waitForTimeout(390);return p;}
  const dismiss=()=>page.locator('.selection-editor-backdrop').click({position:{x:8,y:8}});
  const paragraph=value=>page.locator(`[data-direction="${Number(value)>0?'1,1':'-1,-1'}"]`);
  let p=await hold('.keyboard-footer .cursor-zone');await page.mouse.up();
  assert.equal(await page.locator('.selection-editor').count(),1);
  assert.equal(await page.locator('.selection-toolbar').count(),0);
  assert.equal(await page.locator('.selection-editor [data-action="selection-close"]').count(),0,'The header no longer carries a 完成 button');
  assert.deepEqual(await points(),{start:4,end:4,direction:'forward'});
  assert.deepEqual(await geometry(),original);
  await page.locator('[data-direction="0,1"]').click();assert.equal((await points()).start,4);assert((await points()).end>4);
  await paragraph('1').click();assert((await points()).end>17);
  const beforeMini=await points();const mini=await page.locator('.selection-mini-track').boundingBox();await page.mouse.move(mini.x+mini.width/2,mini.y+mini.height/2);await page.mouse.down();await page.mouse.move(mini.x+mini.width/2-22,mini.y+mini.height/2);await page.mouse.up();
  assert.equal((await points()).start,4,'Short mini drag preserves the origin');assert.equal((await points()).end,beforeMini.end-2);
  // 锁定态再长按 = 解除锁定：选区收成活动终点处的光标，面板留着（未锁定态要能看到并置灰选段）。
  const beforeUnlock=await points();p=await hold('.selection-mini-track');await page.mouse.up();const unlocked=await points();
  assert.deepEqual(unlocked,{start:beforeUnlock.end,end:beforeUnlock.end,direction:'forward'},'Mini long press unlocks onto the active end point');
  assert.equal(await page.locator('.selection-mini-track.locked').count(),0);
  assert.equal(await page.locator('.selection-mini-track').getAttribute('aria-label'),'移动光标 · 长按锁定起点');
  assert.equal(await page.locator('.selection-direction:disabled').count(),0,'Direction buttons remain usable for an unlocked caret');
  // 再长按一次重新锁定，段落跳转与全选照旧。
  p=await hold('.selection-mini-track');await page.mouse.up();
  assert.equal(await page.locator('.selection-mini-track.locked').count(),1);
  assert.equal(await page.locator('.selection-mini-track').getAttribute('aria-label'),'起点锁定 · 拖动调整终点');
  await paragraph('1').click();assert.equal((await points()).start,unlocked.start);
  await page.locator('.selection-editor [data-action="select-all"]').click();assert.equal((await points()).end,text.length);
  await paragraph('-1').click();const afterAll=await points();assert(afterAll.start===unlocked.start||afterAll.end===unlocked.start,'Select-all does not replace the locked origin');
  await page.locator('.selection-editor [data-action="copy-text"]').click();
  assert.equal(await page.locator('.selection-editor').count(),1,'Copy keeps the editing panel');
  assert.equal(await page.locator('.selection-editor-header button').count(),1,'Header carries the count plus the new delete button');
  assert.equal(await page.locator('.selection-editor-header button').getAttribute('aria-label'),'删除','Header delete keeps its Chinese accessible name');
  await dismiss();assert.deepEqual(await geometry(),original,'Closing restores unchanged key geometry');
  await page.locator('#editor').evaluate(e=>{e.setSelectionRange(3,3);e.dispatchEvent(new PointerEvent('pointerdown',{pointerId:17,button:0,bubbles:true,clientX:10,clientY:10}));});await page.waitForTimeout(390);
  await page.locator('#editor').evaluate(e=>e.dispatchEvent(new PointerEvent('pointerup',{pointerId:17,bubbles:true})));assert.equal(await page.locator('.selection-editor').count(),1,'Editor long press opens the same panel');assert.equal((await points()).start,3);
  await dismiss();
  const native=await browser.newPage({viewport:{width:393,height:351}});native.on('pageerror',e=>errors.push(e.message));
  await native.addInitScript(()=>{window.calls=[];window.VoxFixIme=Object.fromEntries(['compose','commit','completeEnglish','copy','delete','enter','hide','switcher','startVoice','stopVoice','cancelVoice','moveCursor','cursorPosition','gestureStart','gestureLock','gestureUnlock','gestureMove','gestureMove2D','gestureEnd','haptic','selectionStep','edit','ready','resize','refreshClipboard','importClipboard'].map(n=>[n,(...a)=>calls.push([n,...a])]));VoxFixIme.voiceReady=()=>true;});
  await native.goto(`http://127.0.0.1:${server.address().port}/`);await native.waitForFunction(()=>typeof voxfixSelectionOrigin==='function');
  await native.evaluate(()=>{voxfixSelectionOrigin();voxfixSelection(5,15,10);});
  for(const width of [320,393]){await native.setViewportSize({width,height:351});const kb=await native.locator('#keyboard').boundingBox(),panel=await native.locator('.selection-editor').boundingBox();assert(panel.x>=kb.x&&panel.x+panel.width<=kb.x+kb.width&&panel.y>=kb.y&&panel.y+panel.height<kb.y+kb.height-20);const targets=await native.locator('.selection-editor-body button').evaluateAll(es=>es.map(e=>{const r=e.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height};}));for(const t of targets)assert(t.width>=48&&t.height>=48);for(let i=0;i<targets.length;i++)for(let j=i+1;j<targets.length;j++){const a=targets[i],b=targets[j];assert(!(a.x<b.x+b.width&&a.x+a.width>b.x&&a.y<b.y+b.height&&a.y+a.height>b.y));}
    // 新增的删除钮只借页头右侧的空白：页头高度不变、不越出面板、不与下方按钮重叠。
    const header=await native.locator('.selection-editor-header').boundingBox(),remove=await native.locator('.selection-delete').boundingBox();
    assert.equal(Math.round(header.height),32,'Title bar keeps its height');
    assert(remove.y>=header.y&&remove.y+remove.height<=header.y+header.height,'Delete button stays inside the title bar');
    assert(remove.x+remove.width<=panel.x+panel.width-8+1,'Delete button keeps the panel padding');
    for(const t of targets)assert(!(remove.x<t.x+t.width&&remove.x+remove.width>t.x&&remove.y<t.y+t.height&&remove.y+remove.height>t.y),'Delete button never overlaps the body buttons');}
  await native.locator('[data-direction="1,1"]').click();assert((await native.evaluate(()=>calls)).some(c=>c[0]==='selectionStep'&&c[1]===1&&c[2]===1));
  await native.evaluate(()=>voxfixSelection(5,5,0));assert.equal(await native.locator('.selection-editor').count(),1,'Collapsed origin leaves panel open');
  const nr=await native.locator('.selection-mini-track').boundingBox();
  await native.mouse.move(nr.x+nr.width/2,nr.y+nr.height/2);await native.mouse.down();await native.waitForTimeout(400);await native.mouse.up();
  assert((await native.evaluate(()=>calls)).some(c=>c[0]==='gestureUnlock'),'Mini long hold calls the shared native unlock');
  assert.equal(await native.locator('.selection-mini-track.locked').count(),0,'The native panel stays open in the unlocked state');
  await native.screenshot({path:new URL('../.android/selection-editor-unlocked.png',import.meta.url).pathname.slice(1)});
  await native.mouse.move(nr.x+nr.width/2,nr.y+nr.height/2);await native.mouse.down();await native.waitForTimeout(400);await native.mouse.up();
  assert((await native.evaluate(()=>calls)).some(c=>c[0]==='gestureLock'),'Long holding again locks the new origin');
  await native.screenshot({path:new URL('../.android/selection-editor.png',import.meta.url).pathname.slice(1)});
  await native.evaluate(()=>voxfixReset());assert.equal(await native.locator('.selection-editor').count(),0);assert.deepEqual(errors,[]);
  console.log('PASS: unchanged 26-key geometry, both long-press toggles, visual-line direction clicks, mini refinement, select-all origin retention, clipboard panel, text entry, native hooks, 48px targets, 320px layout/reset');
}finally{await browser.close();await new Promise(r=>server.close(r));}
