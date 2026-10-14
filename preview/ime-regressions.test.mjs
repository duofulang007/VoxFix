import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
const {chromium}=createRequire(import.meta.url)('playwright');
const server=createServer(async(req,res)=>{
  const name=req.url==='/'?'index.html':req.url.slice(1);
  if(!/^[a-zA-Z0-9_.-]+$/.test(name)){res.writeHead(404);res.end();return;}
  try{res.setHeader('Content-Type',name.endsWith('.mjs')?'text/javascript':name.endsWith('.css')?'text/css':name.endsWith('.html')?'text/html':name.endsWith('.json')?'application/json':'text/plain');res.end(await readFile(new URL(name,import.meta.url)));}
  catch{res.writeHead(404);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({channel:'msedge',headless:true}),failures=[];
async function check(name,run){
  const page=await browser.newPage({viewport:{width:393,height:395},hasTouch:true});page.setDefaultTimeout(4000);
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(()=>{
    window.calls=[];
    window.VoxFixIme=Object.fromEntries(['compose','commit','completeEnglish','copy','delete','enter','hide','switcher','startVoice','startLocalVoice','stopVoice','cancelVoice','moveCursor','cursorPosition','gestureStart','gestureMove','gestureEnd','edit','ready','resize','refreshClipboard','importClipboard'].map(name=>[name,(...args)=>calls.push([name,...args])]));
    VoxFixIme.voiceReady=()=>true;
    localStorage.setItem('voxfix-snippets-v1',JSON.stringify({clips:Array.from({length:30},(_,i)=>'clip-'+i),phrases:[]}));
  });
  try{
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForFunction(()=>typeof voxfixReset==='function'&&document.querySelector('#pinyin-status').textContent.includes('已就绪'));
    const key=(action,value)=>page.locator(`#keyboard [data-action="${action}"]${value===undefined?'':`[data-value="${value}"]`}`).first();
    const click=(action,value)=>key(action,value).click();
    const local=async()=>{await click('clipboard');await click('clip-tab','phrases');await click('snippet-add');await page.setViewportSize({width:393,height:await page.locator('#keyboard').evaluate(e=>Math.ceil(e.getBoundingClientRect().height))});};
    const touch={async hold(box,ms){const session=await page.context().newCDPSession(page);const point={x:box.x+box.width/2,y:box.y+box.height/2};await session.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[point]});await page.waitForTimeout(ms);await session.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await page.waitForTimeout(40);await session.detach();}};
    await run({page,key,click,local,touch});assert.deepEqual(errors,[]);console.log('PASS '+name);
  }catch(e){failures.push(name+': '+e.message);console.log('FAIL '+name+': '+e.message.slice(0,160));}
  finally{await page.close();}
}
try{
  await check('Chinese permits full English words without overriding pinyin',async({page,click})=>{
    for(const l of 'HELLO')await click('letter',l);
    await page.waitForFunction(()=>document.querySelector('[data-action="candidate"][data-value="hello"]'));
    assert.equal(await page.locator('[data-action="candidate"]').first().textContent(),'hello');
    await click('candidate','hello');assert(await page.evaluate(()=>calls.some(row=>row[0]==='commit'&&row[1]==='hello')));
    for(const l of 'WOMEN')await click('letter',l);
    assert.equal(await page.locator('[data-action="candidate"]').first().textContent(),'我们');
    assert(await page.locator('[data-action="candidate"][data-value="women"]').count());
  });
  await check('Chinese single initials stay characters and preserve composition',async({page,click})=>{
    await click('letter','C');
    assert(await page.locator('[data-action="candidate"]').evaluateAll(buttons=>buttons.length>0&&buttons.every(button=>[...button.textContent].length===1)));
    await click('delete');for(const l of 'QQQQQQ')await click('letter',l);
    assert(await page.evaluate(()=>calls.some(row=>row[0]==='compose'&&row[1]==='qqqqqq')));
    assert(!(await page.evaluate(()=>calls.some(row=>row[0]==='commit'))));
    assert.match(await page.locator('[data-action="candidate"]').first().textContent(),/^[\u3400-\u9fff]{6}$/);
  });
  await check('Chinese and English unmatched input stays at the left',async({page,click})=>{
    const left=async()=>{
      const offset=await page.locator('.candidate-visible').evaluate(row=>{const range=document.createRange();range.selectNodeContents(row.firstElementChild);return range.getBoundingClientRect().left-row.getBoundingClientRect().left;});
      assert(offset<20,'First candidate text must start at the left: '+offset);
    };
    for(const l of 'IIIIII')await click('letter',l);
    assert.equal(await page.locator('[data-action="candidate"]').first().textContent(),'iiiiii');await left();
    await click('letter','I');assert(await page.evaluate(()=>calls.some(row=>row[0]==='compose'&&row[1]==='iiiiiii')));
    await click('language');for(const l of 'qqqqqq')await click('letter',l);
    assert.equal(await page.locator('[data-action="english-candidate"]').first().textContent(),'qqqqqq');await left();
  });
  await check('local edit clears stale composition',async({page,click,local})=>{await local();await click('letter','N');await click('letter','I');await page.locator('#snippet-content').fill('replacement');assert.equal(await page.locator('[data-action="candidate"]').count(),0);assert.equal(await page.locator('#snippet-content').inputValue(),'replacement');});
  await check('draft survives reopen and input reset',async({page,click,local})=>{await local();await page.locator('#snippet-content').fill('unsaved draft');await click('clipboard');await click('clip-tab','phrases');await click('snippet-add');assert.equal(await page.locator('#snippet-content').inputValue(),'unsaved draft');await page.evaluate(()=>voxfixReset());await local();assert.equal(await page.locator('#snippet-content').inputValue(),'unsaved draft');});
  await check('clipboard hold keeps content identity',async({page,click})=>{await click('clipboard');const r=await page.locator('.clip-text').first().boundingBox();await page.mouse.move(r.x+r.width/2,r.y+r.height/2);await page.mouse.down();await page.evaluate(()=>voxfixClipboard('new clip','a'.repeat(64)));await page.waitForTimeout(550);await page.mouse.up();assert.equal(await page.locator('.clip-menu-title').textContent(),'clip-0');});
  await check('native voice hides without browser snapshot',async({page,key})=>{const r=await key('space').boundingBox();await page.mouse.move(r.x+r.width/2,r.y+r.height/2);await page.mouse.down();await page.waitForTimeout(500);await page.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,value:true});document.dispatchEvent(new Event('visibilitychange'));});await page.mouse.up();assert.equal(await page.locator('.voice-panel').count(),0);});
  await check('local voice is isolated and cancellation restores selection',async({page,key,local})=>{await local();await page.locator('#snippet-content').fill('前原文后');await page.locator('#snippet-content').evaluate(e=>e.setSelectionRange(1,3));const r=await key('space').boundingBox();await page.mouse.move(r.x+r.width/2,r.y+r.height/2);await page.mouse.down();await page.waitForTimeout(500);assert(await page.evaluate(()=>calls.some(c=>c[0]==='startLocalVoice')));await page.evaluate(()=>voxfixVoiceText('你好',false));assert.equal(await page.locator('#snippet-content').inputValue(),'前你好后');await page.mouse.move(r.x+r.width/2,r.y-70);await page.mouse.up();assert.equal(await page.locator('#snippet-content').inputValue(),'前原文后');assert(!(await page.evaluate(()=>calls.some(c=>c[0]==='commit'||c[0]==='compose'))));});
  await check('scroll stays per panel',async({page,click})=>{await click('numbers');await page.locator('.number-strip').evaluate(e=>e.scrollTop=130);await page.locator('.number-strip [data-value="%"]') .click();assert.equal(await page.locator('.number-strip').evaluate(e=>e.scrollTop),130);await click('symbols');await click('symbol-category','中文');await click('symbol-lock');await page.locator('.symbol-grid').evaluate(e=>e.scrollTop=200);await page.locator('.symbol-grid button').last().click();assert((await page.locator('.symbol-grid').evaluate(e=>e.scrollTop))>0);await page.evaluate(()=>voxfixReset());await click('clipboard');await page.locator('.clip-list').evaluate(e=>e.scrollTop=400);await click('clip-tab','phrases');await click('clip-tab','clipboard');assert.equal(await page.locator('.clip-list').evaluate(e=>e.scrollTop),400);});
  await check('night dims without intercepting cursor',async({page,click})=>{await page.setViewportSize({width:393,height:351});const before=await page.locator('.key').first().evaluate(e=>getComputedStyle(e).color);await click('night');assert(!(await page.locator('#toast').isVisible()));assert(await page.locator('#night-shade').isVisible());assert.equal(await page.locator('#night-shade').evaluate(e=>e.getBoundingClientRect().height),351);assert.equal(await page.locator('.key').first().evaluate(e=>getComputedStyle(e).color),before);const hit=await page.locator('.cursor-zone').evaluate(e=>{const r=e.getBoundingClientRect();return document.elementFromPoint(r.x+r.width/2,r.y+r.height/2)?.closest('.cursor-zone')!==null;});assert(hit);await click('night');assert(!(await page.locator('#keyboard').evaluate(e=>e.classList.contains('night'))));});
  await check('explicit clipboard import does not revive automatic refresh',async({page,click})=>{const openMenu=async()=>{await page.locator('.clip-list').click({button:'right',position:{x:3,y:3}});};await page.evaluate(()=>voxfixClipboard('sample','b'.repeat(64)));await click('clipboard');await openMenu();await click('clear-snippets');assert(await page.locator('.clip-menu-sheet').count(),'清空列表需要先确认');await click('clip-confirm-ok');await page.evaluate(()=>voxfixClipboard('sample','b'.repeat(64)));assert.equal(await page.locator('.clip-text').count(),0);await openMenu();await click('system-paste');assert.equal(await page.locator('.clip-menu-sheet').count(),0);assert(await page.evaluate(()=>calls.some(c=>c[0]==='importClipboard')));await page.evaluate(()=>voxfixClipboard('sample','b'.repeat(64),true));assert.equal(await page.locator('.clip-text').count(),1);});
  await check('English cursor echo does not leave old candidates',async({page,click})=>{await click('language');for(const l of 'hel')await click('letter',l);await page.evaluate(()=>voxfixClearComposition());assert.equal(await page.locator('[data-action="english-candidate"]').count(),0);});
  await check('settings cannot strand its own entry',async({page,click})=>{await click('menu');await click('custom');assert(await page.locator('#feature-toggles [data-feature="menu"]').isDisabled());assert(await page.locator('#feature-toggles [data-feature="menu"]').isChecked());await page.locator('dialog[open] > button').click();await click('return');assert.equal(await page.locator('#keyboard [data-action="menu"]').count(),1);});
  await check('space release outside cancels',async({page,key})=>{const r=await key('space').boundingBox();await page.mouse.move(r.x+r.width/2,r.y+r.height/2);await page.mouse.down();await page.mouse.move(-10,r.y+r.height/2);await page.mouse.up();assert(!(await page.evaluate(()=>calls.some(c=>c[0]==='commit'))));});
  await check('native cursor keyboard uses Android bridge',async({page})=>{await page.evaluate(()=>{voxfixCursor(5,10);const z=document.querySelector('.cursor-zone');z.focus();z.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowLeft',bubbles:true}));});assert(await page.evaluate(()=>calls.some(c=>c[0]==='moveCursor'&&c[1]===-1)));});
  await check('Chinese association continues in the same bar',async({page,click})=>{for(const l of 'NI')await click('letter',l);await click('candidate','你');assert(await page.locator('[data-action="association-candidate"][data-value="好"]').count());await click('association-candidate','好');assert(await page.locator('[data-action="association-candidate"][data-value="吗"]').count());await click('more-candidates');assert(await page.locator('.candidate-grid').count());});
  await check('partial candidate preserves unconsumed pinyin',async({page,click})=>{for(const l of 'NIHAOQ')await click('letter',l);await click('candidate','你好');assert(await page.evaluate(()=>calls.some(c=>c[0]==='commit'&&c[1]==='你好')));assert(await page.evaluate(()=>calls.some(c=>c[0]==='compose'&&c[1]==='q')));await page.evaluate(()=>voxfixClearComposition(true));assert(await page.locator('[data-action="candidate"]').count());});
  await check('local partial commit preserves text and popup space',async({page,click,local})=>{await local();for(const l of 'NIHAOQ')await click('letter',l);await click('candidate','你好');assert.equal(await page.locator('#snippet-content').inputValue(),'你好q');const editor=await page.locator('#ime-local-editor').boundingBox(),key=await page.locator('.key').first().boundingBox(),input=await page.locator('#snippet-content').boundingBox();assert(input.height>=60);assert(editor.y+editor.height<key.y);});
  await check('local voice final result and draft survive input switch',async({page,key,local})=>{await local();await page.locator('#snippet-content').fill('前原文后');await page.locator('#snippet-content').evaluate(e=>e.setSelectionRange(1,3));const r=await key('space').boundingBox();await page.mouse.move(r.x+r.width/2,r.y+r.height/2);await page.mouse.down();await page.waitForTimeout(500);assert(await page.locator('#snippet-form button[type=submit]').isDisabled());await page.evaluate(()=>voxfixVoiceText('你好'));await page.mouse.up();await page.evaluate(()=>{voxfixVoiceText('你好。');voxfixVoiceDone('');});assert.equal(await page.locator('#snippet-content').inputValue(),'前你好。后');assert(await page.locator('#snippet-form button[type=submit]').isEnabled());await page.evaluate(()=>voxfixReset());await local();assert.equal(await page.locator('#snippet-content').inputValue(),'前你好。后');});
  await check('tool paths and night keep touchable keys',async({page,click})=>{for(let i=0;i<3;i++){await click('night');for(const action of ['numbers','symbols']){await click(action);if(action==='symbols'){await click('symbol-category','网址');await click('symbol-back');}}await click('letters');for(const action of ['menu','layouts','emoji','clipboard']){await click(action);await click('return');}assert.equal(await page.locator('[data-action="letter"]').count(),26);}await page.screenshot({path:new URL('../.android/priority-browser.png',import.meta.url).pathname.replace(/^\/([A-Za-z]:)/,'$1')});});
  await check('storage failure retains unsaved draft and old list',async({page,local})=>{await local();await page.locator('#snippet-content').fill('不能丢失的草稿');await page.evaluate(()=>{const original=Storage.prototype.setItem;Storage.prototype.setItem=function(key,value){if(key==='voxfix-snippets-v1')throw new DOMException('Storage full','QuotaExceededError');return original.call(this,key,value);};});await page.locator('#snippet-form button[type=submit]').click();assert(await page.locator('#ime-local-editor').isVisible());assert.equal(await page.locator('#snippet-content').inputValue(),'不能丢失的草稿');assert(await page.locator('#snippet-error').textContent());await page.locator('#snippet-cancel').click();assert.equal(await page.locator('.clip-text').count(),0);});
  // ---- 2026-10-11 第二轮修复的回归：删除加速、语音光标、剪贴板关闭/确认、分组、工具栏 ----
  await check('delete hold accelerates instead of a fixed interval',async({page,key})=>{
    await page.evaluate(()=>{window.__del=[];const original=VoxFixIme.delete.bind(VoxFixIme);let t0=0;VoxFixIme.delete=(...args)=>{if(!t0)t0=performance.now();window.__del.push(performance.now()-t0);return original(...args);};});
    const box=await key('delete').boundingBox();
    await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();await page.waitForTimeout(2400);await page.mouse.up();
    const stamps=await page.evaluate(()=>window.__del);
    assert(stamps.length>=8,'长按应连续删除，实际 '+stamps.length+' 次');
    const gaps=stamps.slice(1).map((v,i)=>v-stamps[i]).slice(1);
    assert(gaps[0]-gaps[gaps.length-1]>=20,'连删间隔应逐渐变小，实测 '+gaps.map(v=>v.toFixed(0)).join(','));
  });
  await check('voice keeps the caret in the local editor',async({page,key,local})=>{
    await local();await page.locator('#snippet-content').fill('原文');
    const box=await key('space').boundingBox();
    await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();await page.waitForTimeout(520);
    assert.equal(await page.locator('#snippet-content').evaluate(e=>e.readOnly),false,'语音期间不可把可见编辑框设为只读（只读会丢插入光标）');
    assert.equal(await page.evaluate(()=>document.activeElement?.id),'snippet-content');
    await page.evaluate(()=>voxfixVoiceText('你好'));
    assert.equal(await page.locator('#snippet-content').inputValue(),'原文你好');
    assert.equal(await page.locator('#snippet-content').evaluate(e=>e.selectionStart),4,'流式更新后插入符应停在文末');
    await page.mouse.up();await page.evaluate(()=>voxfixVoiceDone(''));
  });
  await check('clipboard tap returns to the keyboard',async({page,click})=>{
    await click('clipboard');assert(await page.locator('.clip-text').count());
    await page.locator('.clip-text').first().click();
    assert.equal(await page.locator('.clip-list').count(),0,'点剪贴板条目后应回到键盘');
    assert.equal(await page.locator('[data-action="letter"]').count(),26);
    assert(await page.evaluate(()=>calls.some(c=>c[0]==='commit')));
  });
  await check('clipboard double tap locks repeated paste and keeps manual return',async({page,key,click})=>{
    await page.evaluate(()=>{
      voxfixStartInput(1,false);
      const commit=VoxFixIme.commit;
      VoxFixIme.commit=text=>{commit(text);setTimeout(()=>voxfixStartInput(1,true),10);};
    });
    await click('clipboard');
    const tab=key('clip-tab','clipboard');
    const before=await tab.boundingBox();
    await tab.tap();await tab.tap();
    assert.equal(await tab.getAttribute('aria-pressed'),'true','触屏双击应锁定');
    assert.equal(await tab.locator('.clip-lock').count(),1,'锁定应有锁图标');
    assert.deepEqual(await tab.boundingBox(),before,'锁图标不应改变标签位置和尺寸');
    await page.locator('.clip-list').evaluate(e=>e.scrollTop=200);
    const row=page.locator('.clip-text').nth(5),text=await row.textContent();
    await row.tap();await page.waitForTimeout(40);await row.tap();await page.waitForTimeout(40);
    assert(await page.locator('.clip-list').isVisible(),'锁定后连续粘贴及同输入框回调均留在剪贴板');
    assert.deepEqual(await page.evaluate(()=>calls.filter(c=>c[0]==='commit').map(c=>c[1])),[text,text],'每次点击只粘贴一次');
    assert.equal(await page.locator('.clip-list').evaluate(e=>e.scrollTop),200,'粘贴保留列表位置');
    await click('return');assert.equal(await page.locator('[data-action="letter"]').count(),26,'锁定时仍可收起');
    await click('clipboard');assert.equal(await tab.getAttribute('aria-pressed'),'true','收起再打开保留锁定');
    await click('clip-tab','phrases');
    await page.evaluate(()=>voxfixClipboard('常用语示例','c'.repeat(64)));
    await click('clip-tab','clipboard');
    await page.locator('.clip-text').first().click({button:'right'});await click('context-favorite');
    await click('clip-tab','phrases');await page.locator('.clip-text').first().tap();
    assert.equal(await page.locator('.clip-list').count(),0,'剪贴板锁定不改变常用语的返回行为');
    await click('clipboard');
    await tab.click();await page.waitForTimeout(400);await tab.click();
    assert.equal(await tab.getAttribute('aria-pressed'),'true','间隔较久的单击不能解锁');
    await click('return');await click('clipboard');
    await tab.click();await tab.click();
    assert.equal(await tab.getAttribute('aria-pressed'),'false','鼠标双击应解锁');
    assert.equal(await tab.locator('.clip-lock').count(),0);
    await page.locator('.clip-text').first().tap();
    assert.equal(await page.locator('.clip-list').count(),0,'解锁后粘贴恢复返回键盘');
  });
  await check('single delete and clear both ask for confirmation',async({page,click})=>{
    await click('clipboard');
    const before=await page.locator('.clip-text').count();
    const box=await page.locator('.clip-text').first().boundingBox();
    await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();await page.waitForTimeout(620);await page.mouse.up();
    await click('context-delete');
    assert(await page.locator('.clip-menu-sheet').count(),'单条删除应先确认');
    assert.equal(await page.locator('.clip-text').count(),before,'确认前不应删除');
    await click('clip-confirm-ok');
    assert.equal(await page.locator('.clip-text').count(),before-1);
    await page.locator('.clip-list').click({button:'right',position:{x:3,y:3}});
    await click('undo-snippet');
    assert.equal(await page.locator('.clip-text').count(),before,'应能撤销删除');
  });
  await check('clipboard batch manage selects and deletes',async({page,click})=>{
    await click('clipboard');
    await click('clip-manage-menu');await click('clip-manage');
    await page.locator('.clip-pick').nth(0).click();await page.locator('.clip-pick').nth(1).click();
    assert.equal(await page.locator('.clip-pick.picked').count(),2);
    await click('clip-select-all');
    assert.equal(await page.locator('.clip-pick.picked').count(),30);
    await click('clip-manage-delete');await click('clip-confirm-ok');
    assert.equal(await page.locator('.clip-text').count(),0);
    await page.locator('.clip-list').click({button:'right',position:{x:3,y:3}});
    await click('undo-snippet');
    assert.equal(await page.locator('.clip-text').count(),30,'批量删除后应能撤销');
  });
  await check('phrase groups keep their own items',async({page,click})=>{
    await click('clipboard');await click('clip-tab','phrases');
    assert.equal(await page.locator('.group-pill:not(.group-add)').count(),1,'默认就有一个常用语分组');
    await page.setViewportSize({width:393,height:531});
    await click('clip-group-add');
    assert(await page.locator('#ime-local-editor').isVisible(),'新建分组应使用键盘内编辑框');
    await page.locator('#ime-local-editor input').fill('工作');
    await page.locator('#ime-local-editor button',{hasText:'新建'}).click();
    assert.equal(await page.locator('.group-pill:not(.group-add)').count(),2);
    assert.equal((await page.locator('.group-pill.selected').textContent()),'工作');
    await click('snippet-add');
    await page.locator('#snippet-content').fill('周报模板');
    await page.locator('#snippet-form button[type=submit]').click();
    assert((await page.locator('.clip-text').allTextContents()).includes('周报模板'));
    await page.locator('.group-pill:not(.group-add)').first().click();
    assert(!(await page.locator('.clip-text').allTextContents()).includes('周报模板'),'条目只应留在自己的分组');
  });
  await check('phrase groups can be renamed and deleted',async({page,click,touch})=>{
    await click('clipboard');await click('clip-tab','phrases');
    await page.setViewportSize({width:393,height:531});
    await click('clip-group-add');
    await page.locator('#ime-local-editor input').fill('甲组');
    await page.locator('#ime-local-editor button',{hasText:'新建'}).click();
    assert.equal(await page.locator('.group-pill:not(.group-add)').count(),2);
    await touch.hold(await page.locator('.group-pill:not(.group-add)').nth(1).boundingBox(),620);
    assert(await page.locator('.clip-menu-sheet').count(),'长按分组应打开管理菜单');
    await click('group-rename');
    await page.locator('#ime-local-editor input').fill('乙组');
    await page.locator('#ime-local-editor button',{hasText:'保存'}).click();
    assert.equal(await page.locator('.group-pill:not(.group-add)').nth(1).textContent(),'乙组');
    await touch.hold(await page.locator('.group-pill:not(.group-add)').nth(1).boundingBox(),620);
    await click('group-delete');
    assert(await page.locator('.clip-menu-sheet').count(),'删除分组需要先确认');
    assert.equal(await page.locator('.group-pill:not(.group-add)').count(),2,'确认前不应删除');
    await click('clip-confirm-ok');
    assert.equal(await page.locator('.group-pill:not(.group-add)').count(),1);
    await page.locator('.clip-list').click({button:'right',position:{x:3,y:3}});
    await click('group-undo');
    assert.equal(await page.locator('.group-pill:not(.group-add)').count(),2,'删除分组后应能撤销');
  });
  await check('management menu has a visible entry',async({page,click})=>{
    await click('clipboard');
    assert(await page.locator('#keyboard [data-action="clip-manage-menu"]').isVisible(),'剪贴板页右上角应有「更多管理操作」入口');
    await click('clip-manage-menu');
    assert.equal(await page.locator('.clip-menu-sheet').count(),1);
    assert(await page.locator('[data-action="snippet-add"]').count(),'管理菜单应含添加内容');
    assert(await page.locator('[data-action="system-paste"]').count(),'剪贴板页应含从系统剪贴板导入');
    assert(await page.locator('[data-action="clear-snippets"]').count(),'应含清空列表');
    await click('clip-close');
    await click('clip-tab','phrases');
    assert(await page.locator('#keyboard [data-action="clip-manage-menu"]').isVisible(),'常用语页也应有「更多管理操作」入口');
    assert(await page.locator('#keyboard [data-action="clip-group-add"]').isVisible(),'分组行里应有新分组入口');
    assert(await page.locator('#keyboard [data-action="snippet-add"]').isVisible(),'常用语页应有添加常用语入口');
  });
  await check('clearing the text also clears stale candidates',async({page,click})=>{
    for(const l of 'NI')await click('letter',l);
    await click('candidate','你');
    assert(await page.locator('[data-action="association-candidate"]').count(),'提交后应出现联想候选');
    await click('delete');
    assert.equal(await page.locator('[data-action="association-candidate"]').count(),0,'文本删完后联想候选必须清空');
    assert.equal(await page.locator('.candidate-bar').count(),0);
  });
  await check('clipboard content can join a sortable toolbar',async({page,click})=>{
    await click('clipboard');
    const box=await page.locator('.clip-text').first().boundingBox();
    await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();await page.waitForTimeout(620);await page.mouse.up();
    await click('toolbar-add');await click('return');
    assert.equal(await page.locator('.toolbar-custom-btn').count(),1,'自定义项应出现在工具栏');
    assert.equal(await page.locator('.toolbar-custom-btn').textContent(),'clip');
    const order=nodes=>nodes.map(n=>n.dataset.action==='toolbar-custom'?n.dataset.value:n.dataset.action);
    const toolbar=()=>page.locator('.toolbar [data-action]:not(.tb-remove)');
    const before=await toolbar().evaluateAll(order);
    const first=await toolbar().first().boundingBox();
    await page.mouse.move(first.x+first.width/2,first.y+first.height/2);await page.mouse.down();await page.waitForTimeout(600);await page.mouse.up();
    assert(await page.locator('.toolbar-sort').count(),'长按工具栏应进入排序模式');
    const nodes=await toolbar().all();
    const from=await nodes[0].boundingBox(),to=await nodes[nodes.length-1].boundingBox();
    await page.mouse.move(from.x+from.width/2,from.y+from.height/2);await page.mouse.down();
    await page.mouse.move(to.x+to.width/2,to.y+to.height/2,{steps:8});await page.mouse.up();
    const after=await toolbar().evaluateAll(order);
    assert.notDeepEqual(after,before,'拖动后工具栏顺序应变化');
    assert.equal(after[after.length-1],before[0],'被拖动的首项应移动到末尾');
    await click('toolbar-sort-done');
    assert.equal(await page.locator('.toolbar-sort').count(),0);
  });
  await check('clipboard candidate bar follows its setting',async({page,click})=>{
    await page.evaluate(()=>voxfixClipboard('候选栏内容','c'.repeat(64)));
    assert.equal(await page.locator('.clip-candidate').count(),0,'默认关闭「候选栏展示复制内容」');
    await click('menu');await click('custom');
    await page.locator('#clipboard-toggles [data-setting="clipboardCandidate"]').check();
    await page.locator('dialog[open] > button').click();
    await click('return');
    await page.evaluate(()=>voxfixClipboard('候选栏内容','d'.repeat(64)));
    assert.equal(await page.locator('.clip-candidate').count(),1);
    assert.equal(await page.locator('.clip-preview-source').textContent(),'来自剪贴板');
    await page.locator('[data-action="clip-candidate-insert"]').click();
    assert.equal(await page.locator('.clip-candidate').count(),0);
    assert(await page.evaluate(()=>calls.some(c=>c[0]==='commit'&&c[1]==='候选栏内容')));
  });
  await check('emoji long press repeats input',async({page,click,touch})=>{
    await click('emoji');
    const target=page.locator('.emoji-grid [data-action="insert"]').first();
    const value=await target.getAttribute('data-value'),box=await target.boundingBox();
    await touch.hold(box,1100);
    const inserts=await page.evaluate(v=>calls.filter(c=>c[0]==='commit'&&c[1]===v).length,value);
    assert(inserts>=3,'长按表情应连续输入多个，实际 '+inserts);
  });
  await check('long press a candidate adds it to phrases',async({page,click,touch})=>{
    for(const l of 'NI')await click('letter',l);
    const target=page.locator('.candidate-visible button').first();
    const word=await target.getAttribute('data-value'),box=await target.boundingBox();
    await touch.hold(box,600);
    await page.evaluate(()=>voxfixReset());
    await click('clipboard');await click('clip-tab','phrases');
    assert((await page.locator('.clip-text').allTextContents()).includes(word),'长按候选词应加入常用语：'+word);
  });
  // ---- 设计收敛守卫：页头只留官方的三件套形状，浮层只有一种 sheet ----
  await check('panel header keeps the three-shape layout',async({page,click})=>{
    await click('clipboard');
    assert.equal(await page.locator('.panel-title .ctl').count(),2,'剪贴板页应只有返回与更多管理操作两个圆形图标钮');
    assert.equal(await page.locator('.panel-title .tabs-pill button').count(),2,'分段控件应仍有两个页签');
    assert.equal(await page.locator('.panel-title button').count(),4,'剪贴板页页头＝返回＋两页签＋更多');
    assert.equal(await page.locator('.panel-title .manage-open,.panel-title .clip-more,.panel-title .group-add-title,.panel-title .manage-delete,.clip-manage-count').count(),0,'页头不应再有文字按钮');
    assert.equal(await page.locator('.tabs-pill button.selected').textContent(),'剪贴板');
    await click('clip-tab','phrases');
    assert.equal(await page.locator('.panel-title button').count(),5,'常用语页＝返回＋两页签＋添加常用语＋更多');
    assert.equal(await page.locator('.group-pill.group-add').count(),1,'分组行末尾应保留新建分组');
    assert.equal(await page.locator('.group-pill.group-add svg').count(),1,'新建分组应是圆形图标钮而不是文字 pill');
    await click('clip-tab','clipboard');
    await click('clip-manage-menu');await click('clip-manage');
    assert.equal(await page.locator('.panel-title .title-note').textContent(),'已选 0 条','管理模式中部应显示已选计数');
    assert.equal(await page.locator('.panel-title .text-action').textContent(),'全选');
    assert(await page.locator('.panel-title .ctl.danger[disabled]').count(),'未勾选时删除所选应禁用');
    await page.locator('.clip-pick').first().click();
    assert.equal(await page.locator('.panel-title .ctl.danger[disabled]').count(),0,'勾选后删除所选应可用');
    await click('clip-manage');
    assert.equal(await page.locator('.panel-title .title-note').count(),0);
  });
  await check('every overlay reuses one bottom sheet',async({page,click})=>{
    await click('clipboard');
    await click('clip-manage-menu');
    assert.equal(await page.locator('.clip-menu-sheet').count(),1);
    assert.equal(await page.locator('.clip-menu-title').textContent(),'管理内容');
    assert.equal(await page.locator('.clip-menu-sheet > button.row').count(),4,'管理菜单＝四行文字');
    assert.equal(await page.locator('.clip-menu-sheet > button.clip-menu-cancel').count(),1);
    await click('clear-snippets');
    assert.equal(await page.locator('.clip-menu-sheet > [data-action="clip-confirm-ok"]').count(),1,'确认层复用同一套 sheet');
    assert.equal(await page.locator('.clip-menu-title').textContent(),'确定清空剪贴板的所有内容？');
    await page.locator('.clip-menu-sheet > button.clip-menu-cancel').click();
    assert.equal(await page.locator('.clip-menu-sheet').count(),0);
    await page.locator('.clip-list').click({button:'right',position:{x:3,y:3}});
    assert.equal(await page.locator('.clip-menu-title').textContent(),'管理内容','长按空白处落到同一个 sheet');
    await click('clip-close');
    await click('clip-tab','phrases');
    await click('clip-manage-menu');
    assert.equal(await page.locator('.clip-menu-sheet > button.row').count(),3,'常用语页管理菜单＝三行文字');
    assert.equal(await page.locator('.clip-menu-sheet > button.danger').textContent(),'清空这个分组的常用语','危险项用统一样式');
    await click('clip-close');
  });
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
assert.deepEqual(failures,[]);
