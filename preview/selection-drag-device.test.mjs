import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {adbShell} from './device-adb.mjs';
const {chromium}=createRequire(import.meta.url)('playwright');
const shell=command=>adbShell(command,{serial:process.env.VOXFIX_SERIAL||'6959dba1'});
async function ownText(){
  assert((await shell('dumpsys window')).includes('local.voxfix.debug/local.voxfix.debug.MainActivity'),'User must keep the own-app input open');
  for(let i=0;i<3;i++){
    await shell('rm -f /data/local/tmp/voxfix-selection-ui.xml');
    const dump=await shell('uiautomator dump /data/local/tmp/voxfix-selection-ui.xml');
    if(dump.includes('ERROR'))continue;
    const xml=await shell('cat /data/local/tmp/voxfix-selection-ui.xml');
    const node=xml.match(/<node\b[^>]*content-desc="VoxFix输入法试用框"[^>]*>/)?.[0];
    assert(node,'Own-app text field is missing');
    const value=node.match(/\btext="([^"]*)"/)?.[1]??'';
    return value==='在这里试用 VoxFix 输入法'?'':value.replace(/&#(\d+);/g,(_,n)=>String.fromCodePoint(Number(n))).replace(/&quot;/g,'"').replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&amp;/g,'&');
  }
  throw Error('Own-app text could not be verified; no test text may be inserted');
}
const browser=await chromium.connectOverCDP('http://127.0.0.1:18746');
let page,inserted=false,touch;
const text='first line abcdefghijkl\nsecond line abcdefghijkl\nthird line abcdefghijkl\nfourth line abcdefghijkl';
try{
  page=browser.contexts().flatMap(c=>c.pages()).find(p=>p.url()==='https://voxfix.local/keyboard/index.html');assert(page);
  assert.equal(await ownText(),'','Only a blank own-app field is safe for this check');
  await page.evaluate(()=>{
    window.selectionProbe={events:[],original:globalThis.voxfixSelection};
    globalThis.voxfixSelection=(start,end,count)=>{selectionProbe.events.push({start,end,count});selectionProbe.original(start,end,count);};
  });
  await page.evaluate(text=>VoxFixIme.commit(text),text);inserted=true;await page.waitForTimeout(500);
  touch=await page.context().newCDPSession(page);
  // 「完成」已移除：面板只由点击框外收起，收起不等于解锁，所以先长按小轨解除锁定再收起。
  async function hold(mini=false){
    if(!mini&&await page.locator('.selection-editor').count()){
      if(await page.locator('.selection-mini-track.locked').count()){const r=await page.locator('.selection-mini-track').boundingBox(),q={x:r.x+r.width/2,y:r.y+r.height/2};await touch.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[q]});await page.waitForTimeout(500);await touch.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});}
      await page.locator('.selection-editor-backdrop').click({position:{x:8,y:8}});
    }
    const r=await page.locator(mini?'.selection-mini-track':'.keyboard-footer .cursor-zone').boundingBox();const p={x:r.x+r.width/2,y:r.y+r.height/2};await touch.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[p]});await page.waitForTimeout(mini?100:500);return p;}
  const move=p=>touch.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[p]});
  const end=()=>touch.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  const selected=()=>page.evaluate(()=>selectionProbe.events.at(-1));
  const range=s=>[Math.min(s.start,s.end),Math.max(s.start,s.end)];
  // 普通滑轨双向跨换行，不能把一段的结尾当成全文终点。
  const paragraphEnd=text.indexOf('\n');
  await page.evaluate(position=>{VoxFixIme.moveCursor(-100);VoxFixIme.moveCursor(position);VoxFixIme.gestureStart(false);},paragraphEnd);
  await page.evaluate(()=>VoxFixIme.gestureMove(1));await page.waitForTimeout(300);
  assert.deepEqual(range(await selected()),[paragraphEnd+1,paragraphEnd+1],'Rightward cursor movement must enter the next paragraph');
  await page.evaluate(()=>VoxFixIme.gestureMove(0));await page.waitForTimeout(300);
  assert.deepEqual(range(await selected()),[paragraphEnd,paragraphEnd],'Reversal must return across the same paragraph boundary');
  await page.evaluate(()=>{VoxFixIme.gestureEnd();VoxFixIme.moveCursor(100);});await page.waitForTimeout(250);
  console.log('PASS: plain native cursor crosses paragraph boundaries in both directions');
  let p=await hold();await move({x:p.x-25,y:p.y});await page.waitForTimeout(250);const first=await selected();assert.equal(first.count,2);assert.deepEqual(range(first),[text.length-2,text.length]);await end();
  assert.equal(await page.locator('.selection-pad').count(),0);assert.equal(await page.locator('.selection-editor').count(),1);
  p=await hold(true);assert.deepEqual(range(await selected()),range(first));
  await move({x:p.x-25,y:p.y});await page.waitForTimeout(250);const expanded=await selected();assert.equal(expanded.count,4);assert.deepEqual(range(expanded),[text.length-4,text.length]);await end();
  console.log('PASS: native count, release and fixed-anchor resume');
  p=await hold(true);await move({x:p.x-25,y:p.y-32});await page.waitForTimeout(350);const diagonal=await selected();
  assert(Math.min(diagonal.start,diagonal.end)<Math.min(expanded.start,expanded.end)-4,'Native 2D must cross a visual line');assert.equal(Math.max(diagonal.start,diagonal.end),text.length);
  assert.equal(await page.locator('.selection-count').innerText(),`已选 ${diagonal.count} 字`);
  await page.screenshot({path:new URL('../.android/selection-native.png',import.meta.url).pathname.replace(/^\/([A-Za-z]:)/,'$1')});
  await move({x:p.x+11,y:p.y-28});await page.waitForTimeout(250);const right=await selected();assert(Math.min(right.start,right.end)>Math.min(diagonal.start,diagonal.end),'Rightward adjustment must move along the visual line');
  await touch.send('Input.dispatchTouchEvent',{type:'touchCancel',touchPoints:[]});await page.waitForTimeout(250);const stopped=await selected();await page.waitForTimeout(600);assert.deepEqual(range(await selected()),range(stopped));assert.equal(await page.locator('.selection-pad').count(),0);
  assert.equal((await ownText()).replace(/\r/g,''),text,'Selection must not edit test content');
  for(const origin of [0,text.indexOf('third line')]){
    await page.evaluate(origin=>{VoxFixIme.moveCursor(-100);VoxFixIme.moveCursor(-100);if(origin)VoxFixIme.moveCursor(origin);},origin);
    await page.waitForTimeout(350);assert.deepEqual(range(await selected()),[origin,origin]);
    p=await hold();const pad=await page.locator('.selection-editor').boundingBox();
    await move({x:p.x,y:pad.y+24});await page.waitForTimeout(70);
    assert.deepEqual(range(await selected()),[origin,origin],'Moving up to reposition must not select above the text origin');
    await move({x:p.x,y:pad.y+60});await page.waitForTimeout(350);
    const downward=range(await selected());assert.equal(downward[0],origin);assert(downward[1]>origin,'Downward reversal must select below the original text point');
    await end();
  }
  console.log('PASS: native start/middle reposition then downward selection');
  console.log('PASS: native diagonal/reverse movement, feedback and cancellation');
}finally{
  try{
  if(page){
    await touch?.send('Input.dispatchTouchEvent',{type:'touchCancel',touchPoints:[]}).catch(()=>{});
    await page.evaluate(()=>window.dispatchEvent(new Event('blur'))).catch(()=>{});
    if(inserted){
      // 用户若在测试期间改了文字，不能用全选清空覆盖他的编辑。
      const current=await ownText().catch(()=>null);
      if(current?.replace(/\r/g,'')===text){await page.evaluate(()=>{VoxFixIme.gestureEnd();VoxFixIme.edit('select-all');VoxFixIme.commit('');});await page.waitForTimeout(300);assert.equal(await ownText(),'');}
    }
    await page.evaluate(()=>{if(window.selectionProbe){globalThis.voxfixSelection=selectionProbe.original;delete window.selectionProbe;}document.querySelector('.selection-editor-backdrop')?.click();}).catch(()=>{});
  }
  }finally{await touch?.detach().catch(()=>{});await browser.close();}
}
