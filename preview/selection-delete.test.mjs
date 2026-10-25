// 选字编辑面板：左侧四个按钮文字换图标、顶部右侧新增删除钮。
// 删除必须复用主键盘删除键的完整逻辑（选区优先、否则删光标前一字、长按同一节奏连删），
// 且删除后面板保持打开、计数与按钮状态同步；松手/取消/失焦/收起面板都要立刻停手。
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
const url=`http://127.0.0.1:${server.address().port}/`;
try{
  // ── 原生宿主（桥接打桩）：入口、长按连删、四类停止条件 ─────────────────────
  const errors=[];
  const native=await browser.newPage({viewport:{width:393,height:351}});
  native.on('pageerror',e=>errors.push(e.message));
  await native.addInitScript(()=>{window.calls=[];window.VoxFixIme=Object.fromEntries(['compose','commit','completeEnglish','copy','delete','enter','hide','switcher','startVoice','startLocalVoice','stopVoice','cancelVoice','moveCursor','cursorPosition','gestureStart','gestureLock','gestureUnlock','gestureMove','gestureMove2D','gestureEnd','haptic','selectionStep','edit','ready','resize','refreshClipboard','importClipboard'].map(n=>[n,(...a)=>calls.push([n,...a])]));VoxFixIme.voiceReady=()=>true;});
  await native.goto(url);await native.waitForFunction(()=>typeof voxfixSelectionOrigin==='function');
  const openPanel=()=>native.evaluate(()=>{voxfixSelectionOrigin();voxfixSelection(5,15,10);});
  const deletes=()=>native.evaluate(()=>calls.filter(row=>row[0]==='delete').length);
  const deleteBox=async()=>{const r=await native.locator('.selection-delete').boundingBox();assert(r,'删除钮必须存在');return r;};
  const holdDelete=async ms=>{const r=await deleteBox();await native.mouse.move(r.x+r.width/2,r.y+r.height/2);await native.mouse.down();await native.waitForTimeout(ms);};
  await openPanel();

  // 点按一次只删一次（走的是主键盘同一套 pointerdown 立即删 + 点击被消费），面板不收起。
  const before=await deletes();
  await native.locator('.selection-delete').click();
  await native.waitForTimeout(80);
  assert.equal(await deletes(),before+1,'面板删除钮点按一次只调用一次原生删除');
  assert.equal(await native.locator('.selection-editor').count(),1,'删除后选字面板保持打开');
  await native.evaluate(()=>voxfixSelection(5,5,0));
  assert.equal(await native.locator('.selection-count').innerText(),'已选 0 字','删除后计数同步');
  assert.equal(await native.locator('.selection-editor [data-action="copy-text"]').isDisabled(),true,'空选区禁用复制');
  assert.equal(await native.locator('.selection-editor [data-action="cut-text"]').isDisabled(),true,'空选区禁用剪切');
  assert.equal(await native.locator('.selection-delete').isDisabled(),false,'已选 0 字时删除钮仍可用');
  assert.equal(await native.locator('.selection-mini-track.locked').count(),0,'删除后起点锁定随之失效');

  // 面板里的按钮不得重建键盘页内容：以前每次 render 都整块重写 .keyboard 的 innerHTML，
  // 几十个键节点连同 .kb-recede 的亮度滤镜层要一起重新栅格化，用户看到的就是「背景字母闪一下」。
  await openPanel();
  const keyCount=await native.evaluate(()=>{document.querySelectorAll('#keyboard .key').forEach((node,index)=>{node.dataset.repaintProbe=String(index);});return document.querySelectorAll('#keyboard .key').length;});
  assert(keyCount>0,'键盘键位必须存在');
  const keysSurvived=()=>native.evaluate(count=>document.querySelectorAll('#keyboard .key[data-repaint-probe]').length===count,keyCount);
  await native.locator('.selection-delete').click();
  assert(await keysSurvived(),'面板删除不得重建键盘键位');
  await native.locator('.selection-direction[data-direction="1,1"]').click();
  assert(await keysSurvived(),'方向键不得重建键盘键位');
  await native.locator('.selection-editor [data-action="select-all"]').click();
  assert(await keysSurvived(),'全选不得重建键盘键位');
  await native.locator('.selection-editor [data-action="paste-text"]').click();
  assert(await keysSurvived(),'粘贴不得重建键盘键位');

  // 长按按主键盘节奏连删；重绘不得中断，也不得重复触发。
  await openPanel();
  const longBefore=await deletes();
  await holdDelete(1200);
  const held=await deletes();
  assert(held-longBefore>=6,`长按应连续删除，实际 ${held-longBefore} 次`);
  assert.equal(await native.locator('.selection-delete.pressed').count(),1,'连删重绘后按压态保留');
  assert.equal(await native.locator('.selection-editor').count(),1,'连删期间面板保持打开');
  await native.mouse.up();
  await native.waitForTimeout(80);
  const afterRelease=await deletes();
  assert.equal(await native.locator('.selection-delete.pressed').count(),0,'松手清掉按压态');
  await native.waitForTimeout(450);
  assert.equal(await deletes(),afterRelease,'松手后必须立即停止连删');

  // 指针取消。
  await openPanel();
  await holdDelete(260);
  const during=await deletes();
  await native.locator('#keyboard').evaluate(e=>e.dispatchEvent(new PointerEvent('pointercancel',{pointerId:1,bubbles:true})));
  await native.waitForTimeout(450);
  assert.equal(await deletes(),during,'指针取消后必须停止连删');
  await native.mouse.up();

  // 失焦。
  await openPanel();
  await holdDelete(260);
  const focused=await deletes();
  await native.evaluate(()=>window.dispatchEvent(new Event('blur')));
  await native.waitForTimeout(450);
  assert.equal(await deletes(),focused,'失焦后必须停止连删');
  await native.mouse.up();

  // 指针捕获丢失。
  await openPanel();
  await holdDelete(260);
  const captured=await deletes();
  await native.locator('#keyboard').evaluate(e=>e.dispatchEvent(new PointerEvent('lostpointercapture',{pointerId:1,bubbles:true})));
  await native.waitForTimeout(450);
  assert.equal(await deletes(),captured,'指针捕获丢失后必须停止连删');
  await native.mouse.up();

  // 按住期间面板被收起（点框外）：立刻停手。
  await openPanel();
  await holdDelete(500);
  const closed=await deletes();
  await native.locator('.selection-editor-backdrop').dispatchEvent('click');
  assert.equal(await native.locator('.selection-editor').count(),0,'点框外收起面板');
  await native.waitForTimeout(500);
  assert.equal(await deletes(),closed,'面板收起后必须停止连删');
  await native.mouse.up();

  // 主键盘删除键与面板入口一致性：同为 data-action=delete、同为一次一删。
  assert.equal(await native.locator('.selection-delete').count(),0,'面板收起后只剩主键盘那一个删除入口');
  const mainBefore=await deletes();
  await native.locator('#keyboard [data-action="delete"]').click();
  await native.waitForTimeout(80);
  assert.equal(await deletes(),mainBefore+1,'主键盘删除键同样一次一删');

  // 删除钮尺寸/位置：收在页头 32px 内、贴面板右侧内边距、不与下方按钮重叠。
  await openPanel();
  const panel=await native.locator('.selection-editor').boundingBox();
  const header=await native.locator('.selection-editor-header').boundingBox();
  const del=await deleteBox();
  assert.equal(Math.round(header.height),32,'页头高度不变');
  assert(del.y>=header.y-0.5&&del.y+del.height<=header.y+header.height+0.5,'删除钮完整落在页头内，不撑高标题栏');
  assert(del.x>=header.x+header.width/2,'删除钮在页头右侧');
  assert(Math.abs(del.x+del.width-(panel.x+panel.width-8))<=0.5,'删除钮右缘贴面板内边距');
  const bodyButtons=await native.locator('.selection-editor-body button').evaluateAll(nodes=>nodes.map(node=>{const r=node.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height};}));
  for(const box of bodyButtons)assert(!(del.x<box.x+box.width&&del.x+del.width>box.x&&del.y<box.y+box.height&&del.y+del.height>box.y),'删除钮不与下方按钮重叠');
  // 左侧四个按钮：文字换图标、中文名与命中区不变。
  const rows=await native.locator('.selection-editor-clipboard button').evaluateAll(nodes=>nodes.map(node=>{const r=node.getBoundingClientRect();const icon=node.querySelector('svg')?.getBoundingClientRect();return {action:node.dataset.action,label:node.getAttribute('aria-label'),text:node.textContent.trim(),box:{x:r.x,y:r.y,width:r.width,height:r.height},icon:icon?{x:icon.x,y:icon.y,width:icon.width,height:icon.height}:null};}));
  assert.deepEqual(rows.map(row=>row.action),['select-all','copy-text','cut-text','paste-text']);
  assert.deepEqual(rows.map(row=>row.label),['全选','复制','剪切','粘贴'],'无障碍名称保留中文');
  for(const row of rows){
    assert.equal(row.text,'','按钮上不再有文字');
    assert(row.icon,'按钮里是 SVG 图标');
    assert.equal(row.box.width,76);assert.equal(row.box.height,48);
    assert.equal(Math.round(row.icon.x+row.icon.width/2-(row.box.x+row.box.width/2)),0,`${row.label}图标水平居中`);
    assert.equal(Math.round(row.icon.y+row.icon.height/2-(row.box.y+row.box.height/2)),0,`${row.label}图标垂直居中`);
    assert(row.icon.x>=row.box.x&&row.icon.x+row.icon.width<=row.box.x+row.box.width,'图标不裁切');
    assert(row.icon.y>=row.box.y&&row.icon.y+row.icon.height<=row.box.y+row.box.height,'图标不裁切');
  }

  // ── 共享编辑框（剪贴板/候选引用共用的草稿框）：删除只动草稿，面板保持打开 ─────
  const shared=await browser.newPage({viewport:{width:393,height:479}});
  shared.on('pageerror',e=>errors.push(e.message));
  await shared.addInitScript(()=>{window.calls=[];window.VoxFixIme=Object.fromEntries(['compose','commit','completeEnglish','copy','delete','enter','hide','switcher','startVoice','startLocalVoice','stopVoice','cancelVoice','moveCursor','cursorPosition','gestureStart','gestureLock','gestureUnlock','gestureMove','gestureMove2D','gestureEnd','haptic','selectionStep','edit','ready','resize','refreshClipboard','importClipboard'].map(n=>[n,(...a)=>calls.push([n,...a])]));VoxFixIme.voiceReady=()=>true;});
  await shared.goto(url);await shared.waitForFunction(()=>typeof voxfixClipboard==='function');
  await shared.evaluate(()=>voxfixClipboard('共享编辑框草稿'));
  await shared.locator('#keyboard [data-action="clipboard"]').first().click();
  await shared.locator('.clip-text').first().dispatchEvent('contextmenu');
  await shared.locator('[data-action="context-edit"]').click();
  const draft=shared.locator('#snippet-content');
  await draft.waitFor({state:'visible'});
  const draftBefore=await draft.inputValue();
  const draftBox=await draft.boundingBox();
  await shared.mouse.move(draftBox.x+draftBox.width-14,draftBox.y+draftBox.height/2);
  await shared.mouse.down();await shared.waitForTimeout(400);await shared.mouse.up();
  assert.equal(await shared.locator('.selection-editor').count(),1,'长按共享编辑框呼出同一块选字面板');
  const draftCaret=await draft.evaluate(node=>node.selectionStart);
  await shared.locator('.selection-delete').click();
  await shared.waitForTimeout(60);
  assert.equal(await draft.inputValue(),draftBefore.slice(0,draftCaret-1)+draftBefore.slice(draftCaret),'共享编辑框里删除只动草稿');
  assert.equal(await shared.locator('.selection-editor').count(),1,'共享编辑框里删除后面板仍打开');
  assert.equal(await shared.evaluate(()=>calls.filter(c=>c[0]==='delete').length),0,'共享编辑框删除不走原生宿主删除');
  const backdrop=await shared.locator('.selection-editor-backdrop').boundingBox();
  await shared.mouse.click(backdrop.x+6,backdrop.y+backdrop.height-6);
  assert.equal(await shared.locator('.selection-editor').count(),0,'点框外收起面板');
  const cancel=await shared.locator('#snippet-cancel').boundingBox();
  await shared.mouse.click(cancel.x+cancel.width/2,cancel.y+cancel.height/2);
  assert.equal(await shared.locator('#ime-local-editor').isVisible(),false,'取消后共享编辑框关闭');

  // ── 网页（本地宿主 textarea）：真的删掉选区 / 真的删掉光标前一字 ─────────────
  const page=await browser.newPage({viewport:{width:1200,height:1000}});
  page.on('pageerror',e=>errors.push(e.message));
  await page.goto(url);await page.waitForFunction(()=>document.querySelector('.keyboard-footer .cursor-zone'));
  const text='abcdefghijkl\nmnopqrstuvwx\nyz0123456789';
  await page.locator('#editor').evaluate((node,value)=>{node.value=value;node.setSelectionRange(4,4);node.dispatchEvent(new Event('input'));},text);
  const holdRail=async selector=>{const r=await page.locator(selector).boundingBox();const p={x:r.x+r.width/2,y:r.y+r.height/2};await page.mouse.move(p.x,p.y);await page.mouse.down();await page.waitForTimeout(390);await page.mouse.up();};
  await holdRail('.keyboard-footer .cursor-zone');
  assert.equal(await page.locator('.selection-editor').count(),1,'长按主滑轨呼出面板');
  await page.locator('.selection-direction[data-direction="0,1"]').click();
  const selected=Number((await page.locator('.selection-count').innerText()).match(/\d+/)[0]);
  assert(selected>0,'先选出一段文字');
  assert.equal(await page.locator('.selection-delete').isDisabled(),false);
  const geometryBefore=await page.locator('#keyboard').evaluate(node=>({height:node.offsetHeight,keys:[...node.querySelectorAll('.key-row .key')].map(k=>{const r=k.getBoundingClientRect();return [r.x,r.y,r.width,r.height];})}));
  await page.locator('.selection-delete').click();
  const value=await page.locator('#editor').inputValue();
  assert.equal(value.length,text.length-selected,'有选区时删除所选文字');
  assert.equal(await page.locator('.selection-editor').count(),1,'网页侧删除后面板同样保持打开');
  assert.equal(await page.locator('.selection-count').innerText(),'已选 0 字');
  assert.equal(await page.locator('.selection-editor [data-action="copy-text"]').isDisabled(),true);
  assert.equal(await page.locator('.selection-editor [data-action="cut-text"]').isDisabled(),true);
  assert.deepEqual(await page.locator('#keyboard').evaluate(node=>({height:node.offsetHeight,keys:[...node.querySelectorAll('.key-row .key')].map(k=>{const r=k.getBoundingClientRect();return [r.x,r.y,r.width,r.height];})})),geometryBefore,'26 键几何不受面板与删除影响');
  // 选区已删空 → 光标收起、无选区，此时删除钮仍可用，删掉光标前一字。
  const caretText=await page.locator('#editor').inputValue();
  const caret=await page.locator('#editor').evaluate(node=>node.selectionStart);
  await page.locator('.selection-delete').click();
  const afterCaret=await page.locator('#editor').inputValue();
  assert.equal(afterCaret.length,caretText.length-1,'无选区时删除光标前一字');
  assert.equal(afterCaret,caretText.slice(0,caret-1)+caretText.slice(caret),'删掉的是光标前那一字');
  assert.equal(await page.locator('.selection-editor').count(),1,'无选区删除后面板仍打开');
  // 拼音组合中的删除：面板入口与主键盘入口共用 deleteText，组合里同样是删掉最后一个字母。
  await page.reload();await page.waitForFunction(()=>document.querySelector('.keyboard-footer .cursor-zone'));
  await page.locator('#editor').evaluate(node=>{node.value='';node.setSelectionRange(0,0);node.dispatchEvent(new Event('input'));});
  for(const letter of ['N','I'])await page.locator(`#keyboard [data-action="letter"][data-value="${letter}"]`).click();
  await page.waitForTimeout(80);
  assert.equal(await page.locator('#editor').inputValue(),'ni','拼音组合已开始');
  assert.equal(await page.locator('#keyboard [data-action="delete"]').count(),1,'面板未打开时只剩主键盘删除键');
  await page.locator('#keyboard [data-action="delete"]').click();
  await page.waitForTimeout(80);
  assert.equal(await page.locator('#editor').inputValue(),'n','组合中删除删掉组合里最后一个字母');
  assert.deepEqual(errors,[]);
  console.log('PASS: 面板删除钮点按/长按连删、四类停止+收起停止、面板保持打开、计数与禁用态同步、面板操作不重建键位、图标居中不裁切、页头与 26 键几何不变、原生宿主/共享编辑框/网页宿主一致、主键盘删除入口一致');
}finally{await browser.close();await new Promise(r=>server.close(r));}
