import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {adbShell} from './device-adb.mjs';
const {chromium}=createRequire(import.meta.url)('playwright');
const shell=(...args)=>adbShell(args.join(' '));
async function inputText(){
  // uiautomator 在键盘有合成窗口/动画时经常报 "could not get idle state" 并保留上一个文件，
  // 直接 cat 会读到过期的界面快照（会把上一次的文本当成本次结果）。这里每次先删文件再 dump 并重试。
  let xml='';
  for(let attempt=0;attempt<4;attempt++){
    await shell('rm -f /data/local/tmp/voxfix-ime-ui.xml');
    const dumped=await shell('uiautomator dump /data/local/tmp/voxfix-ime-ui.xml');
    if(!dumped.includes('ERROR')){
      xml=await shell('cat /data/local/tmp/voxfix-ime-ui.xml');
      if(xml.includes('EditText'))break;
    }
    await new Promise(resolve=>setTimeout(resolve,300));
  }
  const node=xml.match(/<node\b[^>]*class="android.widget.EditText"[^>]*package="local.voxfix.debug"[^>]*>/)?.[0];
  assert(node,'Open the VoxFix test input before running this check');
  const text=node.match(/\btext="([^"]*)"/)[1].replace(/&#(\d+);/g,(_,code)=>String.fromCodePoint(Number(code))).replace(/&quot;/g,'"').replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&amp;/g,'&');
  // UIAutomator reports the own test field's placeholder as text when empty.
  return text==='在这里试用 VoxFix 输入法'?'':text;
}
const browser=await chromium.connectOverCDP('http://127.0.0.1:18746');
let testedPage,learningBefore;
try{
  const page=browser.contexts().flatMap(context=>context.pages()).find(page=>page.url()==='https://voxfix.local/keyboard/index.html');
  assert(page,'The VoxFix native WebView must be open');
  testedPage=page;learningBefore=await page.evaluate(()=>localStorage.getItem('voxfix-learning-v1'));
  const base=await inputText();
  assert(base.trim()==='','This check only runs in a blank own-app test field');
  // 刚呼出的键盘可能还在装载原生拼音引擎；不等就绪会拿到空候选而误判成功能坏了。
  for(let attempt=0;attempt<40;attempt++){
    if(await page.evaluate(()=>window.VoxFixIme.pinyinReady()))break;
    await page.waitForTimeout(500);
  }
  // 上一次会话可能把前端的拼音组合留在 WebView 内存里，先复位并清空宿主编框，避免旧组合被写回。
  await page.evaluate(()=>{globalThis.voxfixReset?.();window.VoxFixIme.edit('select-all');});
  await page.waitForTimeout(200);
  await page.evaluate(()=>window.VoxFixIme.commit(''));
  await page.waitForTimeout(200);
  // 中英文会跨键盘会话保留（官方也保留），先确保处于中文键位：英文键位下字母是小写 data-value，
  // 后面按 'N' 会找不到键。中文键位带「分词」键，英文键位是 shift 键，用它来判断。
  for(let attempt=0;attempt<3;attempt++){
    if(await page.evaluate(()=>Boolean(document.querySelector('#keyboard [data-action="split"]'))))break;
    await page.evaluate(()=>document.querySelector('#keyboard [data-action="language"]')?.click());
    await page.waitForTimeout(300);
  }
  assert(await page.evaluate(()=>Boolean(document.querySelector('#keyboard [data-action="split"]'))),'The check needs Chinese letter layout');
  await page.evaluate(()=>window.VoxFixIme.moveCursor(100));await page.waitForTimeout(150);
  const click=(action,value)=>page.evaluate(({action,value})=>{
    const buttons=[...document.querySelectorAll('#keyboard [data-action]')];
    const button=buttons.find(button=>button.dataset.action===action&&(value===undefined||button.dataset.value===value));
    if(!button)throw Error('Missing key '+action);button.click();
  },{action,value});
  for(const letter of 'NIHAO'){await click('letter',letter);await page.waitForTimeout(150);}
  assert.equal(await inputText(),base+'nihao','Composing text must reach the real Android editor');
  await click('candidate','你好');await page.waitForTimeout(250);
  assert.equal(await inputText(),base+'你好','Candidate must replace composing text');
  await click('language');await click('letter','q');await click('space');await page.waitForTimeout(250);
  assert.equal(await inputText(),base+'你好q ');
  await click('delete');await page.waitForTimeout(250);assert.equal(await inputText(),base+'你好q');
  await click('numbers');
  for(const number of ['1','2','3']){await click('insert',number);await page.waitForTimeout(200);}
  assert.equal(await inputText(),base+'你好q123');
  assert(await page.locator('.number-body').isVisible(),'Real editor callbacks must preserve number mode');
  await click('letters');await click('language');
  for(const letter of 'NIHAO'){await click('letter',letter);await page.waitForTimeout(100);}
  await click('insert','，');await page.waitForTimeout(250);
  assert.equal(await inputText(),base+'你好q123你好，','Punctuation must first commit the Chinese candidate');
  // Only clear the exact fixed test text; leave any concurrent user edit untouched.
  if(await inputText()===base+'你好q123你好，'){
    await page.evaluate(()=>window.VoxFixIme.edit('select-all'));await page.waitForTimeout(150);
    await page.evaluate(base=>window.VoxFixIme.commit(base),base);await page.waitForTimeout(150);
    assert.equal(await inputText(),base);
  }
  await click('language');
  for(const letter of 'abcdefgh'){await click('letter',letter);await page.waitForTimeout(60);}
  assert.equal(await inputText(),base+'abcdefgh');
  const key=await page.locator('#keyboard [data-action="delete"]').boundingBox();
  const touch=await page.context().newCDPSession(page);
  await touch.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:key.x+key.width/2,y:key.y+key.height/2}]});
  await page.waitForTimeout(750);
  await touch.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await page.waitForTimeout(200);
  await touch.detach();
  const afterHold=await inputText();
  assert(afterHold.startsWith(base)&&afterHold.length<=base.length+6,'Long delete must continue across real selection callbacks');
  if(afterHold.startsWith(base)&&'abcdefgh'.startsWith(afterHold.slice(base.length))){
    await page.evaluate(()=>window.VoxFixIme.edit('select-all'));await page.waitForTimeout(150);
    await page.evaluate(base=>window.VoxFixIme.commit(base),base);await page.waitForTimeout(150);assert.equal(await inputText(),base);
  }
  await click('language');
  await click('numbers');await click('symbols');await click('symbol','()');await page.waitForTimeout(150);
  await click('insert','7');await page.waitForTimeout(200);
  assert.equal(await inputText(),base+'(7)','Paired symbols must place the real cursor inside');
  assert(await page.locator('.number-body').isVisible(),'Symbols opened from numbers must return to numbers');
  if(await inputText()===base+'(7)'){
    await page.evaluate(()=>window.VoxFixIme.edit('select-all'));await page.waitForTimeout(150);
    await page.evaluate(base=>window.VoxFixIme.commit(base),base);await page.waitForTimeout(150);assert.equal(await inputText(),base);
  }
  await click('letters');
  const letter=await page.locator('#keyboard [data-action="letter"]').first().boundingBox();
  const longTouch=await page.context().newCDPSession(page);
  await longTouch.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:letter.x+letter.width/2,y:letter.y+letter.height/2}]});
  await page.waitForTimeout(550);
  await longTouch.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await page.waitForTimeout(200);
  assert.equal(await inputText(),base+'1','Touch hold must commit its highlighted number on release');
  assert.equal(await page.locator('.key-popup').count(),0);
  await longTouch.detach();
  if(await inputText()===base+'1'){
    await click('delete');await page.waitForTimeout(200);assert.equal(await inputText(),base);
  }
  for(const letter of 'NIHAOQ'){await click('letter',letter);await page.waitForTimeout(100);}
  assert.equal(await inputText(),base+'nihaoq');
  await click('candidate','你好');await page.waitForTimeout(250);
  assert.equal(await inputText(),base+'你好q','Partial candidate must retain the last pinyin segment');
  assert(await page.locator('[data-action="candidate"]').count(),'Real selection echoes must retain remaining candidates');
  const next=await page.locator('[data-action="candidate"]').first().getAttribute('data-value');
  await click('candidate',next);await page.waitForTimeout(200);
  assert.equal(await inputText(),base+'你好'+next);
  if(await inputText()===base+'你好'+next){await page.evaluate(()=>VoxFixIme.edit('select-all'));await page.waitForTimeout(150);await page.evaluate(base=>VoxFixIme.commit(base),base);await page.waitForTimeout(150);}
  for(const letter of 'NI'){await click('letter',letter);await page.waitForTimeout(100);}
  await click('candidate','你');await page.waitForTimeout(200);
  await click('association-candidate','好');await page.waitForTimeout(200);
  assert.equal(await inputText(),base+'你好');
  assert(await page.locator('[data-action="association-candidate"][data-value="吗"]').count());
  if(await inputText()===base+'你好'){await page.evaluate(()=>VoxFixIme.edit('select-all'));await page.waitForTimeout(150);await page.evaluate(base=>VoxFixIme.commit(base),base);await page.waitForTimeout(150);}
  await click('language');for(const letter of 'hel'){await click('letter',letter);await page.waitForTimeout(100);}
  await click('delete');await page.waitForTimeout(250);
  assert.equal(await inputText(),base+'he');
  assert(await page.locator('[data-action="english-candidate"]').count(),'Own deletion echoes must retain valid English predictions');
  if(await inputText()===base+'he'){await page.evaluate(()=>VoxFixIme.edit('select-all'));await page.waitForTimeout(150);await page.evaluate(base=>VoxFixIme.commit(base),base);await page.waitForTimeout(150);}
  await click('language');assert.equal(await inputText(),base);
  await click('letter','C');await page.waitForTimeout(150);
  assert.equal(await inputText(),'c');
  assert(await page.locator('[data-action="candidate"]').evaluateAll(buttons=>buttons.length>0&&buttons.every(button=>[...button.textContent].length===1)),'A single initial must offer characters on the installed IME');
  await click('delete');await page.waitForTimeout(150);
  for(const letter of 'QQQQQQ'){await click('letter',letter);await page.waitForTimeout(50);}
  assert.equal(await inputText(),'qqqqqq','Repeated initials remain composing until selection');
  // 断言只钉行为，不钉候选文字：librime 对 q/i 这类不成词片段只回显拼音，不会像旧 JS 表那样硬造整词。
  const repeated=await page.locator('[data-action="candidate"]').first().getAttribute('data-value');assert(repeated,'Repeated initials must still offer candidates on the installed IME');
  await click('delete');await page.waitForTimeout(150);assert.equal(await inputText(),'qqqqq');
  const shortened=await page.locator('[data-action="candidate"]').first().getAttribute('data-value');assert(shortened,'Shortened composing must keep candidates');
  await click('candidate',shortened);await page.waitForTimeout(150);assert.equal(await inputText(),shortened);
  await page.evaluate(()=>{VoxFixIme.edit('select-all');VoxFixIme.commit('');});await page.waitForTimeout(150);assert.equal(await inputText(),base);
  const assertLeft=async()=>assert(await page.locator('.candidate-visible').evaluate(row=>{const range=document.createRange();range.selectNodeContents(row.firstElementChild);return range.getBoundingClientRect().left-row.getBoundingClientRect().left<20;}),'Candidate text starts at the left on K60');
  for(const letter of 'IIIIIII')await click('letter',letter);
  await page.waitForTimeout(150);assert.equal(await inputText(),'iiiiiii');assert(await page.locator('[data-action="candidate"]').count(),'Long composing must expose candidates');await assertLeft();
  await click('enter');await page.waitForTimeout(150);assert.equal(await inputText(),'iiiiiii');
  await page.evaluate(()=>{VoxFixIme.edit('select-all');VoxFixIme.commit('');});await page.waitForTimeout(150);assert.equal(await inputText(),base);
  for(const letter of 'HELLO')await click('letter',letter);
  await page.waitForTimeout(150);assert.equal(await inputText(),'hello');assert.equal(await page.locator('[data-action="candidate"]').first().textContent(),'hello');
  await click('candidate','hello');await page.waitForTimeout(150);assert.equal(await inputText(),'hello');
  await page.evaluate(()=>{VoxFixIme.edit('select-all');VoxFixIme.commit('');});await page.waitForTimeout(150);assert.equal(await inputText(),base);
  await click('language');for(const letter of 'qqqqqq')await click('letter',letter);
  await page.waitForTimeout(150);assert.equal(await inputText(),'qqqqqq');assert.equal(await page.locator('[data-action="english-candidate"]').first().textContent(),'qqqqqq');await assertLeft();
  await page.evaluate(()=>{VoxFixIme.edit('select-all');VoxFixIme.commit('');});await page.waitForTimeout(150);assert.equal(await inputText(),base);
  await click('language');
  // 底部无极滑轨：不经长按直接拖动就移动宿主输入框光标；长按后拖动则框选真实文字。
  const track=await page.context().newCDPSession(page);
  const zone=await page.locator('.cursor-zone').boundingBox();
  const zx=zone.x+zone.width/2,zy=zone.y+zone.height/2;
  const typeEnglish=async value=>{for(const letter of value){await click('letter',letter);await page.waitForTimeout(60);}};
  await click('language');
  await typeEnglish('abcdefgh');
  assert.equal(await inputText(),base+'abcdefgh');
  await track.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:zx,y:zy}]});
  await track.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:zx-33,y:zy}]});
  await page.waitForTimeout(120);
  await track.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await page.waitForTimeout(150);
  await click('letter','x');await page.waitForTimeout(150);
  assert.equal(await inputText(),base+'abcdexfgh','A plain track drag must move the real editor cursor without a prior hold');
  await page.evaluate(()=>{VoxFixIme.edit('select-all');VoxFixIme.commit('');});await page.waitForTimeout(150);
  await typeEnglish('abcdefgh');
  assert.equal(await inputText(),base+'abcdefgh');
  await track.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:zx,y:zy}]});
  await page.waitForTimeout(520);
  await track.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:zx-22,y:zy}]});
  await page.waitForTimeout(150);
  await track.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await page.waitForTimeout(150);
  await click('delete');await page.waitForTimeout(200);
  assert.equal(await inputText(),base+'abcdef','Holding the track must select real editor text');
  await page.evaluate(()=>{VoxFixIme.edit('select-all');VoxFixIme.commit('');});await page.waitForTimeout(150);assert.equal(await inputText(),base);
  await track.detach();
  await click('language');
  console.log('PASS: K60 composing/candidates, English/delete predictions, numbers, punctuation, long delete, cursor track move/select, partial consumption, association and test-text cleanup');
}finally{
  // 触摸长按框选后系统可能收起输入法并销毁 WebView，这时页面已经没了，收尾不能因此把整轮判成失败。
  try{
    if(testedPage&&learningBefore!==undefined)await testedPage.evaluate(saved=>{if(saved===null)localStorage.removeItem('voxfix-learning-v1');else localStorage.setItem('voxfix-learning-v1',saved);window.dispatchEvent(new StorageEvent('storage',{key:'voxfix-learning-v1',newValue:saved}));},learningBefore);
  }catch(error){console.log('cleanup skipped:',error.message);}
  await browser.close();
}
