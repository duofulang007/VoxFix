import {lexiconRecords} from './test-lexicon.mjs';
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
const browser=await chromium.launch({channel:'msedge',headless:true});
const failures=[];
try{
for(const name of ['compact centered panels','anchored management motion','geometry and candidates','host focus routes shared keyboard','focus keeps independent compositions','manual matches and highlights','cancel and reset','paste refusal and success','locked search repeated paste','phrases and scroll','reverse and focus']){
 const context=await browser.newContext({viewport:{width:393,height:600},hasTouch:true}),page=await context.newPage(),errors=[];
 page.setDefaultTimeout(5000);page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{
  window.calls=[];window.accept=true;
  window.VoxFixIme=Object.fromEntries(['compose','commit','delete','enter','hide','switcher','ready','resize','refreshClipboard','importClipboard','clipHint','cursorPosition','moveCursor','searchFocus','gestureStart','gestureEnd','gestureClearSteps','gestureLock','haptic'].map(name=>[name,(...args)=>calls.push([name,...args])]));
  VoxFixIme.pinyinReady=()=>true;VoxFixIme.voiceReady=()=>true;
  VoxFixIme.pinyinCandidates=raw=>JSON.stringify({words:raw.startsWith('mingtian')?['明天','明']:raw==='rj'?['软件','如今']:['默认'],preedit:raw==='mingtianxiawu'?'ming tian xia wu':raw==='mingtian'?'ming tian':'ruan jian'});
  VoxFixIme.pinyinChoose=(...args)=>{calls.push(['pinyinChoose',...args]);return JSON.stringify({committed:true,remaining:''})};
  VoxFixIme.pinyinChooseLocal=(raw,word)=>{calls.push(['pinyinChooseLocal',raw,word]);return JSON.stringify({committed:true,remaining:raw==='mingtianxiawu'?'xiawu':''})};
  VoxFixIme.commitAccepted=text=>{calls.push(['commitAccepted',text]);return JSON.stringify({committed:accept})};
  localStorage.setItem('voxfix-snippets-v1',JSON.stringify({clips:['凑单软件整合，薅羊毛项目','软件项目，软件清单','https://example.com','<script>软件</script>',...Array.from({length:20},(_,i)=>'记录'+i+'：明天下午开会。')],phrases:[{name:'常用语',items:['收到，谢谢','软件问题已记录']}]}));
 });
 await page.goto(`http://127.0.0.1:${server.address().port}`);
 await page.waitForFunction(()=>typeof voxfixStartInput==='function'&&document.querySelector('#pinyin-status').textContent.includes('已就绪'));
 const key=(action,value)=>page.locator(`#keyboard [data-action="${action}"]${value===undefined?'':`[data-value="${value}"]`}`).first();
 const type=async raw=>{for(const c of raw)await key('letter',c.toUpperCase()).click()};
 const toggle=()=>page.locator('.clip-search-toggle:visible');
 const input=page.locator('.clip-search-input');
 const results=page.locator('.clip-search-result');
 try{
  const baseline=await key('letter','Q').boundingBox();
  if(name==='locked search repeated paste')await type('mingtian');
  await key('clipboard').click();
  const snippets=await page.evaluate(()=>localStorage.getItem('voxfix-snippets-v1'));
  if(name==='compact centered panels'){
   for(const width of [320,393,411]){
    await page.setViewportSize({width,height:351});
    for(const tab of ['clipboard','phrases']){
     await key('clip-tab',tab).click();
     const header=await page.locator('.clip-header').boundingBox(),tabs=await page.locator('.tabs-pill').boundingBox();
     assert.equal(header.height,44);assert(Math.abs(header.x+header.width/2-tabs.x-tabs.width/2)<.1);
     const left=await page.locator('.clip-header-leading').boundingBox(),right=await page.locator('.clip-header-actions').boundingBox();
     assert(left.x+left.width<=tabs.x&&tabs.x+tabs.width<=right.x,'页签不能与两侧操作重叠');
     const labels=await page.locator('.tabs-pill button').evaluateAll(nodes=>nodes.map(n=>({width:n.clientWidth,text:n.scrollWidth})));
     assert(labels.every(n=>n.text<=n.width),'窄屏页签文字不能被裁切');
     assert.equal((await page.locator('.clip-text').first().boundingBox()).height,44);
     assert(await page.locator('.clip-header-actions [data-action="clip-search-toggle"]').isVisible(),'两页搜索都在右侧');
     if(tab==='phrases'){
      assert(await page.locator('.clip-header-actions [data-action="snippet-add"]:last-child').isVisible(),'常用语添加在最右侧');
      assert(await page.locator('.clip-header-leading [data-action="clip-manage-menu"]').isVisible(),'分组清空入口在返回旁');
      assert.equal(await key('clip-manage-menu').getAttribute('aria-label'),'清空当前分组的常用语');
      const paths=await key('clip-manage-menu').locator('svg').innerHTML();
      assert.match(paths,/path/);assert.doesNotMatch(paths,/circle/,'清空使用垃圾桶而非三个点');
     }
     if(width===411)await page.locator('#keyboard').screenshot({path:`.android/clipboard-layout-${tab}.png`});
     await key('clip-manage-menu').click();
     await page.locator('.clip-management-menu').evaluate(n=>Promise.all(n.getAnimations().map(a=>a.finished)));
     const menu=await page.locator('.clip-menu-sheet').boundingBox(),kb=await page.locator('#keyboard').boundingBox(),footer=await page.locator('.keyboard-footer').boundingBox(),trigger=await key('clip-manage-menu').boundingBox();
     assert(menu.y>=header.y+header.height&&menu.y+menu.height<=footer.y+.1);
     assert(menu.x>=kb.x&&menu.x+menu.width<=kb.x+kb.width);
     assert.equal(menu.height,tab==='clipboard'?200:50);assert.equal(menu.width,228);
     assert(Math.abs(menu.x-Math.max(kb.x+8,Math.min(trigger.x+trigger.width-menu.width,kb.x+kb.width-menu.width-8)))<1);assert(Math.abs(menu.y-trigger.y-trigger.height-6)<1);
     assert.equal(await page.locator('.clip-management-menu .clip-menu-title,.clip-management-menu .clip-menu-cancel').count(),0);
     assert.equal(await page.locator('#keyboard [data-action="snippet-add"]').count(),1,'添加内容只保留一个入口');
     if(tab==='phrases')assert.equal(await page.locator('#keyboard [data-action="clip-group-add"]').count(),1,'新建分组只保留分组栏入口');
     const metrics=await page.locator('.clip-menu-sheet').evaluate(n=>({height:n.clientHeight,scroll:n.scrollHeight}));
     assert.equal(metrics.height,metrics.scroll,'常规管理菜单应完整显示');
     if(width===411)await page.locator('#keyboard').screenshot({path:`.android/clipboard-layout-${tab}-menu.png`});
     await key('clip-manage-menu').click();await page.locator('.clip-management-backdrop').waitFor({state:'detached'});
     assert.equal(await page.evaluate(()=>localStorage.getItem('voxfix-snippets-v1')),snippets);
    }
   }
   console.log('PASS '+name);continue;
  }
  if(name==='anchored management motion'){
   const trigger=key('clip-manage-menu'),menu=page.locator('.clip-management-menu');
   const kb=await page.locator('#keyboard').boundingBox();await trigger.click();
   assert.equal(await trigger.getAttribute('aria-expanded'),'true');assert(await trigger.evaluate(n=>n.classList.contains('clip-more-open')));
   await menu.evaluate(n=>{window.managementNode=n;window.managementMotion=n.getAnimations()[0];managementMotion.pause();managementMotion.currentTime=80;});
   const animation=await page.evaluate(()=>({duration:managementMotion.effect.getTiming().duration,frames:managementMotion.effect.getKeyframes()}));
   assert.equal(animation.duration,180);assert(animation.frames[0].transform.includes('scale(0.95)'));
   await page.evaluate(()=>voxfixRedraw());assert(await menu.evaluate(n=>n===managementNode&&n.getAnimations()[0]===managementMotion));
   assert.equal(await page.evaluate(()=>managementMotion.currentTime),80);
   await page.evaluate(()=>managementMotion.finish());
   const origin=await menu.evaluate(n=>{const r=n.getBoundingClientRect(),p=getComputedStyle(n).transformOrigin.split(' ').map(parseFloat);return {x:r.x+p[0],y:r.y+p[1]};}),anchor=await trigger.boundingBox();
   assert(Math.abs(origin.x-anchor.x-anchor.width/2)<.1&&Math.abs(origin.y-anchor.y-anchor.height/2)<.1);
   assert.deepEqual(await page.locator('#keyboard').boundingBox(),kb);
   await page.keyboard.press('ArrowDown');assert.equal(await page.locator(':focus').getAttribute('data-action'),'system-paste');
   await trigger.click();await page.evaluate(()=>{managementMotion.pause();managementMotion.currentTime=80;});
   assert(await page.locator('.clip-management-backdrop').evaluate(n=>n.inert));
   await trigger.click();assert(await menu.evaluate(n=>n===managementNode));assert(await page.evaluate(()=>managementMotion.playbackRate>0));
   await page.keyboard.press('Escape');await page.locator('.clip-management-backdrop').waitFor({state:'detached'});assert.equal(await page.locator(':focus').getAttribute('data-action'),'clip-manage-menu');
   await trigger.click();await key('clip-manage').click();
   await page.locator('.clip-management-backdrop').waitFor({state:'detached'});
   assert(await page.locator('.clip-pick').count());
   await page.locator('.clip-list').dispatchEvent('contextmenu');assert(await menu.isVisible());
   await page.keyboard.press('Escape');await page.locator('.clip-management-backdrop').waitFor({state:'detached'});await key('clip-manage').click();
   await page.emulateMedia({reducedMotion:'reduce'});await trigger.click();assert.equal(await menu.evaluate(n=>n.getAnimations()[0].effect.getKeyframes()[0].transform),'none');
   await page.locator('.clip-management-backdrop').click({position:{x:8,y:240}});await page.locator('.clip-management-backdrop').waitFor({state:'detached'});
   assert.equal(await page.evaluate(()=>localStorage.getItem('voxfix-snippets-v1')),snippets);
   console.log('PASS '+name);continue;
  }
  if(name==='locked search repeated paste'){
   await key('clip-tab','clipboard').click();await key('clip-tab','clipboard').click();
   assert.equal(await key('clip-tab','clipboard').getAttribute('aria-pressed'),'true');
  }
  await toggle().click();
  if(name==='geometry and candidates'){
   assert.equal((await key('letter','Q').boundingBox()).height,baseline.height);
   const at=await key('letter','Q').boundingBox();assert.equal(await page.locator('.toolbar').count(),0);assert.equal(await page.locator('.candidate-bar').count(),0);
   await type('mingtianxiawu');assert.equal(await input.inputValue(),'mingtianxiawu');assert.equal(await page.locator('.candidate-bar').count(),1);
   assert.deepEqual(await key('letter','Q').boundingBox(),at);
   await page.locator('.candidate-visible [data-value="明天"]').click();assert.equal(await input.inputValue(),'明天xiawu');
   assert.deepEqual(await page.evaluate(()=>calls.filter(r=>['commit','compose','commitAccepted','pinyinChoose'].includes(r[0]))),[]);
   assert.deepEqual(await lexiconRecords(page),[]);
  }else if(name==='host focus routes shared keyboard'){
   await input.fill('软件');
   assert.equal(await page.evaluate(()=>calls.filter(r=>r[0]==='searchFocus').at(-1)[1]),true);
   await page.evaluate(()=>{calls=[];globalThis.voxfixHostFocus?.(true);voxfixCursor(2,5);voxfixSelection(2,2)});
   await key('language').click();await key('letter','q').click();
   assert.equal(await input.inputValue(),'软件','点击宿主后键盘不能继续改搜索词');
   assert(await page.evaluate(()=>calls.some(r=>r[0]==='commit'&&r[1]==='q')),'宿主目标必须收到键盘输入');
   assert.equal(await input.evaluate(n=>getComputedStyle(n).caretColor),'rgba(0, 0, 0, 0)','编辑宿主时搜索光标停止闪动');
   assert(await page.locator('.clip-search-results').isVisible());
   assert.equal(await page.evaluate(()=>calls.filter(r=>r[0]==='searchFocus').at(-1)[1]),false);
   await page.locator('.cursor-zone').dispatchEvent('keydown',{key:'ArrowRight',bubbles:true});assert(await page.evaluate(()=>calls.some(r=>r[0]==='moveCursor'&&r[1]===1)));
   await input.click();await key('letter','R').click();assert.equal(await input.inputValue(),'软件r');
   assert.notEqual(await input.evaluate(n=>getComputedStyle(n).caretColor),'rgba(0, 0, 0, 0)');
   assert.equal(await page.evaluate(()=>calls.filter(r=>r[0]==='searchFocus').at(-1)[1]),true);
  }else if(name==='focus keeps independent compositions'){
   await type('rj');const resultsBefore=await results.allTextContents();
   await page.evaluate(()=>{voxfixCursor(4,8);voxfixSelection(4,4)});assert.equal(await input.inputValue(),'rj');assert.equal(await input.evaluate(n=>n.readOnly),false,'普通宿主回读不能抢走搜索焦点');
   await page.evaluate(()=>voxfixHostFocus(true));await type('mingtian');assert.equal(await input.inputValue(),'rj');assert.deepEqual(await results.allTextContents(),resultsBefore);
   await page.locator('.candidate-visible [data-value="明天"]').click();assert(await page.evaluate(()=>calls.some(r=>r[0]==='pinyinChoose'&&r[1]==='mingtian')));
   await input.click();assert.equal(await input.inputValue(),'rj');assert(await page.locator('.candidate-visible [data-value="软件"]').isVisible());
   const before=await page.evaluate(()=>calls.filter(r=>['commit','commitAccepted','pinyinChoose'].includes(r[0])).length);
   await page.locator('.candidate-visible [data-value="软件"]').click();assert.equal(await input.inputValue(),'软件');assert.equal(await page.evaluate(()=>calls.filter(r=>['commit','commitAccepted','pinyinChoose'].includes(r[0])).length),before);
   await page.evaluate(()=>voxfixReset());assert(await page.locator('#ime-local-editor').isHidden());assert.equal(await page.evaluate(()=>calls.filter(r=>r[0]==='searchFocus').at(-1)[1]),false);
  }else if(name==='manual matches and highlights'){
   await input.fill('软件 项目');assert.equal(await results.count(),2);assert.deepEqual(await results.nth(1).locator('mark').allTextContents(),['软件项目','软件']);
   await input.fill('rj');assert.equal(await results.count(),3);assert.equal(await results.first().locator('mark').first().textContent(),'软件');
   await input.fill('软合');assert.equal(await results.count(),1);assert.deepEqual(await results.first().locator('mark').allTextContents(),['软','合']);
   await input.fill('example');assert.equal(await results.count(),1);
   await input.fill('<script>');assert.equal(await results.count(),1);assert.equal(await page.locator('.clip-search-results script').count(),0);
   await input.fill('无匹配内容');assert.equal(await results.count(),0);assert(await page.locator('.clip-search-results .empty-state').isVisible());
  }else if(name==='cancel and reset'){
   await input.fill('软件');await toggle().click();assert(await page.locator('#ime-local-editor').isHidden());assert.equal((await page.locator('#keyboard').boundingBox()).height,351);
   assert.equal(await page.evaluate(()=>localStorage.getItem('voxfix-snippets-v1')),snippets);
   await toggle().click();await type('rj');await page.evaluate(()=>voxfixStartInput(1,false,0));assert(await page.locator('#ime-local-editor').isHidden());assert.equal((await page.locator('#keyboard').boundingBox()).height,351);
   assert.deepEqual(await page.evaluate(()=>calls.filter(r=>r[0]==='commitAccepted')),[]);
  }else if(name==='paste refusal and success'){
   await input.fill('软件');const text=await results.first().getAttribute('data-value');await page.evaluate(()=>accept=false);await results.first().click();assert(await input.isVisible());assert.equal(await input.inputValue(),'软件');
   await page.evaluate(()=>accept=true);await results.first().click();assert(await page.locator('#ime-local-editor').isHidden());assert.deepEqual(await page.evaluate(()=>calls.filter(r=>r[0]==='commitAccepted').map(r=>r[1])),[text,text]);
   assert.equal(await page.evaluate(()=>localStorage.getItem('voxfix-snippets-v1')),snippets);
  }else if(name==='locked search repeated paste'){
   await input.fill('明天');await page.locator('[data-action="clip-search-keys"]:visible').first().click();
   const list=page.locator('.clip-search-results');await list.evaluate(n=>n.scrollTop=180);
   const top=await list.evaluate(n=>n.scrollTop),before=await results.allTextContents();
   const pick=async index=>{const item=results.nth(index),text=await item.getAttribute('data-value');await item.dispatchEvent('click');return text};
   await page.evaluate(()=>accept=false);await pick(4);assert.equal(await input.inputValue(),'明天');assert.equal(await list.evaluate(n=>n.scrollTop),top);
   await page.evaluate(()=>{accept=true;calls=[]});const first=await pick(4),second=await pick(5);await pick(5);
   assert.deepEqual(await page.evaluate(()=>calls.filter(r=>r[0]==='commitAccepted').map(r=>r[1])),[first,second,second]);
   assert.equal(await input.inputValue(),'明天');assert.deepEqual(await results.allTextContents(),before);assert.equal(await list.evaluate(n=>n.scrollTop),top);
   assert(await page.locator('.clip-search-resume').isVisible());assert.equal(await page.locator('.key-rows').count(),0);
   assert.equal(await page.evaluate(()=>localStorage.getItem('voxfix-snippets-v1')),snippets);
   assert.deepEqual(await lexiconRecords(page),[]);
   await toggle().click();assert(await page.locator('#ime-local-editor').isHidden());assert.equal(await key('clip-tab','clipboard').getAttribute('aria-pressed'),'true');assert.equal(await page.locator('.candidate-bar').count(),0);assert.equal(await page.locator('#editor').inputValue(),'');
   await key('clip-tab','clipboard').click();await key('clip-tab','clipboard').click();
   assert.equal(await key('clip-tab','clipboard').getAttribute('aria-pressed'),'false');await toggle().click();await input.fill('软件');await results.first().click();assert(await page.locator('#ime-local-editor').isHidden());
  }else if(name==='phrases and scroll'){
   await input.fill('明天');const list=page.locator('.clip-search-results');await list.evaluate(n=>n.scrollTop=180);const top=await list.evaluate(n=>n.scrollTop);await page.evaluate(()=>voxfixRedraw());assert.equal(await list.evaluate(n=>n.scrollTop),top);
   await page.locator('[data-action="clip-search-keys"]:visible').first().click();assert.equal(await page.locator('.key-rows').count(),0);assert(await page.locator('.clip-search-resume').isVisible());await page.locator('.clip-search-resume').click();assert.equal(await input.inputValue(),'明天');assert(await key('letter','Q').isVisible());
   await toggle().click();await key('clip-tab','phrases').click();await toggle().click();await input.fill('软件');assert.equal(await results.count(),1);assert.equal(await results.first().getAttribute('data-value'),'软件问题已记录');
  }else{
   for(let i=0;i<3;i++){await toggle().click();await toggle().click();}
   await input.fill('软件');await input.focus();assert.equal(await input.evaluate(n=>getComputedStyle(n).outlineStyle),'none');assert.equal(await input.evaluate(n=>getComputedStyle(n).borderLeftWidth),'0px');await page.waitForTimeout(260);
   assert.equal(await page.locator('.clip-header .tabs-pill').evaluate(n=>getComputedStyle(n).opacity),'0');
   assert.equal(await page.locator('.clip-search-close').evaluate(n=>getComputedStyle(n).opacity),'1');
   assert.equal(await page.locator('.clip-search-close').evaluate(n=>getComputedStyle(n).fill),'none');
   for(const width of [320,393,411]){
    await page.setViewportSize({width,height:600});
    const capsule=await page.locator('.clip-search-surface').boundingBox(),close=await toggle().boundingBox(),field=await input.boundingBox();
    assert(capsule.x<=close.x&&capsule.y<=close.y&&capsule.x+capsule.width>=close.x+close.width&&capsule.y+capsule.height>=close.y+close.height,'白色胶囊必须完整包住X控件');
    assert(field.x+field.width<=close.x,'搜索文字不能进入X区域');
    assert.equal(await page.locator('.clip-search-surface').evaluate(n=>getComputedStyle(n).backgroundColor),'rgb(255, 255, 255)');
    assert(await toggle().evaluate(n=>{const r=n.getBoundingClientRect();return document.elementFromPoint(r.x+r.width/2,r.y+r.height/2)?.closest('button')===n}),'白底不能拦住X点击');
   }
   await page.setViewportSize({width:393,height:600});
   await page.screenshot({path:'.android/clipboard-panel-search-browser.png'});
   await toggle().click();
   for(const width of [320,393,411]){await page.setViewportSize({width,height:600});const offset=await page.locator('.clip-header').evaluate(h=>{const a=h.getBoundingClientRect(),b=h.querySelector('.tabs-pill').getBoundingClientRect();return Math.abs(a.x+a.width/2-b.x-b.width/2)});assert(offset<.1);}
  }
  assert.deepEqual(errors,[]);console.log('PASS '+name);
 }catch(error){failures.push(name+': '+error.stack);console.error('FAIL '+name+': '+error.message)}finally{await context.close()}
}
const context=await browser.newContext({viewport:{width:1200,height:1000}}),page=await context.newPage();
try{
 await page.addInitScript(()=>localStorage.setItem('voxfix-snippets-v1',JSON.stringify({clips:['软件甲','软件乙'],phrases:[{name:'常用语',items:[]}]})));
 await page.goto(`http://127.0.0.1:${server.address().port}`);await page.waitForFunction(()=>document.querySelector('#pinyin-status').textContent.includes('已就绪'));
 const key=action=>page.locator(`#keyboard [data-action="${action}"]`).first();
 await page.locator('#editor').fill('前缀');await key('clipboard').click();
 const tab=page.locator('[data-action="clip-tab"][data-value="clipboard"]');await tab.click();await tab.click();
 await page.locator('.clip-search-toggle').click();const input=page.locator('.clip-search-input');await input.fill('软件');
 const results=page.locator('.clip-search-result');await results.nth(0).click();await results.nth(1).click();await results.nth(1).click();
 assert.equal(await page.locator('#editor').inputValue(),'前缀软件甲软件乙软件乙');assert.equal(await input.inputValue(),'软件');
 await page.locator('#editor').click();await page.locator('#editor').evaluate(n=>n.setSelectionRange(2,2));await results.nth(0).click();
 assert.equal(await page.locator('#editor').inputValue(),'前缀软件甲软件甲软件乙软件乙');assert.equal(await input.inputValue(),'软件');
 await input.click();await input.evaluate(n=>n.setSelectionRange(n.value.length,n.value.length));await page.locator('#keyboard [data-action="letter"][data-value="Q"]').click();assert.equal(await input.inputValue(),'软件q');
 await key('delete').click();assert.equal(await input.inputValue(),'软件');
 await page.locator('.clip-search-toggle').click();assert.equal(await page.locator('#editor').inputValue(),'前缀软件甲软件甲软件乙软件乙');
 console.log('PASS browser locked search appends consecutive results without replacing earlier selections');
}catch(error){failures.push('browser locked search: '+error.stack);console.error(error.message)}finally{await context.close()}
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
if(failures.length)throw Error(failures.join('\n'));
