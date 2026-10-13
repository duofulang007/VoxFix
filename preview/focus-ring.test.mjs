// 键盘内不得出现浏览器焦点框。
// 依据 docs/键盘焦点框-AndroidWebView.md：Android WebView 在窗口焦点失而复得时会把 DOM 焦点
// 丢给文档里第一个可聚焦元素（工具栏第一个按钮），浏览器随即按 button:focus-visible 画出 2px 外框，
// 真机表现为「菜单栏莫名被选中」。这里用 CDP 强制 :focus-visible，把结论钉死在样式层。
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
const {chromium}=createRequire(import.meta.url)('playwright');
const server=createServer(async(req,res)=>{
  const path=req.url==='/'?'index.html':req.url.slice(1);
  if(!/^[a-zA-Z0-9.-]+$/.test(path)){res.writeHead(404);res.end();return;}
  try{res.setHeader('Content-Type',path.endsWith('.mjs')?'text/javascript':path.endsWith('.css')?'text/css':path.endsWith('.json')?'application/json':'text/html');res.end(await readFile(new URL(path,import.meta.url)));}
  catch{res.writeHead(404);res.end();}
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({channel:'msedge',headless:true});
try{
  const page=await browser.newPage({viewport:{width:1200,height:1000}});
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForSelector('.cursor-zone');
  const client=await page.context().newCDPSession(page);
  await client.send('DOM.enable');await client.send('CSS.enable');
  const {root}=await client.send('DOM.getDocument');
  // 强制 :focus-visible 后读计算样式：不依赖浏览器自身的焦点可见性启发式。
  const outlineOf=async selector=>{
    const {nodeId}=await client.send('DOM.querySelector',{nodeId:root.nodeId,selector});
    assert(nodeId,`找不到 ${selector}`);
    await client.send('CSS.forcePseudoState',{nodeId,forcedPseudoClasses:['focus-visible']});
    return page.locator(selector).first().evaluate(node=>getComputedStyle(node).outlineStyle);
  };
  // 对照：键盘外的设置按钮必须保留焦点框，证明强制伪类确实生效（否则下面的断言是空转的）。
  assert.equal(await outlineOf('.control-tabs button'),'solid','设置区按钮的 :focus-visible 焦点框必须保留');
  // 键盘内：从工具栏图标钮到字母键、滑轨，一个都不许画出焦点框。
  for(const selector of ['#keyboard .toolbar button','#keyboard .key','#keyboard .key.function','#keyboard .cursor-zone']){
    assert.equal(await outlineOf(selector),'none',`${selector} 在键盘内不得出现浏览器焦点框`);
  }
  // 真实路径：程序化把焦点交给工具栏第一个按钮（Android WebView 就是这么干的），计算样式仍无轮廓。
  const focused=await page.evaluate(()=>{
    const target=document.querySelector('#keyboard .toolbar button');
    target.focus();
    return {active:document.activeElement===target,outline:getComputedStyle(target).outlineStyle,width:getComputedStyle(target).outlineWidth};
  });
  assert.equal(focused.active,true,'工具栏第一个按钮必须是可聚焦元素（真机上 WebView 正是把焦点丢给它）');
  assert.equal(focused.outline,'none',`工具栏按钮获得 DOM 焦点后仍不得画外框，实测 ${focused.width}`);
  // 出候选后候选条（tabindex=0）同样不许有框。
  await page.locator('#keyboard [data-action="letter"][data-value="N"]').click();
  assert.equal(await outlineOf('#keyboard .candidate-visible'),'none','候选条不得出现浏览器焦点框');
  console.log('PASS: 键盘内不绘制浏览器焦点框，设置区焦点框保留');
}finally{await browser.close();await new Promise(r=>server.close(r));}
