// 备份恢复的「待应用镜像」：page 顶层与运行中推送（原生 updateClipboard → voxfixApplyPendingMirror）
// 必须都真正落到键盘上。
// 背景：2026-11-15 用户卸载重装后从文件恢复备份，截图工具栏顺序没回来；排查发现文件选择器路径
// 不触发冷启动只是其中一半，前端运行中推送此前也只合并 snippets，layout/keyboard 被整段忽略。
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
async function check(name,init,run){
  const page=await browser.newPage({viewport:{width:393,height:351},hasTouch:true}),errors=[];
  page.setDefaultTimeout(4000);page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(bridge);
  if(init) await page.addInitScript(init);
  const order=()=>page.evaluate(()=>[...document.querySelectorAll('#keyboard .toolbar [data-action]')].map(node=>node.dataset.action));
  const stored=()=>page.evaluate(()=>{try{return JSON.parse(localStorage.getItem('voxfix-layout-v1')||'null');}catch{return null;}});
  try{
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForFunction(()=>typeof voxfixReset==='function'&&document.querySelector('#keyboard .toolbar [data-action="menu"]'));
    await run({page,order,stored});
    assert.deepEqual(errors,[]);
    console.log('PASS '+name);
  }catch(error){failures.push(name+': '+error.message);console.log('FAIL '+name+': '+error.message.slice(0,300));}
  finally{try{await page.close();}catch{}}
}
const bridge=()=>{
  window.calls=[];
  window.VoxFixIme=Object.fromEntries(['compose','commit','completeEnglish','copy','delete','enter','hide','switcher','startVoice','startLocalVoice','stopVoice','cancelVoice','moveCursor','cursorPosition','gestureStart','gestureMove','gestureEnd','haptic','edit','ready','resize','refreshClipboard','importClipboard','clipHint'].map(name=>[name,(...args)=>window.calls.push([name,...args])]));
  VoxFixIme.voiceReady=()=>true;VoxFixIme.pinyinReady=()=>true;
  VoxFixIme.pinyinCandidates=()=>JSON.stringify({words:['你好','你','好']});
  VoxFixIme.pinyinChoose=()=>JSON.stringify({committed:true,remaining:''});
  VoxFixIme.commitAccepted=()=>JSON.stringify({committed:true});
};
try{
  await check('启动时取到待应用镜像：工具栏顺序与 9 键布局直接生效',function(){
    VoxFixIme.takePendingMirror=()=>JSON.stringify({
      layout:{toolbar:['night','clipboard','menu']},
      keyboard:{layout:'9',language:'en',night:false}
    });
  },async({page,order,stored})=>{
    assert.deepEqual(await order(),['night','clipboard','menu'],'工具栏顺序没有按备份恢复');
    assert.equal(await page.locator('#keyboard .t9-grid').count(),1,'备份里的 9 键布局没有生效');
    const saved=await stored();
    assert.deepEqual(saved.toolbar,['night','clipboard','menu'],'恢复后的顺序没有写回本地设置');
  });
  await check('运行中推送镜像：layout 与 keyboard 都必须应用，而不是只合并 snippets',null,async({page,order,stored})=>{
    assert.deepEqual(await order(),['menu','layouts','night','clipboard','emoji','hide'],'初始顺序应为默认值');
    await page.evaluate(()=>globalThis.voxfixApplyPendingMirror(JSON.stringify({
      layout:{toolbar:['emoji','hide','menu']},
      keyboard:{layout:'9',language:'en',night:true}
    })));
    assert.deepEqual(await order(),['emoji','hide','menu'],'运行中恢复的工具栏顺序没有应用');
    assert.equal(await page.locator('#keyboard .t9-grid').count(),1,'运行中恢复的 9 键布局没有应用');
    const saved=await stored();
    assert.deepEqual(saved.toolbar,['emoji','hide','menu'],'运行中恢复的顺序没有写回本地设置');
  });
  await check('坏布局不改动当前键盘',null,async({page,order,stored})=>{
    const before=await order(),beforeSaved=await stored();
    await page.evaluate(()=>globalThis.voxfixApplyPendingMirror(JSON.stringify({layout:{toolbar:['no-such-tool']}})));
    assert.deepEqual(await order(),before,'坏备份不应改变工具栏');
    assert.deepEqual(await stored(),beforeSaved,'坏备份不应覆盖本地设置');
  });
}finally{
  await browser.close();server.close();
}
if(failures.length){console.log('\n'+failures.length+' 项失败');process.exitCode=1;}else console.log('\n全部通过');
