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
async function check(name,run){
  const page=await browser.newPage({viewport:{width:393,height:531},hasTouch:true}),errors=[];
  page.setDefaultTimeout(3000);page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(()=>{
    window.calls=[];
    window.VoxFixIme=Object.fromEntries(['compose','commit','completeEnglish','copy','delete','enter','hide','switcher','startVoice','startLocalVoice','stopVoice','cancelVoice','moveCursor','cursorPosition','gestureStart','gestureMove','gestureEnd','haptic','edit','ready','resize','refreshClipboard','importClipboard','clipHint'].map(name=>[name,(...args)=>calls.push([name,...args])]));
    VoxFixIme.voiceReady=()=>true;
    VoxFixIme.pinyinReady=()=>true;VoxFixIme.pinyinCandidates=()=>JSON.stringify({words:['你好','你','好']});
    VoxFixIme.pinyinChoose=()=>JSON.stringify({committed:true,remaining:''});VoxFixIme.commitAccepted=()=>JSON.stringify({committed:true});
    const original=Storage.prototype.setItem;
    Storage.prototype.setItem=function(key,value){if(window.failSave&&key==='voxfix-snippets-v1')throw new DOMException('full','QuotaExceededError');return original.call(this,key,value);};
    if(!localStorage.getItem('voxfix-snippets-v1'))localStorage.setItem('voxfix-layout-v1',JSON.stringify({clipboardCandidate:true}));
    localStorage.setItem('voxfix-snippets-v1',JSON.stringify({clips:['A','B','C'],phrases:[{name:'常用语',items:['默认']},{name:'甲',items:['甲内容']},{name:'乙',items:['乙内容']}]}));
  });
  const key=(action,value)=>page.locator(`#keyboard [data-action="${action}"]${value===undefined?'':`[data-value="${value}"]`}`).first();
  const click=(action,value)=>key(action,value).click();
  const context=text=>page.locator('.clip-text').filter({hasText:text}).first().dispatchEvent('contextmenu');
  const storage=()=>page.evaluate(()=>JSON.parse(localStorage.getItem('voxfix-snippets-v1')));
  const ready=()=>page.waitForFunction(()=>typeof voxfixReset==='function'&&document.querySelector('#pinyin-status').textContent.includes('已就绪'));
  try{await page.goto(`http://127.0.0.1:${server.address().port}/`);await ready();await run({page,key,click,context,storage});assert.deepEqual(errors,[]);console.log('PASS '+name);}
  catch(error){failures.push(name+': '+error.message);console.log('FAIL '+name+': '+error.message.slice(0,220));}
  finally{await page.close();}
}
try{
  await check('two overlapping touch keys commit in press order without duplicates',async({page,click,key})=>{
    await click('language');const a=await key('letter','a').boundingBox(),b=await key('letter','b').boundingBox();
    const first={x:a.x+a.width/2,y:a.y+a.height/2,id:1},second={x:b.x+b.width/2,y:b.y+b.height/2,id:2},touch=await page.context().newCDPSession(page);
    try{for(const reverse of [false,true]){
      await page.evaluate(()=>{voxfixReset();calls=[];});
      await touch.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[first]});
      await touch.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[first,second]});
      await touch.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:reverse?[first]:[second]});
      await touch.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await page.waitForTimeout(100);
      assert.deepEqual(await page.evaluate(()=>calls.filter(c=>c[0]==='commit').map(c=>c[1])),['a','b']);
    }}finally{await touch.detach();}
  });
  await check('overlapping Chinese keys keep both letters in the same composition',async({page,key})=>{
    const n=await key('letter','N').boundingBox(),i=await key('letter','I').boundingBox(),touch=await page.context().newCDPSession(page);
    const first={x:n.x+n.width/2,y:n.y+n.height/2,id:1},second={x:i.x+i.width/2,y:i.y+i.height/2,id:2};
    try{
      await touch.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[first]});await touch.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[first,second]});
      await touch.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[second]});await touch.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
      assert.equal(await page.evaluate(()=>calls.filter(c=>c[0]==='compose').at(-1)?.[1]),'ni');
      assert.equal(await page.evaluate(()=>calls.filter(c=>c[0]==='commit').length),0);
    }finally{await touch.detach();}
  });
  await check('shared editor reuses native candidates and partial selection without touching host',async({page,click})=>{
    await page.evaluate(()=>{
      VoxFixIme.pinyinCandidates=raw=>JSON.stringify({words:raw==='beifenq'?['备份']:raw==='q'?['去']:['备份'],preedit:'bei fen q'});
      VoxFixIme.pinyinChooseLocal=(raw,word)=>{calls.push(['localChoose',raw,word]);return JSON.stringify({committed:true,remaining:raw==='beifenq'?'q':''});};
    });
    await click('clipboard');await click('clip-tab','phrases');await click('snippet-add');
    for(const ch of 'BEIFENQ')await click('letter',ch);
    await click('candidate','备份');assert.equal(await page.locator('#snippet-content').inputValue(),'备份q');
    await click('candidate','去');assert.equal(await page.locator('#snippet-content').inputValue(),'备份去');
    assert.equal(await page.evaluate(()=>calls.filter(c=>['commit','commitAccepted','pinyinChoose'].includes(c[0])).length),0);
    await page.locator('#snippet-cancel').click();
  });
  await check('restart updates clipboard eligibility in both directions without another keystroke',async({page,click})=>{
    await page.evaluate(()=>voxfixClipboard('明天下午开会'));for(const ch of 'MTXW')await click('letter',ch);
    await page.waitForTimeout(350);assert(await page.locator('.clipboard-reference').isEnabled());
    await page.evaluate(()=>voxfixStartInput(1,true,0x1000000));assert(await page.locator('.clipboard-reference').isDisabled());
    await page.evaluate(()=>voxfixStartInput(1,true,0));await page.waitForTimeout(350);assert(await page.locator('.clipboard-reference').isEnabled());
  });
  await check('failed favorite must not report success or retain phantom item',async({page,click,context,storage})=>{
    await click('clipboard');await context('A');await page.evaluate(()=>window.failSave=true);await click('context-favorite');
    const notice=await page.locator('#toast').textContent();await click('clip-tab','phrases');
    const visible=await page.locator('.clip-text').allTextContents(),saved=await storage();
    console.log('FAVORITE_EVIDENCE '+JSON.stringify({notice,visible,persisted:saved.phrases[0].items}));
    assert(!notice.includes('已添加'),'Storage failure is overwritten by a success toast');assert(!visible.includes('A'),'Failed favorite remains visible only in memory');
  });
  await check('toolbar hold interrupted before threshold must not start dragging',async({page,key})=>{
    await key('clipboard').dispatchEvent('pointerdown',{pointerId:411,pointerType:'touch',button:0,clientX:100,clientY:220});
    await page.waitForTimeout(100);await page.evaluate(()=>window.dispatchEvent(new Event('blur')));await page.waitForTimeout(400);
    const count=await page.locator('.toolbar-drag').count();console.log('TOOLBAR_BLUR_EVIDENCE '+count);assert.equal(count,0,'A pending hold starts dragging after focus was lost');
  });
  await check('emoji repeat must stop when page becomes hidden',async({page,click})=>{
    await click('emoji');const emoji=page.locator('.emoji-hold').first();
    await emoji.dispatchEvent('pointerdown',{pointerId:412,pointerType:'touch',button:0,clientX:100,clientY:300});await page.waitForTimeout(380);
    await page.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,get:()=>true});document.dispatchEvent(new Event('visibilitychange'));});
    const before=await page.evaluate(()=>calls.filter(c=>c[0]==='commit').length);await page.waitForTimeout(700);const after=await page.evaluate(()=>calls.filter(c=>c[0]==='commit').length);
    console.log('EMOJI_HIDE_EVIDENCE '+JSON.stringify({before,after}));assert.equal(after,before,'Hidden page keeps emitting emoji commits');
  });
  await check('batch delete failure retains checked rows for retry',async({page,click,storage})=>{
    await click('clipboard');await click('clip-manage-menu');await click('clip-manage');await click('clip-pick','A');await click('clip-manage-delete');await page.evaluate(()=>window.failSave=true);await click('clip-confirm-ok');
    const saved=await storage(),checked=await page.locator('.clip-pick.picked').count();console.log('BATCH_FAILURE_EVIDENCE '+JSON.stringify({saved:saved.clips,checked}));assert.equal(checked,1,'Failed deletion loses the selection for retry');
  });
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
assert.deepEqual(failures,[]);

