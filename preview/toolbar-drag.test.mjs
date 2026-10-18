// 工具栏排序（2026-10-18 改）：长按图标直接抬起拖动、松手落位。
// 上一版是「长按进入排序模式」——候选栏位置会多出一行「拖动图标排序 / 完成 / 长按自定义项无法移除」，
// 挡住候选区视野。这版回归三件事：①拖动期间不出现任何额外行（工具栏顶边与字母键高度不变）；
// ②松手即定序并落盘；③轻点仍然触发原功能，长按不动既不换位也不触发原功能。
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
const HOLD_MS=420;   // 触发阈值 380ms，留 40ms 余量
async function check(name,run){
  const page=await browser.newPage({viewport:{width:393,height:351},hasTouch:true}),errors=[];
  page.setDefaultTimeout(4000);page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(()=>{
    window.calls=[];
    window.VoxFixIme=Object.fromEntries(['compose','commit','completeEnglish','copy','delete','enter','hide','switcher','startVoice','startLocalVoice','stopVoice','cancelVoice','moveCursor','cursorPosition','gestureStart','gestureMove','gestureEnd','haptic','edit','ready','resize','refreshClipboard','importClipboard','clipHint'].map(name=>[name,(...args)=>window.calls.push([name,...args])]));
    VoxFixIme.voiceReady=()=>true;VoxFixIme.pinyinReady=()=>true;
    VoxFixIme.pinyinCandidates=()=>JSON.stringify({words:['你好','你','好']});
    VoxFixIme.pinyinChoose=()=>JSON.stringify({committed:true,remaining:''});
    VoxFixIme.commitAccepted=()=>JSON.stringify({committed:true});
  });
  const touch=await page.context().newCDPSession(page);
  const send=(type,x,y)=>touch.send('Input.dispatchTouchEvent',{type,touchPoints:type==='touchStart'||type==='touchMove'?[{x,y}]:[]});
  const order=()=>page.evaluate(()=>[...document.querySelectorAll('#keyboard .toolbar [data-action]')].map(node=>node.dataset.action));
  const boxOf=action=>page.locator(`#keyboard .toolbar [data-action="${action}"]`).boundingBox();
  const center=async action=>{const box=await boxOf(action);return {x:box.x+box.width/2,y:box.y+box.height/2};};
  const layout=()=>page.evaluate(()=>{
    const toolbar=document.querySelector('#keyboard .toolbar').getBoundingClientRect();
    const rows=document.querySelector('#keyboard .key-rows').getBoundingClientRect();
    return {toolbarTop:toolbar.top,toolbarHeight:toolbar.height,rowsTop:rows.top,rowsHeight:rows.height};
  });
  const dragClasses=()=>page.evaluate(()=>({
    lifted:[...document.querySelectorAll('#keyboard .toolbar-drag')].map(node=>node.dataset.action),
    targets:[...document.querySelectorAll('#keyboard .toolbar .drop-target')].map(node=>node.dataset.action),
    removes:[...document.querySelectorAll('#keyboard .toolbar .drop-remove')].map(node=>node.dataset.action),
    extraRows:document.querySelectorAll('#keyboard .candidate-bar').length,
  }));
  try{
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForFunction(()=>typeof voxfixReset==='function'&&document.querySelector('#keyboard .toolbar [data-action="menu"]'));
    await run({page,send,order,center,layout,dragClasses,HOLD_MS,boxOf});
    assert.deepEqual(errors,[]);
    console.log('PASS '+name);
  }catch(error){failures.push(name+': '+error.message);console.log('FAIL '+name+': '+error.message.slice(0,300));}
  finally{try{await page.close();}catch{}}
}
try{
  await check('长按图标直接拖动换位：全程不出现提示行，松手落位并落盘',async({page,send,order,center,layout,dragClasses,HOLD_MS})=>{
    const base=await order();
    assert.deepEqual(base,['menu','layouts','night','clipboard','emoji','hide'],'默认工具栏顺序不对：'+base.join(','));
    const before=await layout();
    const night=await center('night'),menu=await center('menu');
    await send('touchStart',night.x,night.y);
    await page.waitForTimeout(HOLD_MS);
    // 抬起：只有被按住的那块图标有状态，没有任何额外行。
    assert.deepEqual(await dragClasses(),{lifted:['night'],targets:[],removes:[],extraRows:0},'长按抬起时不得铺提示行，抬起态只落在被按住的图标上');
    assert.deepEqual(await layout(),before,'拖动中工具栏与字母键位置不得变化（多一行会把它们推下去）');
    await send('touchMove',menu.x,menu.y);await page.waitForTimeout(40);
    const hover=await dragClasses();
    assert.deepEqual(hover.lifted,['night'],'拖动中被按住项应保持抬起态');
    assert.deepEqual(hover.targets,['menu'],'落位目标应高亮为 menu，实得 '+hover.targets.join(','));
    assert.equal(hover.extraRows,0,'拖动中不得出现提示行');
    await send('touchEnd',menu.x,menu.y);await page.waitForTimeout(80);
    assert.deepEqual(await order(),['night','menu','layouts','clipboard','emoji','hide'],'松手后顺序不对');
    assert.deepEqual(await dragClasses(),{lifted:[],targets:[],removes:[],extraRows:0},'松手后不得残留拖动状态');
    assert.deepEqual(await layout(),before,'落位后布局应回到原样');
    // 落盘：刷新页面顺序还在。
    await page.reload();
    await page.waitForFunction(()=>typeof voxfixReset==='function'&&document.querySelector('#keyboard .toolbar [data-action="menu"]'));
    assert.deepEqual(await order(),['night','menu','layouts','clipboard','emoji','hide'],'顺序没有持久化');
    await page.evaluate(()=>voxfixReset());
  });
  await check('轻点仍触发原功能；长按不动既不换位也不触发',async({page,send,order,center,HOLD_MS})=>{
    const base=await order();
    const menu=await center('menu');
    await send('touchStart',menu.x,menu.y);await page.waitForTimeout(120);await send('touchEnd',menu.x,menu.y);
    await page.waitForTimeout(120);
    assert.equal(await page.locator('#keyboard .menu-grid').count(),1,'轻点功能菜单应照旧打开');
    await page.evaluate(()=>voxfixReset());
    await page.waitForTimeout(60);
    assert.deepEqual(await order(),base,'重置后顺序应回到默认');
    const night=await center('night');
    await send('touchStart',night.x,night.y);await page.waitForTimeout(HOLD_MS);await send('touchEnd',night.x,night.y);
    await page.waitForTimeout(120);
    assert.equal(await page.locator('#keyboard .menu-grid').count(),0,'长按抬起后松手不应触发原功能');
    assert.equal(await page.locator('#keyboard .candidate-grid').count(),0,'长按抬起后松手不应跳到其它页面');
    assert.deepEqual(await order(),base,'长按不动不应改变顺序');
    assert.equal(await page.locator('#keyboard .toolbar-drag').count(),0,'长按松手后不得残留抬起态');
  });
}finally{
  await browser.close();server.close();
}
if(failures.length){console.log('\n'+failures.length+' 项失败');process.exitCode=1;}else console.log('\n全部通过');
