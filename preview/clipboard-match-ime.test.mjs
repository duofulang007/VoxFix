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
  const session=await browser.newContext({viewport:{width:393,height:351},hasTouch:true}),page=await session.newPage(),errors=[];
  page.setDefaultTimeout(4000);page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(()=>{
    window.calls=[];window.acceptCommit=true;window.hostComposition='';
    const codes={'明天':'ming tian','下午':'xia wu','见':'jian','是':'shi','时':'shi'};
    window.VoxFixIme=Object.fromEntries(['compose','commit','completeEnglish','copy','delete','enter','hide','switcher','startVoice','startLocalVoice','stopVoice','cancelVoice','moveCursor','cursorPosition','gestureStart','gestureEnd','edit','haptic','ready','resize','refreshClipboard','importClipboard'].map(name=>[name,(...args)=>calls.push([name,...args])]));
    VoxFixIme.voiceReady=()=>true;VoxFixIme.pinyinReady=()=>true;
    VoxFixIme.pinyinCandidates=raw=>{
      const word=raw.startsWith('mingtian')?'明天':raw.startsWith('xiawu')?'下午':raw==='jian'?'见':'默认';
      return JSON.stringify({words:raw==='shi'?['是','时']:raw==='ln'?['李宁','两年','理念','了呢']:raw==='mtxw'?['明天下午',globalThis.shortSecond?'明天':'明天上午','明天','下午','明日','天','上午','明晚','明年','明朝','明天见','每天','明朗','明白','明文']:[word],preedit:raw==='mingtianxiawujian'?'ming tian xia wu jian':raw==='xiawujian'?'xia wu jian':raw==='shi'?'shi':codes[word]||raw});
    };
    VoxFixIme.pinyinChoose=(raw,word,learn)=>{
      calls.push(['pinyinChoose',raw,word,learn]);
      return JSON.stringify({committed:acceptCommit,remaining:raw.slice(codes[word].replaceAll(' ','').length)});
    };
    VoxFixIme.compose=raw=>{calls.push(['compose',raw]);hostComposition=raw;};
    VoxFixIme.commitAccepted=word=>{calls.push(['commitAccepted',word]);if(acceptCommit)hostComposition='';return JSON.stringify({committed:acceptCommit});};
    VoxFixIme.pinyinClearLearning=()=>{calls.push(['clearLearning']);return '{"cleared":true}';};
  });
  const ready=()=>page.waitForFunction(()=>typeof voxfixStartInput==='function'&&document.querySelector('#pinyin-status').textContent.includes('已就绪'));
  const key=(action,value)=>page.locator(`#keyboard [data-action="${action}"]${value===undefined?'':`[data-value="${value}"]`}`).first();
  const type=async raw=>{for(const letter of raw)await key('letter',letter.toUpperCase()).click();};
  const records=()=>page.evaluate(()=>JSON.parse(localStorage.getItem('voxfix-learning-v1')||'[]'));
  const reset=()=>page.evaluate(()=>voxfixStartInput(1,false,0));
  try{await page.goto(`http://127.0.0.1:${server.address().port}/`);await ready();await run({page,key,type,records,reset,ready});assert.deepEqual(errors,[]);console.log('PASS '+name);}
  catch(e){failures.push(name+': '+e.message);console.log('FAIL '+name+': '+e.message.slice(0,500));}
  finally{await session.close();}
}
const clip='明天下午三点到会议室';
const matching=async({page,type})=>{await page.evaluate(text=>voxfixClipboard(text),clip);await type('mtxw');await page.locator('.clipboard-reference').waitFor({state:'visible'});await page.waitForTimeout(310);assert(await page.locator('.clipboard-reference').isEnabled());};
try{
 await check('reference replaces host composition without putting typed letters back',async ctx=>{
  await matching(ctx);assert.equal(await ctx.page.locator('.clipboard-reference mark').textContent(),'明天下午');await ctx.page.evaluate(()=>acceptCommit=false);await ctx.page.locator('.clipboard-reference').click();assert.equal(await ctx.page.evaluate(()=>hostComposition),'mtxw');await ctx.page.evaluate(()=>acceptCommit=true);await ctx.page.locator('.clipboard-reference').click();assert.equal(await ctx.page.evaluate(()=>hostComposition),'');
 });
 await check('reference editor opens at the pinned search match with a visible background',async({page,type})=>{
  const text='普通前文。\n'.repeat(70)+'明天下午到会议室。\n'+'普通后文。\n'.repeat(70);
  await page.evaluate(text=>voxfixClipboard(text),text);await type('mtxw');await page.waitForTimeout(310);
  const quote=page.locator('.clipboard-reference'),box=await quote.boundingBox();await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();await page.waitForTimeout(620);await page.mouse.up();await page.setViewportSize({width:393,height:479});
  const input=page.locator('#snippet-content');assert.equal(await input.inputValue(),text);assert((await input.evaluate(node=>node.scrollTop))>0);
  const mark=page.locator('.snippet-highlight mark').first();assert.equal(await mark.textContent(),'明天下午');assert(await mark.evaluate(node=>{const rect=node.getBoundingClientRect(),input=document.querySelector('#snippet-content').getBoundingClientRect();return rect.top>=input.top&&rect.bottom<=input.bottom&&getComputedStyle(node).backgroundColor!=='rgba(0, 0, 0, 0)';}));
  await page.locator('#ime-local-editor').screenshot({path:'.android/clipboard-reference-highlight.png'});
  await page.evaluate(()=>voxfixClipboard('新复制的无关内容'));assert.equal(await mark.textContent(),'明天下午');
  await input.evaluate(node=>{node.scrollTop+=24;});assert.equal(await page.locator('.snippet-highlight').evaluate(node=>node.scrollTop),await input.evaluate(node=>node.scrollTop));
  await page.locator('#snippet-cancel').click();assert.equal(await page.evaluate(()=>hostComposition),'mtxw');assert.equal(await page.locator('.snippet-highlight').count(),0);
 });
 await check('watermark holds one to three digits against the right edge without taking text width',async({page,type})=>{
  await page.evaluate(()=>window.dispatchEvent(new StorageEvent('storage',{key:'voxfix-snippets-v1',newValue:JSON.stringify({clips:Array.from({length:300},(_,i)=>'通知'+i+'：明天下午到会议室，请提前准备材料。'),phrases:[]})})));await type('mtxw');await page.waitForTimeout(310);assert.equal(await page.locator('.clipboard-count').textContent(),'300 ▾');await page.locator('.clipboard-count').click();
  for(const i of [0,98,99,299]){const row=page.locator('.clipboard-result').nth(i),number=row.locator('.clipboard-result-index');assert.equal(await number.textContent(),String(i+1));assert(await number.evaluate(node=>{const box=node.getBoundingClientRect(),card=node.parentElement.getBoundingClientRect(),style=getComputedStyle(node),range=document.createRange();range.selectNodeContents(node);const text=range.getBoundingClientRect();return style.textAlign==='right'&&Math.abs(box.right-text.right)<1&&card.right-text.right<=5&&text.left>=box.left-.5&&node.scrollWidth<=node.clientWidth+1&&getComputedStyle(node.nextElementSibling).textIndent==='0px';}),'1/99/100/300 must fit and share the same right edge');}
  await page.locator('.clipboard-result').last().scrollIntoViewIfNeeded();await page.screenshot({path:'.android/clipboard-search-watermark-300-browser.png'});await page.locator('.clipboard-result').last().click();assert.equal(await page.locator('.clipboard-results').count(),0);
 });
 await check('all history opens in an aligned floating list without a time label, scrollbar or changed key geometry',async({page,type})=>{
  const rows=Array.from({length:12},(_,i)=>'通知'+i+'：明天下午到会议室，请提前准备材料。');
  await page.evaluate(rows=>{for(const text of rows)voxfixClipboard(text);voxfixClipboard('不相关的最新内容');},rows);await type('mtxw');await page.waitForTimeout(310);
  assert.equal(await page.locator('.clipboard-count').textContent(),'12 ▾');const key=page.locator('[data-action="letter"]').first(),before=await key.boundingBox(),height=(await page.locator('#keyboard').boundingBox()).height;
  await page.locator('.clipboard-count').click();const list=page.locator('.clipboard-results');assert(await list.isVisible());assert.equal(await list.locator('button').count(),12);assert.deepEqual(await key.boundingBox(),before);assert.equal((await page.locator('#keyboard').boundingBox()).height,height);
  assert.deepEqual(await list.locator('.clipboard-result-index').allTextContents(),Array.from({length:12},(_,i)=>String(i+1)));
  assert(await list.locator('.clipboard-result').evaluateAll(rows=>rows.every(row=>{const index=row.querySelector('.clipboard-result-index'),text=row.querySelector('.clipboard-result-text'),a=index.getBoundingClientRect(),b=text.getBoundingClientRect(),card=row.getBoundingClientRect(),style=getComputedStyle(index),chars=[],walker=document.createTreeWalker(text,NodeFilter.SHOW_TEXT);let node;while(node=walker.nextNode())for(let i=0;i<node.length;i++){const range=document.createRange();range.setStart(node,i);range.setEnd(node,i+1);const r=range.getBoundingClientRect();if(r.width&&r.top<b.bottom-1)chars.push({x:r.left,y:r.top});}const firstY=Math.min(...chars.map(r=>r.y)),first=chars.filter(r=>Math.abs(r.y-firstY)<1),next=chars.filter(r=>r.y>firstY+5);return card.right-a.right<=5&&card.bottom-a.bottom<=2&&a.height>=parseFloat(getComputedStyle(text).lineHeight)*1.9&&+style.opacity<.3&&style.pointerEvents==='none'&&+getComputedStyle(text).zIndex>+style.zIndex&&Math.abs(Math.min(...first.map(r=>r.x))-b.left)<1&&next.length&&Math.abs(Math.min(...next.map(r=>r.x))-b.left)<1;})), 'Pale bottom-right watermark occupies no text space; both lines use full width above it');
  const measure=await list.evaluate(node=>{const a=node.getBoundingClientRect(),kb=document.querySelector('#keyboard').getBoundingClientRect(),anchor=document.querySelector('.clipboard-slot').getBoundingClientRect();return {left:a.left,anchor:anchor.left,width:a.width,kb:kb.width,bar:getComputedStyle(node).scrollbarWidth,scroll:node.scrollHeight>node.clientHeight,background:getComputedStyle(node).backgroundColor};});assert(Math.abs(measure.left-measure.anchor)<1);assert(measure.width<measure.kb*.5);assert.equal(measure.bar,'none');assert(measure.scroll);assert.notEqual(measure.background,'rgba(0, 0, 0, 0)');assert(!/分钟前|历史记录|复制于/.test(await list.textContent()));
  const connection=await page.locator('.clipboard-slot').evaluate(slot=>{const a=slot.getBoundingClientRect(),list=document.querySelector('.clipboard-results').getBoundingClientRect();return {width:a.width-list.width,seam:slot.closest('.candidate-bar').getBoundingClientRect().bottom-list.top,open:slot.classList.contains('reference-open')};});assert(Math.abs(connection.width)<1);assert(connection.seam>=0&&connection.seam<2);assert(connection.open);
  await page.screenshot({path:'.android/clipboard-search-connected-browser.png'});await list.evaluate(node=>node.scrollTop=150);assert(await list.evaluate(node=>node.scrollTop)>0);await page.screenshot({path:'.android/clipboard-search-floating-browser.png'});
  const selected=await list.locator('button').last().getAttribute('data-value');await list.locator('button').last().click();assert.equal(await page.locator('.clipboard-results').count(),0);assert.deepEqual(await page.evaluate(()=>calls.filter(r=>r[0]==='commitAccepted')),[['commitAccepted',selected]]);assert.deepEqual(await key.boundingBox(),before);
 });
 await check('floating result long-press reuses shared editor and cancel restores its scroll and original history',async({page,type,records})=>{
  await page.evaluate(()=>{for(let i=0;i<8;i++)voxfixClipboard('记录'+i+'：明天下午开会。');});await type('mtxw');await page.waitForTimeout(310);await page.locator('.clipboard-count').click();const list=page.locator('.clipboard-results');await list.evaluate(node=>node.scrollTop=130);const item=list.locator('button').nth(3),box=await item.boundingBox(),before=await page.evaluate(()=>localStorage.getItem('voxfix-snippets-v1'));
  await item.scrollIntoViewIfNeeded();const scroll=await list.evaluate(node=>node.scrollTop),target=await item.boundingBox();await page.mouse.move(target.x+20,target.y+15);await page.mouse.down();await page.waitForTimeout(620);await page.mouse.up();assert(await page.locator('#ime-local-editor').isVisible());assert.deepEqual(await page.locator('#snippet-form .dialog-actions button').allTextContents(),['取消','拆词','保存']);assert(!await page.evaluate(()=>calls.some(r=>r[0]==='commitAccepted')));
  await page.setViewportSize({width:393,height:479});await page.locator('#snippet-cancel').click();await page.waitForTimeout(310);assert(await page.locator('.clipboard-results').isVisible());assert.equal(await page.locator('.clipboard-results').evaluate(node=>node.scrollTop),scroll);assert.equal(await page.evaluate(()=>localStorage.getItem('voxfix-snippets-v1')),before);assert.deepEqual(await records(),[]);
 });
 await check('swipe and outside dismissal cannot insert text or fire the underlying keyboard key',async({page,type})=>{
  await page.evaluate(()=>{for(let i=0;i<8;i++)voxfixClipboard('记录'+i+'：明天下午开会。');});await type('mtxw');await page.waitForTimeout(310);await page.locator('.clipboard-count').click();const item=page.locator('.clipboard-result').first(),box=await item.boundingBox();
  await item.dispatchEvent('pointerdown',{pointerId:50,pointerType:'touch',button:0,clientX:box.x+10,clientY:box.y+10});await item.dispatchEvent('pointermove',{pointerId:50,pointerType:'touch',clientX:box.x+10,clientY:box.y+40});await item.dispatchEvent('pointerup',{pointerId:50,pointerType:'touch'});await item.dispatchEvent('click',{pointerId:50,pointerType:'touch',detail:1});assert(!await page.evaluate(()=>calls.some(r=>r[0]==='commitAccepted')));
  const before=await page.locator('#editor').inputValue();await page.locator('[data-action="letter"][data-value="Q"]').click();assert.equal(await page.locator('.clipboard-results').count(),0);assert.equal(await page.locator('#editor').inputValue(),before);
 });
 await check('repeated copies move to the front, refresh does not reorder, editing and reference do not reorder',async({page,type})=>{
  const a='明天下午先开会',b='明天下午再讨论';await page.evaluate(({a,b})=>{voxfixClipboard(a,'copy-a');voxfixClipboard(b,'copy-b');voxfixClipboard(a,'copy-a-again');},{a,b});const read=()=>page.evaluate(()=>JSON.parse(localStorage.getItem('voxfix-snippets-v1')).clips);assert.deepEqual((await read()).slice(0,2),[a,b]);
  await page.evaluate(a=>voxfixClipboard(a,'copy-a-again'),a);assert.deepEqual((await read()).slice(0,2),[a,b]);await type('mtxw');await page.waitForTimeout(310);await page.locator('.clipboard-count').click();await page.locator('.clipboard-result').nth(1).click();assert.deepEqual((await read()).slice(0,2),[a,b]);
  await page.reload();await page.waitForFunction(()=>typeof voxfixStartInput==='function');assert.deepEqual((await read()).slice(0,2),[a,b]);
 });
 await check('weak typo match opens choices instead of silently committing its preview',async({page,type})=>{
  await page.evaluate(()=>voxfixClipboard('通知：明天开会，请查收。'));await type('mingtan');await page.waitForTimeout(310);assert.equal(await page.locator('.clipboard-reference span').textContent(),'近似匹配，点开查看');await page.locator('.clipboard-reference').click();assert(await page.locator('.clipboard-results').isVisible());assert(!await page.evaluate(()=>calls.some(r=>r[0]==='commitAccepted')));await page.locator('.clipboard-result').first().click();assert(await page.evaluate(()=>calls.some(r=>r[0]==='commitAccepted')));
 });
 await check('unmatched candidates use natural widths, equal gaps and a shared text baseline',async({page,type})=>{
  await type('ln');await page.waitForTimeout(310);await page.screenshot({path:'.android/clipboard-reference-unmatched-browser.png'});
  const measures=await page.locator('.candidate-visible [data-action="candidate"]').evaluateAll(nodes=>nodes.slice(0,3).map(node=>{const range=document.createRange();range.selectNodeContents(node);const text=range.getBoundingClientRect(),button=node.getBoundingClientRect();return {left:text.left,right:text.right,center:text.y+text.height/2,buttonWidth:button.width,textWidth:text.width};}));
  const [a,b,c]=measures;assert(Math.abs((b.left-a.right)-(c.left-b.right))<1,'Unmatched second candidate must not reserve clipboard space: '+JSON.stringify(measures));
  assert(Math.max(...measures.map(r=>r.center))-Math.min(...measures.map(r=>r.center))<.6,'Candidate text must share one vertical center');
 });
 await check('holding an ordinary candidate never silently saves it as a phrase',async({page,type})=>{
  await type('shi');const before=await page.evaluate(()=>localStorage.getItem('voxfix-snippets-v1'));
  const button=page.locator('.candidate-visible [data-action="candidate"]').first(),b=await button.boundingBox(),cdp=await page.context().newCDPSession(page);
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:b.x+b.width/2,y:b.y+b.height/2}]});await page.waitForTimeout(650);await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  assert.equal(await page.evaluate(()=>localStorage.getItem('voxfix-snippets-v1')),before,'Ordinary candidate hold must not write favorites');
 });
 await check('only a related clipboard expands a short second candidate, then restores natural width',async({page,type})=>{
  await page.evaluate(()=>globalThis.shortSecond=true);await type('mtxw');const second=page.locator('.clipboard-slot'),before=await second.boundingBox();
  await page.evaluate(text=>voxfixClipboard(text),clip);await page.waitForTimeout(310);assert(await page.locator('.clipboard-reference').isEnabled());assert((await second.boundingBox()).width>before.width);
  await page.evaluate(()=>{voxfixClipboard('不相关内容');window.dispatchEvent(new StorageEvent('storage',{key:'voxfix-snippets-v1',newValue:JSON.stringify({clips:[],phrases:[]})}));});await page.waitForTimeout(180);assert(await page.locator('.clipboard-reference').isDisabled());assert.equal((await second.boundingBox()).width,before.width);
 });
 await check('real touch on the visible reference text opens reference editing, not phrase saving',async ctx=>{
  const {page}=ctx;await matching(ctx);const before=await page.evaluate(()=>localStorage.getItem('voxfix-snippets-v1')),b=await page.locator('.clipboard-reference span').boundingBox(),cdp=await page.context().newCDPSession(page);
  const x=b.x+b.width/2,y=b.y+b.height/2;assert.equal(await page.evaluate(({x,y})=>document.elementFromPoint(x,y).closest('button').dataset.action,{x,y}),'clipboard-reference');
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y}]});await page.waitForTimeout(650);await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  assert.equal(await page.locator('#snippet-title').textContent(),'编辑本次引用');assert.equal(await page.evaluate(()=>localStorage.getItem('voxfix-snippets-v1')),before);
 });
 await check('stable match keeps keyboard geometry and directly replaces composition without learning',async ctx=>{
  const {page,type,records}=ctx;await page.evaluate(text=>voxfixClipboard(text),clip);await type('mtxw');
  const first=await page.locator('.candidate-visible>.candidate-hold').first().boundingBox(),keyBox=await page.locator('[data-action="letter"]').first().boundingBox(),height=await page.locator('#keyboard').boundingBox();
  await page.waitForTimeout(310);assert(await page.locator('.clipboard-reference').isEnabled());assert.deepEqual(await page.locator('.candidate-visible>.candidate-hold').first().boundingBox(),first);assert.deepEqual(await page.locator('[data-action="letter"]').first().boundingBox(),keyBox);assert.equal((await page.locator('#keyboard').boundingBox()).height,height.height);
  assert.equal(await page.locator('.clipboard-reference span').textContent(),clip);assert.equal(await page.locator('.clipboard-displaced').getAttribute('data-value'),'明天上午');
  await page.screenshot({path:'.android/clipboard-reference-browser.png'});await page.locator('.clipboard-reference').click();assert.deepEqual(await records(),[]);assert.deepEqual(await page.evaluate(()=>calls.filter(r=>r[0]==='commitAccepted')), [['commitAccepted',clip]]);assert.equal(await page.evaluate(()=>calls.filter(r=>r[0]==='pinyinChoose').length),0);assert(!await page.evaluate(()=>calls.some(r=>r[0]==='copy')));
 });
 await check('finger freezes preview changes, swipe cancels editing and keeps all candidates browsable',async ctx=>{
  const {page}=ctx;await matching(ctx);const quote=page.locator('.clipboard-reference');const box=await quote.boundingBox();
  await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();await page.evaluate(()=>voxfixClipboard('不相干内容'));await page.waitForTimeout(220);assert(await quote.isEnabled(),'Do not swap a touched target');
  await page.mouse.move(box.x-110,box.y+box.height/2,{steps:5});await page.waitForTimeout(600);assert(await page.locator('#ime-local-editor').isHidden());assert((await page.locator('.candidate-visible').evaluate(e=>e.scrollLeft))>0);await page.mouse.up();await page.waitForTimeout(180);assert(await quote.isEnabled(),'A different current clipboard must not hide a matching historical entry');assert(!await page.evaluate(()=>calls.some(r=>r[0]==='commitAccepted')));
 });
 await check('long-press edits locally in the compact shared editor without overwriting clipboard or learning',async ctx=>{
  const {page,records}=ctx;await matching(ctx);const height=await page.locator('#keyboard').boundingBox(),keyBox=await page.locator('[data-action="letter"]').first().boundingBox();
  await page.locator('.clipboard-reference').dispatchEvent('pointerdown',{pointerId:5,pointerType:'touch',button:0,clientX:160,clientY:20});await page.waitForTimeout(620);await page.locator('#keyboard').dispatchEvent('pointerup',{pointerId:5,pointerType:'touch',button:0});
  assert(await page.locator('#ime-local-editor.snippet-edit').isVisible());assert.equal((await page.locator('#keyboard').boundingBox()).height,height.height+128);const moved=await page.locator('[data-action="letter"]').first().boundingBox();assert.equal(moved.y,keyBox.y+128);assert.equal(moved.height,keyBox.height);await page.setViewportSize({width:393,height:479});assert.equal(await page.evaluate(()=>calls.filter(r=>r[0]==='commitAccepted').length),0);
  await page.locator('#snippet-content').fill('明天下午四点到会议室');await page.screenshot({path:'.android/clipboard-reference-editor-browser.png'});await page.evaluate(()=>acceptCommit=false);await page.locator('#snippet-form .dialog-actions button').last().click();assert(await page.locator('#ime-local-editor').isVisible());assert.equal(await page.locator('#snippet-content').inputValue(),'明天下午四点到会议室','Refused host commit must retain the draft');await page.evaluate(()=>{acceptCommit=true;calls=calls.filter(r=>r[0]!=='commitAccepted');});await page.locator('#snippet-form .dialog-actions button').last().click();assert.deepEqual(await records(),[]);assert.deepEqual(await page.evaluate(()=>calls.filter(r=>r[0]==='commitAccepted')),[['commitAccepted','明天下午四点到会议室']]);assert(!await page.evaluate(()=>calls.some(r=>r[0]==='copy')));
 });
 await check('shared reference editor splits only its draft and saves without learning or rewriting clipboard',async ctx=>{
  const {page,records,type}=ctx;await matching(ctx);
  await page.locator('.clipboard-reference').dispatchEvent('pointerdown',{pointerId:15,pointerType:'touch',button:0,clientX:160,clientY:20});await page.waitForTimeout(620);await page.locator('#keyboard').dispatchEvent('pointerup',{pointerId:15,pointerType:'touch',button:0});
  assert.deepEqual(await page.locator('#snippet-form .dialog-actions button').allTextContents(),['取消','拆词','保存']);
  await page.setViewportSize({width:393,height:479});await page.locator('#snippet-content').fill('明天下午四点到会议室');
  const callsBefore=await page.evaluate(()=>calls.length);await page.locator('#snippet-split').click();await page.waitForSelector('.clip-split-chip');
  assert(await page.locator('#ime-local-editor').isHidden());assert.equal((await page.locator('#keyboard').boundingBox()).height,351);
  const word=await page.locator('.clip-split-chip').first().textContent();await page.locator('.clip-split-chip').first().click();
  assert(!await page.evaluate(start=>calls.slice(start).some(c=>['commit','compose','commitAccepted','splitBegin','splitCompose'].includes(c[0])),callsBefore));
  await page.locator('.clip-split-title [data-action="split-return"]').click();assert(await page.locator('#ime-local-editor').isVisible());assert.equal(await page.locator('#snippet-content').inputValue(),word);assert.equal((await page.locator('#keyboard').boundingBox()).height,479);
  await page.locator('#snippet-form button[type=submit]').click();assert(await page.evaluate(word=>calls.some(c=>c[0]==='commitAccepted'&&c[1]===word),word));assert.deepEqual(await records(),[]);
  await type('mtxw');await page.waitForTimeout(310);assert.equal(await page.locator('.clipboard-reference').getAttribute('data-value'),clip);
 });
 await check('cancel restores original composition and a refused reference preserves it',async ctx=>{
  const {page}=ctx;await matching(ctx);await page.locator('.clipboard-reference').dispatchEvent('pointerdown',{pointerId:6,pointerType:'touch',button:0,clientX:160,clientY:20});await page.waitForTimeout(620);await page.locator('#keyboard').dispatchEvent('pointerup',{pointerId:6,pointerType:'touch',button:0});await page.locator('#snippet-content').fill('');assert(await page.locator('#snippet-form .dialog-actions button').last().isDisabled());await page.locator('#snippet-form .dialog-actions button').first().click();await page.waitForTimeout(310);assert.equal(await page.locator('.clipboard-reference').getAttribute('data-value'),clip);
  await page.evaluate(()=>acceptCommit=false);await page.locator('.clipboard-reference').click();assert(await page.locator('.clipboard-reference').isEnabled());assert.equal(await page.locator('#editor').inputValue(),'mtxw');
 });
 await check('sensitive, unrelated and private text never appears; opt-out works',async ctx=>{
  const {page,type,reset}=ctx;
  for(const text of ['明天下午密码是123456','不相关内容']){await reset();await page.evaluate(()=>window.dispatchEvent(new StorageEvent('storage',{key:'voxfix-snippets-v1',newValue:JSON.stringify({clips:[],phrases:[]})})));await page.evaluate(text=>voxfixClipboard(text),text);await type('mtxw');await page.waitForTimeout(310);assert(await page.locator('.clipboard-reference').isDisabled(),text);}
  await reset();await page.evaluate(text=>{voxfixStartInput(1,false,0x1000000);voxfixClipboard(text);},clip);await type('mtxw');await page.waitForTimeout(310);assert(await page.locator('.clipboard-reference').isDisabled());
  await reset();await page.evaluate(text=>{voxfixClipboard(text);const box=document.querySelector('[data-setting="clipboardMatch"]');box.checked=false;box.dispatchEvent(new Event('change',{bubbles:true}));},clip);await type('mtxw');await page.waitForTimeout(310);assert.equal(await page.locator('.clipboard-reference').count(),0);
 });
 await check('cleared current clipboard keeps history searchable and field changes refresh current text',async ctx=>{
  const {page,reset,type}=ctx;await matching(ctx);await page.evaluate(()=>voxfixClipboard(''));await page.waitForTimeout(160);assert(await page.locator('.clipboard-reference').isEnabled());await reset();await page.evaluate(text=>voxfixClipboard(text),clip);await type('mtxw');await page.waitForTimeout(310);assert(await page.locator('.clipboard-reference').isEnabled());
 });
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
if(failures.length)throw Error(failures.join('\n'));
