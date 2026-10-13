import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFile} from 'node:fs/promises';
import {adbShell} from './device-adb.mjs';
const {chromium}=createRequire(import.meta.url)('playwright');
const shell=command=>adbShell(command,{serial:process.env.VOXFIX_SERIAL||'6959dba1'});
async function ownText(){
  assert((await shell('dumpsys window')).includes('local.voxfix.debug/local.voxfix.debug.MainActivity'),'Only the own-app editor may be tested');
  const dump=await shell('uiautomator dump /data/local/tmp/voxfix-selection-editor-ui.xml');assert(!dump.includes('ERROR'));
  const xml=await shell('cat /data/local/tmp/voxfix-selection-editor-ui.xml');
  const node=xml.match(/<node\b[^>]*content-desc="VoxFix输入法试用框"[^>]*>/)?.[0];assert(node,'Own editor absent');
  const text=node.match(/\btext="([^"]*)"/)?.[1]??'';
  return text==='在这里试用 VoxFix 输入法'?'':text.replace(/&#(\d+);/g,(_,n)=>String.fromCodePoint(Number(n))).replace(/&quot;/g,'"').replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&amp;/g,'&');
}
const browser=await chromium.connectOverCDP('http://127.0.0.1:18746');
const text='abcdefghijkl\nmnopqrstuvwx\nyz0123456789';
let page,inserted=false,touch;
try{
  page=browser.contexts().flatMap(c=>c.pages()).find(p=>p.url()==='https://voxfix.local/keyboard/index.html');assert(page);
  assert.equal(await ownText(),'','Own editor must be blank before inserting test text');
  const backup=JSON.parse(await readFile(new URL('../.android/selection-update-storage-backup.json',import.meta.url),'utf8'));
  const live=await page.evaluate(()=>Object.fromEntries(Object.entries(localStorage)));
  for(const key of ['voxfix-snippets-v1','voxfix-learning-v1','voxfix-hotwords-v1'])if(key in backup)assert(live[key]===backup[key],'User data must survive installation: '+key);
  assert(await page.evaluate(()=>typeof VoxFixIme.gestureLock==='function'&&typeof VoxFixIme.selectionParagraph==='function'),'Installed native bridge must include new methods');
  await page.evaluate(()=>{window.selectionEditorProbe={events:[],original:voxfixSelection};globalThis.voxfixSelection=(start,end,count)=>{selectionEditorProbe.events.push({start,end,count});selectionEditorProbe.original(start,end,count);};});
  await page.evaluate(text=>VoxFixIme.commit(text),text);inserted=true;await page.waitForTimeout(300);
  await page.evaluate(()=>{VoxFixIme.moveCursor(-100);VoxFixIme.moveCursor(4);});await page.waitForTimeout(300);
  const latest=()=>page.evaluate(()=>selectionEditorProbe.events.at(-1));
  const range=async()=>{const s=await latest();assert(s);return [Math.min(s.start,s.end),Math.max(s.start,s.end)];};
  assert.deepEqual(await range(),[4,4]);
  const original=await page.locator('#keyboard').evaluate(e=>({height:e.offsetHeight,keys:[...e.querySelectorAll('.key-row .key')].map(k=>{const r=k.getBoundingClientRect();return [r.x,r.y,r.width,r.height];})}));
  touch=await page.context().newCDPSession(page);
  async function start(selector,hold=0){const r=await page.locator(selector).boundingBox(),p={x:r.x+r.width/2,y:r.y+r.height/2};await touch.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[p]});if(hold)await page.waitForTimeout(hold);return p;}
  const move=p=>touch.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[p]});
  const end=async()=>{await touch.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await page.waitForTimeout(250);};
  await start('.keyboard-footer .cursor-zone',500);await end();assert.deepEqual(await range(),[4,4]);assert.equal(await page.locator('.selection-editor').count(),1);
  await page.locator('[data-action="selection-paragraph"][data-value="1"]').click();await page.waitForTimeout(250);assert.deepEqual(await range(),[4,12]);
  await page.locator('[data-action="selection-paragraph"][data-value="1"]').click();await page.waitForTimeout(250);assert.deepEqual(await range(),[4,25]);
  let p=await start('.selection-mini-track');await move({x:p.x-25,y:p.y});await end();assert.deepEqual(await range(),[4,23],'Mini refinement must retain the locked origin');
  await start('.selection-mini-track',500);await end();assert.deepEqual(await range(),[23,23],'Mini long hold must relock the previous head');
  p=await start('.selection-mini-track');await move({x:p.x-15,y:p.y});await end();assert.deepEqual(await range(),[22,23]);
  await start('.selection-mini-track',500);await end();assert.deepEqual(await range(),[22,22],'Backward selection relocks its head rather than its maximum endpoint');
  await page.locator('.selection-editor [data-action="select-all"]').click();await page.waitForTimeout(250);assert.deepEqual(await range(),[0,text.length]);
  await page.locator('[data-action="selection-paragraph"][data-value="-1"]').click();await page.waitForTimeout(250);assert.deepEqual(await range(),[22,26],'Whole-text selection must not replace the locked origin');
  assert.equal((await ownText()).replace(/\r/g,''),text,'Selection operations must not edit test text');
  const current=await page.locator('#keyboard').evaluate(e=>({height:e.offsetHeight,keys:[...e.querySelectorAll('.key-row .key')].map(k=>{const r=k.getBoundingClientRect();return [r.x,r.y,r.width,r.height];})}));assert.deepEqual(current,original);
  assert.equal(await page.locator('.selection-toolbar').count(),0);await page.screenshot({path:new URL('../.android/selection-editor-native.png',import.meta.url).pathname.replace(/^\/([A-Za-z]:)/,'$1')});
  console.log('PASS: installed bridge, preserved user data, main/mini origin locks, paragraph jumps, immediate fine adjustment, backward relock, select-all origin retention, unchanged 26-key geometry');
}finally{
  try{
  await touch?.send('Input.dispatchTouchEvent',{type:'touchCancel',touchPoints:[]}).catch(()=>{});
  if(page)await page.evaluate(()=>window.dispatchEvent(new Event('blur'))).catch(()=>{});
  if(page){if(inserted&&(await ownText().catch(()=>null))?.replace(/\r/g,'')===text){await page.evaluate(()=>{VoxFixIme.gestureEnd();VoxFixIme.edit('select-all');VoxFixIme.commit('');});await page.waitForTimeout(300);assert.equal(await ownText(),'');}
    await page.evaluate(()=>{if(window.selectionEditorProbe){globalThis.voxfixSelection=selectionEditorProbe.original;delete window.selectionEditorProbe;}document.querySelector('.selection-editor-backdrop')?.click();}).catch(()=>{});
  }}finally{await touch?.detach().catch(()=>{});await browser.close();}
}
