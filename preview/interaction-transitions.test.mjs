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
    VoxFixIme.pinyinClearLearning=()=>{calls.push(['clearLearning']);return JSON.stringify({cleared:true});};
    const original=Storage.prototype.setItem;
    Storage.prototype.setItem=function(key,value){if(window.failSave&&key==='voxfix-snippets-v1')throw new DOMException('full','QuotaExceededError');return original.call(this,key,value);};
    if(!localStorage.getItem('voxfix-snippets-v1'))localStorage.setItem('voxfix-snippets-v1',JSON.stringify({clips:['A','B','C'],phrases:[{name:'常用语',items:['默认']},{name:'甲',items:['甲内容']},{name:'乙',items:['乙内容']}]}));
  });
  const key=(action,value)=>page.locator(`#keyboard [data-action="${action}"]${value===undefined?'':`[data-value="${value}"]`}`).first();
  const click=(action,value)=>key(action,value).click();
  const context=text=>page.locator('.clip-text').filter({hasText:text}).first().dispatchEvent('contextmenu');
  const storage=()=>page.evaluate(()=>JSON.parse(localStorage.getItem('voxfix-snippets-v1')));
  const ready=()=>page.waitForFunction(()=>typeof voxfixReset==='function'&&document.querySelector('#pinyin-status').textContent.includes('已就绪'));
  async function hold(locator,ms=550){
    const box=await locator.boundingBox(),touch=await page.context().newCDPSession(page);
    await touch.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:box.x+box.width/2,y:box.y+box.height/2}]});
    await page.waitForTimeout(ms);await touch.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await page.waitForTimeout(70);await touch.detach();
  }
  async function addGroup(name){await click('clip-group-add');await page.locator('#ime-local-editor input').fill(name);await page.locator('#ime-local-editor button').filter({hasText:'新建'}).click();}
  async function deleteGroup(value){await hold(key('clip-group',value));await click('group-delete');await click('clip-confirm-ok');}
  async function remove(text){await context(text);await click('context-delete');await click('clip-confirm-ok');}
  try{await page.goto(`http://127.0.0.1:${server.address().port}/`);await ready();await run({page,key,click,context,storage,ready,hold,addGroup,deleteGroup,remove});assert.deepEqual(errors,[]);console.log('PASS '+name);}
  catch(error){failures.push(name+': '+error.message);console.log('FAIL '+name+': '+error.message.slice(0,220));}
  finally{await page.close();}
}
try{
  await check('clipboard preview matches official layout and keeps full paste payload',async({page,click,storage})=>{
    const baseHeight=await page.locator('#keyboard').evaluate(node=>node.getBoundingClientRect().height);
    await page.evaluate(()=>{const input=document.querySelector('[data-setting="clipboardCandidate"]');input.checked=true;input.dispatchEvent(new Event('change',{bubbles:true}));voxfixClipboard('好久','preview-1');});
    const geometry=await page.evaluate(()=>{const preview=document.querySelector('.clip-preview'),source=preview.querySelector('.clip-preview-source'),text=preview.querySelector('.clip-preview-text'),close=preview.querySelector('.clip-preview-close'),keyboard=document.querySelector('#keyboard');return {height:keyboard.getBoundingClientRect().height,keyboardTop:keyboard.getBoundingClientRect().top,sourceHeight:source.getBoundingClientRect().height,sourceBottom:source.getBoundingClientRect().bottom,sourceLeft:source.getBoundingClientRect().left,sourceWidth:source.getBoundingClientRect().width,barTop:text.getBoundingClientRect().top,font:getComputedStyle(text).fontSize,color:getComputedStyle(text).color,closeWidth:close.getBoundingClientRect().width,hint:calls.filter(row=>row[0]==='clipHint').at(-1)?.[1]};});
    // 标签悬在键盘上沿之外，**不占键盘高度**：有没有剪贴板预览，键盘高度必须一模一样。
    assert.equal(geometry.height,baseHeight);assert.equal(geometry.height,351);
    assert.equal(geometry.sourceHeight,29);assert.equal(geometry.sourceBottom,geometry.keyboardTop);assert.equal(geometry.sourceLeft,0);
    assert(Math.abs(geometry.sourceWidth-126.3)<1.5,`label width ${geometry.sourceWidth} != 126.3`);
    assert.equal(geometry.sourceBottom+1,geometry.barTop);assert.equal(geometry.font,'22px');assert.equal(geometry.color,'rgb(52, 130, 255)');assert.equal(geometry.closeWidth,55);
    assert.equal(geometry.hint,'来自剪贴板');
    // 存整机截图（宿主输入区 + 键盘）而不是视口截图：标签在键盘上沿之外，视口截图会把它裁掉，
    // 而这张图要证明的恰恰是「标签浮在键盘外、宿主输入框一点没被顶上去」。
    await page.locator('#phone').screenshot({path:new URL('../.android/clipboard-preview-browser.png',import.meta.url).pathname.replace(/^\/([A-Z]:)/,'$1')});
    await click('clip-candidate-dismiss');assert.equal(await page.locator('.clip-preview').count(),0);assert((await storage()).clips.includes('好久'));
    assert.equal(await page.locator('#keyboard').evaluate(node=>node.getBoundingClientRect().height),baseHeight);
    assert.equal((await page.evaluate(()=>calls)).filter(row=>row[0]==='clipHint').at(-1)[1],'');
    await page.evaluate(()=>voxfixClipboard('好久','preview-1'));assert.equal(await page.locator('.clip-preview').count(),0);
    // 验证码内容换成防转发提示；悬空标签随之变宽，但仍不占键盘高度。
    await page.evaluate(()=>voxfixClipboard('483920','preview-code'));
    assert.equal(await page.locator('.clip-preview-source').textContent(),'请勿转发验证码，避免财产损失');
    assert.equal(await page.locator('#keyboard').evaluate(node=>node.getBoundingClientRect().height),baseHeight);
    await page.evaluate(()=>voxfixClipboard('好久','preview-1'));
    const long='长文本<&>\n'.repeat(30);await page.evaluate(text=>voxfixClipboard(text,'preview-2'),long);
    assert.equal(await page.locator('.clip-preview-text').textContent(),long);await click('clip-candidate-insert');
    assert((await page.evaluate(()=>calls)).some(row=>row[0]==='commit'&&row[1]===long));assert.equal(await page.locator('.clip-preview').count(),0);
    assert.equal(await page.locator('#keyboard').evaluate(node=>node.getBoundingClientRect().height),351);
  });
  await check('delete confirmation keeps content identity after a clipboard update',async({page,click,context,storage})=>{
    await click('clipboard');await context('A');await click('context-delete');await page.evaluate(()=>voxfixClipboard('X','new-copy'));
    await click('clip-confirm-ok');assert.deepEqual((await storage()).clips,['X','B','C']);
  });
  await check('undo deletion preserves newer copies and original item order',async({page,click,remove,storage})=>{
    await click('clipboard');await remove('A');await page.evaluate(()=>voxfixClipboard('X','new-copy'));await click('clip-manage-menu');await click('undo-snippet');
    assert.deepEqual((await storage()).clips,['X','A','B','C']);
  });
  await check('undo group deletion preserves new groups and their contents',async({page,click,storage,addGroup,deleteGroup})=>{
    await click('clipboard');await click('clip-tab','phrases');await deleteGroup('2');await addGroup('丙');
    await click('snippet-add');await page.locator('#snippet-content').fill('丙内容');await page.locator('#snippet-form button[type="submit"]').click();
    await click('clip-manage-menu');await click('group-undo');
    assert.deepEqual((await storage()).phrases.map(group=>group.name),['常用语','甲','乙','丙']);assert.deepEqual((await storage()).phrases.at(-1).items,['丙内容']);
  });
  await check('failed group creation keeps input and does not report success',async({page,click,storage})=>{
    await click('clipboard');await click('clip-tab','phrases');await click('clip-group-add');await page.locator('#ime-local-editor input').fill('丙');
    await page.evaluate(()=>window.failSave=true);await page.locator('#ime-local-editor button').filter({hasText:'新建'}).click();
    assert(await page.locator('#ime-local-editor').isVisible());assert.equal(await page.locator('#ime-local-editor input').inputValue(),'丙');
    assert.deepEqual((await storage()).phrases.map(group=>group.name),['常用语','甲','乙']);assert.doesNotMatch(await page.locator('#toast').textContent(),/已新建/);
    await page.evaluate(()=>window.failSave=false);await page.locator('#ime-local-editor button').filter({hasText:'新建'}).click();assert.equal((await storage()).phrases.at(-1).name,'丙');
  });
  await check('custom toolbar survives reload with its original text',async({page,click,context,ready})=>{
    await click('clipboard');await context('A');await click('toolbar-add');await click('return');assert.equal(await page.locator('[data-action="toolbar-custom"]').count(),1);
    await page.reload();await ready();assert.equal(await page.locator('[data-action="toolbar-custom"]').count(),1);await click('toolbar-custom');
    assert(await page.evaluate(()=>calls.some(call=>call[0]==='commit'&&call[1]==='A')));
  });
  await check('night toggles preserve the language before a temporary email layout',async({page,click})=>{
    await page.evaluate(()=>voxfixStartInput(1,false));await page.evaluate(()=>voxfixStartInput(33,false));assert.equal(await page.locator('[data-action="letter"]').first().getAttribute('data-value'),'q');
    await click('night');await page.evaluate(()=>voxfixStartInput(1,false));assert.equal(await page.locator('[data-action="letter"]').first().getAttribute('data-value'),'Q');
    assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('voxfix-keyboard-v1')).language),'zh');
  });
  await check('clipboard hold menu survives touch release without pasting',async({page,click,hold})=>{
    await click('clipboard');await hold(page.locator('.clip-text').first());assert.equal(await page.locator('.clip-menu-sheet').count(),1);
    assert.equal(await page.evaluate(()=>calls.filter(call=>call[0]==='commit').length),0);await click('context-edit');assert.equal(await page.locator('#snippet-content').inputValue(),'A');
  });
  await check('lost focus closes the long-letter popup without committing it',async({page})=>{
    const box=await page.locator('[data-action="letter"][data-value="N"]').boundingBox();await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();await page.waitForTimeout(450);
    assert.equal(await page.locator('.key-popup').count(),1);await page.evaluate(()=>window.dispatchEvent(new Event('blur')));await page.mouse.up();await page.waitForTimeout(50);
    assert.equal(await page.locator('.key-popup').count(),0);assert.equal(await page.locator('.key.pressed').count(),0);assert.equal(await page.evaluate(()=>calls.filter(call=>call[0]==='commit').length),0);
  });
  await check('clear-learning confirmation is visible and needs a second click',async({page,click})=>{
    await click('menu');await click('custom');await page.locator('#clear-learning').click();
    assert(await page.locator('#toast').isVisible());assert.match(await page.locator('#toast').textContent(),/再次点击/);assert.equal(await page.evaluate(()=>calls.filter(call=>call[0]==='clearLearning').length),0);
    await page.locator('#clear-learning').click();assert.equal(await page.evaluate(()=>calls.filter(call=>call[0]==='clearLearning').length),1);assert.equal(await page.locator('#toast').textContent(),'已清空选词记忆。');
  });
  await check('history disabled still allows copied-content hints and explicit import',async({page,click,storage})=>{
    await click('menu');await click('custom');await page.locator('[data-setting="clipboardHistory"]').uncheck();await page.locator('[data-setting="clipboardCandidate"]').check();
    await page.locator('dialog[open] > button').first().click();await click('return');await page.evaluate(()=>voxfixClipboard('X','new-copy'));
    assert.equal(await page.locator('.clip-candidate').count(),1);assert(!(await storage()).clips.includes('X'));
    await click('clipboard');await click('clip-manage-menu');await click('system-paste');await page.evaluate(()=>voxfixClipboard('X','new-copy',true));
    assert((await storage()).clips.includes('X'));
  });
  await check('failed rename, deletion and group undo preserve persisted content',async({page,key,click,storage,hold,deleteGroup})=>{
    await click('clipboard');await click('clip-tab','phrases');await hold(key('clip-group','1'));await click('group-rename');
    await page.locator('#ime-local-editor input').fill('新甲');await page.evaluate(()=>window.failSave=true);await page.locator('#ime-local-editor button').filter({hasText:'保存'}).click();
    assert(await page.locator('#ime-local-editor').isVisible());assert.equal((await storage()).phrases[1].name,'甲');
    await page.evaluate(()=>window.failSave=false);await page.locator('#ime-local-editor button').filter({hasText:'保存'}).click();assert.equal((await storage()).phrases[1].name,'新甲');
    await page.evaluate(()=>window.failSave=true);await deleteGroup('1');assert.equal((await storage()).phrases.length,3);assert.equal(await page.locator('.group-pill:not(.group-add)').count(),3);
    await page.evaluate(()=>window.failSave=false);await deleteGroup('1');assert.equal((await storage()).phrases.length,2);
    await page.evaluate(()=>window.failSave=true);await click('clip-manage-menu');await click('group-undo');assert.equal((await storage()).phrases.length,2);assert.equal(await page.locator('.group-pill:not(.group-add)').count(),2);
    await page.evaluate(()=>window.failSave=false);await click('clip-manage-menu');await click('group-undo');assert.equal((await storage()).phrases[1].name,'新甲');
  });
  await check('failed clipboard deletion and undo keep their retry point',async({page,click,remove,storage})=>{
    await click('clipboard');await page.evaluate(()=>window.failSave=true);await remove('A');assert.deepEqual((await storage()).clips,['A','B','C']);assert.equal(await page.locator('.clip-text').count(),3);
    await page.evaluate(()=>window.failSave=false);await remove('A');await page.evaluate(()=>window.failSave=true);await click('clip-manage-menu');await click('undo-snippet');assert.deepEqual((await storage()).clips,['B','C']);
    await page.evaluate(()=>window.failSave=false);await click('clip-manage-menu');await click('undo-snippet');assert.deepEqual((await storage()).clips,['A','B','C']);
  });
  await check('group undo does not replace a newer group with the same name',async({click,storage,deleteGroup,addGroup,page})=>{
    await click('clipboard');await click('clip-tab','phrases');await deleteGroup('2');await addGroup('乙');
    await click('snippet-add');await page.locator('#snippet-content').fill('新的乙内容');await page.locator('#snippet-form button[type="submit"]').click();
    await click('clip-manage-menu');await click('group-undo');assert.deepEqual((await storage()).phrases.at(-1),{name:'乙',items:['新的乙内容']});assert.match(await page.locator('#toast').textContent(),/同名/);
  });
  await check('long clipboard holds stay open after the initial suppression window',async({page,click,hold})=>{
    await click('clipboard');await hold(page.locator('.clip-text').first(),1500);assert.equal(await page.locator('.clip-menu-sheet').count(),1);await page.locator('.clip-menu-cancel').click();
    await click('clip-manage-menu');await click('clip-manage');await hold(page.locator('.clip-text').first());assert.equal(await page.locator('.clip-menu-sheet').count(),1);
  });
  await check('undo at the clipboard limit refuses to discard newer copies',async({page,click,remove,storage})=>{
    await click('clipboard');await remove('A');await click('return');await page.evaluate(()=>{for(let i=0;i<298;i++)voxfixClipboard('new-'+i,'copy-'+i);});
    const before=(await storage()).clips;assert.equal(before.length,300);await click('clipboard');await click('clip-manage-menu');await click('undo-snippet');
    assert.deepEqual((await storage()).clips,before);assert.match(await page.locator('#toast').textContent(),/上限/);
  });
  await check('large candidate grids render in batches and keep every word reachable',async({page,click})=>{
    await page.evaluate(()=>{
      const words=Array.from({length:2000},(_,i)=>'候选内容'+i);words[1950]='字';
      VoxFixIme.pinyinCandidates=(raw,limit)=>JSON.stringify({words:words.slice(0,limit)});
      VoxFixIme.pinyinChoose=(raw,word)=>{calls.push(['pinyinChoose',raw,word]);return JSON.stringify({committed:true,remaining:''});};
    });
    await click('letter','N');await click('letter','I');await click('more-candidates');
    assert((await page.locator('.candidate-grid button').count())<=96,'Opening must not create 2000 buttons');
    await click('candidate-mode');assert.deepEqual(await page.locator('.candidate-grid button').allTextContents(),['字'],'Character filtering sees the entire candidate list');await click('candidate-mode');
    for(let i=0;i<25&&await page.locator('.candidate-grid button').count()<2000;i++){
      await page.locator('.candidate-grid').evaluate(grid=>{grid.scrollTop=grid.scrollHeight;grid.dispatchEvent(new Event('scroll'));});await page.waitForTimeout(20);
    }
    assert.equal(await page.locator('.candidate-grid button').count(),2000);
    await page.locator('.candidate-grid').evaluate(grid=>grid.scrollTop=20000);await page.waitForTimeout(40);
    const top=await page.locator('.candidate-grid').evaluate(grid=>grid.scrollTop);await click('more-candidates');await click('more-candidates');
    assert.equal(await page.locator('.candidate-grid').evaluate(grid=>grid.scrollTop),top,'Returning to the panel restores deep scrolling');
    for(let i=0;i<25&&await page.locator('.candidate-grid button').count()<2000;i++){
      await page.locator('.candidate-grid').evaluate(grid=>{grid.scrollTop=grid.scrollHeight;grid.dispatchEvent(new Event('scroll'));});await page.waitForTimeout(20);
    }
    await page.locator('.candidate-grid').evaluate(grid=>grid.scrollTop=grid.scrollHeight);await page.waitForTimeout(40);assert(await page.locator('[data-action="candidate-page"][data-value="down"]').isDisabled());
    await page.locator('.candidate-grid button').last().click();assert(await page.evaluate(()=>calls.some(call=>call[0]==='pinyinChoose'&&call[2]==='候选内容1999')));
  });
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
assert.deepEqual(failures,[]);
