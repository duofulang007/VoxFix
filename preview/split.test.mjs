import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import {createClipboardSplitter} from './split.mjs';
const data=JSON.parse(await readFile(new URL('./pinyin-data.json',import.meta.url),'utf8'));
const split=createClipboardSplitter(data);
for(const text of ['尊敬的用户您好！今天是中国移动为您服务的第1919天。','中文 English123，😀\n第二行','𠀀生僻字','https://s.click.Taobao.com/jWXJyci','尊敬'.repeat(5000),''])assert.equal(split(text).join(''),text);
assert(split('尊敬的用户您好！').includes('尊敬'));
assert.deepEqual(split('https://s.click.Taobao.com/jWXJyci'),['https://','s.','click.','Taobao.com','/','jWXJyci']);
const ambiguous=createClipboardSplitter({entries:[['研究',100],['研究生',1],['生命',100],['命',1],['起源',100]]});
assert.deepEqual(ambiguous('研究生命起源'),['研究','生命','起源'],'Use frequency-based segmentation rather than blindly taking the longest match');
console.log('PASS offline dictionary segmentation, source preservation, URL, mixed text, supplementary characters and 10k text');
const {chromium}=createRequire(import.meta.url)('playwright');
const text='尊敬的用户您好！今天是中国移动为您服务的第1919天，感谢您的选择。';
const server=createServer(async(req,res)=>{const name=req.url==='/'?'index.html':req.url.slice(1);if(!/^[\w.-]+$/.test(name)){res.writeHead(404).end();return;}try{res.setHeader('Content-Type',name.endsWith('.mjs')?'text/javascript':name.endsWith('.css')?'text/css':name.endsWith('.json')?'application/json':'text/html');res.end(await readFile(new URL(name,import.meta.url)));}catch{res.writeHead(404).end();}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({channel:'msedge',headless:true});
try{
 for(const native of [true,false]){
  const page=await browser.newPage({viewport:{width:native?411:1100,height:native?479:1100},deviceScaleFactor:1});const errors=[];page.on('pageerror',e=>errors.push(e.message));page.setDefaultTimeout(10000);
  await page.addInitScript(({native,text})=>{
   window.copied=[];window.rejectCopy=false;
   Object.defineProperty(navigator,'clipboard',{value:{writeText:async value=>{if(rejectCopy)throw Error('denied');copied.push(value);}}});
   localStorage.setItem('voxfix-snippets-v1',JSON.stringify({clips:[text],phrases:[]}));
   if(native){window.calls=[];window.nativeText='';window.rejectSplit=false;window.VoxFixIme=Object.fromEntries(['compose','commit','copy','delete','enter','hide','switcher','startVoice','startLocalVoice','stopVoice','cancelVoice','gestureStart','gestureMove','gestureEnd','edit','ready','resize','refreshClipboard','clipHint'].map(n=>[n,(...args)=>calls.push([n,...args])]));VoxFixIme.voiceReady=()=>true;
    VoxFixIme.splitBegin=()=>{calls.push(['splitBegin']);return JSON.stringify({session:1});};
    VoxFixIme.splitCompose=(id,value)=>{calls.push(['splitCompose',id,value]);if(!rejectSplit)nativeText=value;return JSON.stringify({updated:!rejectSplit});};
    VoxFixIme.splitFinish=id=>{calls.push(['splitFinish',id]);return JSON.stringify({finished:true});};
   }
  },{native,text});
  await page.goto('http://127.0.0.1:'+server.address().port+'/');await page.waitForSelector('.key-rows');
  const stored=await page.evaluate(()=>localStorage.getItem('voxfix-snippets-v1'));
  const open=async()=>{if(!await page.locator('.clip-list').count()){const hint=page.locator('[data-action="clip-candidate-dismiss"]');if(await hint.count())await hint.click();await page.locator('#keyboard [data-action="clipboard"]').first().click();}await page.locator('.clip-text').first().dispatchEvent('contextmenu');await page.locator('[data-action="context-split"]').click();await page.waitForSelector('.clip-split-chip');};
  if(!native)await page.locator('#editor').evaluate(e=>{e.value='前旧文后';e.setSelectionRange(1,3);});
  await open();const chips=page.locator('.clip-split-chip');assert.equal(await page.locator('.clip-split-title input,.clip-split-title textarea').count(),0);
  if(native){const rows=await chips.evaluateAll(nodes=>nodes.slice(0,5).map(n=>n.getBoundingClientRect().top));assert(rows.every(y=>y===rows[0]),'Official short sample must fit five naturally sized chips on its first row');}
  assert.equal(await page.locator('#keyboard').evaluate(e=>e.getBoundingClientRect().height),351);
  const drag=async(from,to)=>{
   const a=await chips.nth(from).boundingBox(),b=await chips.nth(to).boundingBox();
   await page.mouse.move(a.x+a.width/2,a.y+a.height/2);await page.mouse.down();await page.mouse.move(b.x+b.width/2,b.y+b.height/2,{steps:8});await page.mouse.up();
  };
  await drag(0,4);
  assert.deepEqual(errors,[]);
  assert.deepEqual(await chips.evaluateAll(nodes=>nodes.flatMap((n,i)=>n.getAttribute('aria-pressed')==='true'?[i]:[])),[0,1,2,3,4],'Dragging across chips selects the entire range without a release toggle');
  await drag(4,1);
  assert.deepEqual(await chips.evaluateAll(nodes=>nodes.flatMap((n,i)=>n.getAttribute('aria-pressed')==='true'?[i]:[])),[0],'Dragging from a selected chip removes the range');
  await chips.nth(0).click();
  const touch=await page.context().newCDPSession(page);
  await touch.send('Emulation.setTouchEmulationEnabled',{enabled:true});
  const first=await chips.nth(0).boundingBox(),last=await chips.nth(6).boundingBox();
  const point=box=>({x:box.x+box.width/2,y:box.y+box.height/2});
  await touch.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[point(first)]});
  await touch.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[point(last)]});
  await touch.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  assert.deepEqual(await chips.evaluateAll(nodes=>nodes.flatMap((n,i)=>n.getAttribute('aria-pressed')==='true'?[i]:[])),[0,1,2,3,4,5,6],'Touch drag must select across wrapped rows');
  await touch.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[point(last)]});
  await touch.send('Input.dispatchTouchEvent',{type:'touchCancel',touchPoints:[]});
  await chips.nth(6).click();assert.equal(await chips.nth(6).getAttribute('aria-pressed'),'false','Cancelled touch must not toggle or block the next click');
  await page.locator('[data-action="split-all"]').click();await page.locator('[data-action="split-all"]').click();
  await touch.detach();
  assert.equal(await chips.nth(0).evaluate(n=>getComputedStyle(n).touchAction),'none','Touch dragging must not be intercepted as native scrolling');
  assert.equal(await page.locator('.clip-split-grid').evaluate(n=>getComputedStyle(n).touchAction),'pan-y','Blank space keeps vertical scrolling');
  await chips.nth(0).click();assert.equal(await chips.nth(0).getAttribute('aria-pressed'),'true');
  await chips.nth(0).click();assert.equal(await chips.nth(0).getAttribute('aria-pressed'),'false');
  if(!native)assert.equal(await page.locator('#editor').inputValue(),'前旧文后','Cancel-all restores only the original selected text');
  await chips.nth(0).click();await chips.nth(2).click();await chips.nth(0).click();
  const chosen=await chips.nth(2).textContent();
  if(native)assert.equal(await page.evaluate(()=>nativeText),chosen);else assert.equal(await page.locator('#editor').inputValue(),'前'+chosen+'后');
  await page.locator('[data-action="split-all"]').click();await page.locator('[data-action="split-all"]').click();
  const duplicate=page.locator('.clip-split-chip').filter({hasText:/^的$/});assert(await duplicate.count()>=2);
  await duplicate.nth(0).click();await duplicate.nth(1).click();await duplicate.nth(0).click();assert.equal(await duplicate.nth(0).getAttribute('aria-pressed'),'false');assert.equal(await duplicate.nth(1).getAttribute('aria-pressed'),'true');
  if(native)assert.equal(await page.evaluate(()=>nativeText),'的');else assert.equal(await page.locator('#editor').inputValue(),'前的后');
  await page.locator('[data-action="split-all"]').click();assert.equal(await page.locator('[data-action="split-all"]').getAttribute('aria-pressed'),'true');
  await chips.nth(0).click();assert.equal(await page.locator('[data-action="split-all"]').getAttribute('aria-pressed'),'false');await chips.nth(0).click();assert.equal(await page.locator('[data-action="split-all"]').getAttribute('aria-pressed'),'true');
  await page.locator('.clip-split-grid').evaluate(e=>{e.scrollTop=100;});const top=await page.locator('.clip-split-grid').evaluate(e=>e.scrollTop);await chips.last().click();assert.equal(await page.locator('.clip-split-grid').evaluate(e=>e.scrollTop),top);
  await page.locator('[data-action="split-all"]').click();
  await page.locator('.clip-split-title [data-action="split-return"]').click();assert.equal(await page.locator('.clip-list').count(),1);
  assert.equal(await page.evaluate(()=>localStorage.getItem('voxfix-snippets-v1')),stored,'Picking words must not rewrite clipboard entries');
  if(native){assert(await page.evaluate(()=>calls.some(c=>c[0]==='splitFinish')));assert(!await page.evaluate(()=>calls.some(c=>c[0]==='copy'||c[0]==='commit')));await open();await page.evaluate(()=>rejectSplit=true);await page.locator('.clip-split-chip').first().click();assert.equal(await page.locator('.clip-list').count(),1);assert.match(await page.locator('#toast').textContent(),/输入位置/);await page.evaluate(()=>rejectSplit=false);await open();await page.evaluate(()=>voxfixStartInput(1,true));assert.equal(await page.locator('.clip-split-chip').count(),0);await page.evaluate(()=>voxfixClipboard('尊敬'.repeat(5000),'long-clip'));await open();await page.locator('[data-action="split-all"]').click();assert.equal((await page.evaluate(()=>nativeText)).length,10000);await page.locator('.clip-split-chip').first().click();assert.equal((await page.evaluate(()=>nativeText)).length,9998);await page.evaluate(()=>voxfixReset());assert.equal(await page.locator('.clip-split-chip').count(),0);}
  else{const value=await page.locator('#editor').inputValue();await open();await page.locator('.clip-split-chip').first().click();const kept=await page.locator('#editor').inputValue();await page.locator('.clip-split-title [data-action="split-return"]').click();assert.equal(await page.locator('#editor').inputValue(),kept);assert.notEqual(kept,value);await open();await page.locator('#editor').evaluate(e=>{e.value='外部编辑';e.setSelectionRange(0,0);});await page.locator('.clip-split-chip').first().click();assert.equal(await page.locator('#editor').inputValue(),'外部编辑');assert.equal(await page.locator('.clip-list').count(),1);}
  if(native)await page.evaluate(text=>voxfixClipboard(text,'split-copy-source'),text);
  await open();
  const copy=page.locator('.clip-split-actions button').last();
  await copy.click();assert.equal(await page.locator('.clip-split-card').count(),1,'Copy must stay on the split page rather than return without copying');
  if(native)assert.equal(await page.evaluate(()=>calls.filter(c=>c[0]==='copy').at(-1)?.[1]),text);else assert.equal(await page.evaluate(()=>copied.at(-1)),text);
  await chips.nth(2).click();await chips.nth(0).click();
  const picked=await chips.nth(2).textContent()+await chips.nth(0).textContent();
  await copy.click();
  if(native)assert.equal(await page.evaluate(()=>calls.filter(c=>c[0]==='copy').at(-1)?.[1]),picked);else assert.equal(await page.evaluate(()=>copied.at(-1)),picked);
  assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('voxfix-snippets-v1')).clips[0]),picked,'Copied selection must appear in clipboard history');
  assert.equal(await chips.nth(0).getAttribute('aria-pressed'),'true');assert.equal(await chips.nth(2).getAttribute('aria-pressed'),'true');
  if(!native){await page.evaluate(()=>rejectCopy=true);await copy.click();assert.match(await page.locator('#toast').textContent(),/复制失败/);assert.equal(await page.locator('.clip-split-card').count(),1);await page.evaluate(()=>rejectCopy=false);}
  await page.locator('.clip-split-title [data-action="split-return"]').click();
  console.log('PASS '+(native?'native bridge':'browser')+' split copy: full source, selected order, history and panel retention');
  assert(await page.locator('.clip-list').isVisible());await page.locator('.clip-text').first().dispatchEvent('contextmenu');await page.locator('[data-action="context-edit"]').click();
  if(native)await page.setViewportSize({width:411,height:479});
  assert.deepEqual(await page.locator('#snippet-form .dialog-actions button').allTextContents(),['取消','拆词','保存']);
  const layout=await page.locator('.snippet-frame').evaluate(frame=>{
   const rect=frame.getBoundingClientRect(),input=frame.querySelector('textarea'),text=input.getBoundingClientRect(),actions=frame.querySelector('.dialog-actions').getBoundingClientRect(),button=frame.querySelector('#snippet-split').getBoundingClientRect();
   const buttons=[...frame.querySelectorAll('.dialog-actions button')].map(node=>node.getBoundingClientRect());
   return {bottomGap:rect.bottom-text.bottom,textRight:text.right,actionsLeft:actions.left,actionsWidth:actions.width,vertical:buttons.every((box,i)=>!i||box.top>=buttons[i-1].bottom),lines:(input.clientHeight-parseFloat(getComputedStyle(input).paddingTop)-parseFloat(getComputedStyle(input).paddingBottom))/parseFloat(getComputedStyle(input).lineHeight),leftTarget:document.elementFromPoint(text.left+8,text.bottom-8)===input,buttonTarget:document.elementFromPoint(button.x+button.width/2,button.y+button.height/2)?.id};
  });
  assert(layout.bottomGap<=2,'Text must fill the frame down to its bottom border');assert(layout.textRight<=layout.actionsLeft,'Controls must not cover any editable text');assert.equal(layout.actionsWidth,46);assert(layout.vertical,'Controls must be arranged top to bottom');if(native)assert(layout.lines>=4,'Native editor must show four complete lines');assert(layout.leftTarget,'Text at the bottom must remain editable');assert.equal(layout.buttonTarget,'snippet-split');
  if(native)for(const width of [320,393,411]){
   await page.setViewportSize({width,height:479});
   const fit=await page.locator('.snippet-frame').evaluate(frame=>{
    const outer=frame.closest('#ime-local-editor').getBoundingClientRect(),box=frame.getBoundingClientRect(),input=frame.querySelector('textarea'),text=input.getBoundingClientRect();
    return {border:getComputedStyle(frame).borderTopWidth,left:box.left-outer.left,right:outer.right-box.right,height:text.height,width:text.width,buttons:[...frame.querySelectorAll('button')].every(button=>{const r=button.getBoundingClientRect();return r.top>=outer.top&&r.bottom<=outer.bottom&&r.right<=outer.right&&document.elementFromPoint(r.x+r.width/2,r.y+r.height/2)===button;})};
   });
   assert.equal(fit.border,'0px');assert.equal(fit.left,0);assert.equal(fit.right,0);assert.equal(fit.height,120);assert.equal(fit.width,width-56);assert(fit.buttons,'All three controls must fit and remain clickable inside the white frame');
  }
  if(native)await page.setViewportSize({width:411,height:479});
  await page.locator('#snippet-content').fill('编辑框文字铺满左侧，右侧控件不遮挡正文。\n'.repeat(20));
  assert(await page.locator('#snippet-content').evaluate(input=>{input.scrollTop=input.scrollHeight;return input.scrollTop>0&&input.scrollTop+input.clientHeight>=input.scrollHeight-1;}),'Long text must still scroll to its end');
  if(native)await page.locator('#ime-local-editor').screenshot({path:'.android/editor-white-frame.png'});
  const beforeDraft=await page.evaluate(()=>localStorage.getItem('voxfix-snippets-v1'));
  await page.locator('#snippet-content').fill('');assert(await page.locator('#snippet-split').isDisabled());await page.locator('#snippet-content').fill('明天下午四点到会议室');assert(await page.locator('#snippet-split').isEnabled());
  const hostBefore=await page.locator('#editor').inputValue(),callsBefore=native?await page.evaluate(()=>calls.length):0;
  await page.locator('#snippet-split').click();await page.waitForSelector('.clip-split-chip');assert.equal(await page.locator('#keyboard').evaluate(e=>e.getBoundingClientRect().height),351);
  const pickedWord=(await chips.allTextContents()).slice(0,3).join('');await drag(0,2);
  if(native)assert(!await page.evaluate(start=>calls.slice(start).some(c=>['splitBegin','splitCompose','commit','compose'].includes(c[0])),callsBefore));
  await page.locator('.clip-split-title [data-action="split-return"]').click();
  assert.equal(await page.locator('#snippet-content').inputValue(),pickedWord);assert.equal(await page.locator('#editor').inputValue(),hostBefore);assert.equal(await page.evaluate(()=>localStorage.getItem('voxfix-snippets-v1')),beforeDraft);
  if(native)assert.equal(await page.locator('#keyboard').evaluate(e=>e.getBoundingClientRect().height),479);else assert(await page.locator('#snippet-dialog').evaluate(e=>e.open));
  await page.locator('#snippet-split').click();await page.waitForSelector('.clip-split-chip');await page.locator('.clip-split-title [data-action="split-return"]').click();assert.equal(await page.locator('#snippet-content').inputValue(),pickedWord,'Returning with no selection must keep the draft');
  await page.locator('#snippet-form button[type=submit]').click();assert.notEqual(await page.evaluate(()=>localStorage.getItem('voxfix-snippets-v1')),beforeDraft);assert(await page.locator('.clip-text').filter({hasText:pickedWord}).count());
  await page.locator('.clip-text').first().dispatchEvent('contextmenu');await page.locator('[data-action="context-edit"]').click();await page.locator('#snippet-content').fill('放弃的修改');await page.locator('#snippet-split').click();await page.waitForSelector('.clip-split-chip');await page.locator('.clip-split-chip').first().click();await page.locator('.clip-split-title [data-action="split-return"]').click();const beforeCancel=await page.evaluate(()=>localStorage.getItem('voxfix-snippets-v1'));await page.locator('#snippet-cancel').click();assert.equal(await page.evaluate(()=>localStorage.getItem('voxfix-snippets-v1')),beforeCancel);
  if(native){
   await page.evaluate(()=>voxfixClipboard('灯塔 路 街道 红灯 停 绿灯 行 学校 '.repeat(20),'drag-scroll'));await open();
   const a=await chips.first().boundingBox(),grid=await page.locator('.clip-split-grid').boundingBox();
   await page.mouse.move(a.x+a.width/2,a.y+a.height/2);await page.mouse.down();await page.mouse.move(a.x+a.width/2,grid.y+grid.height-3,{steps:5});
   await page.waitForTimeout(300);
   const top=await page.locator('.clip-split-grid').evaluate(n=>n.scrollTop);assert(top>0,'Dragging at the edge must scroll long text');
   assert(await page.locator('.clip-split-chip[aria-pressed="true"]').count()>5,'Edge scrolling must continue selecting words');
   await page.mouse.up();const stopped=await page.locator('.clip-split-grid').evaluate(n=>n.scrollTop);await page.waitForTimeout(80);assert.equal(await page.locator('.clip-split-grid').evaluate(n=>n.scrollTop),stopped,'Release must stop automatic scrolling');
   await page.locator('.clip-split-title [data-action="split-return"]').click();
  }
  assert.deepEqual(errors,[]);console.log('PASS '+(native?'native bridge':'browser')+' split drag, touch/cancel, shared draft and copy regressions');await page.close();
 }
}finally{await browser.close();await new Promise(r=>server.close(r));}
