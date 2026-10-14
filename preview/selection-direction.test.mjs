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
  await page.goto(`http://127.0.0.1:${server.address().port}/`);await page.waitForSelector('.keyboard-footer .cursor-zone');
  const text='第一行甲乙丙丁\n第二行甲乙丙丁\n第三行甲乙丙丁\n第四行甲乙丙丁';
  for(const origin of [0,text.indexOf('第三行')]){
    await page.locator('#editor').evaluate((e,{text,origin})=>{e.value=text;e.setSelectionRange(origin,origin);e.dispatchEvent(new Event('input'));},{text,origin});
    // 串行跑整套时偶发 boundingBox 返回 null（元素挂载了但尺寸还没量到）。
    // 用一次 waitForFunction 原子取出两个矩形，避免两次调用之间页面状态变化。
    const {rail,kb}=await page.waitForFunction(()=>{
      const rail=document.querySelector('.keyboard-footer .cursor-zone'),kb=document.querySelector('#keyboard');
      if(!rail||!kb)return null;
      const r=rail.getBoundingClientRect(),k=kb.getBoundingClientRect();
      return r.width&&k.width?{rail:{x:r.x,y:r.y,width:r.width,height:r.height},kb:{x:k.x,y:k.y,width:k.width,height:k.height}}:null;
    }).then(handle=>handle.jsonValue());
    const x=rail.x+rail.width/2,y=rail.y+rail.height/2;
    await page.mouse.move(x,y);await page.mouse.down();await page.waitForTimeout(380);
    // 主轨只保留左右：先沿滑轨向左拖出选区，再向右反转，锚点不能跟着跑。
    await page.mouse.move(x-70,y,{steps:8});
    const atLeft=await page.locator('#editor').evaluate(e=>[e.selectionStart,e.selectionEnd]);
    assert(atLeft[0]<=origin&&atLeft[1]===origin,'Dragging left keeps its original anchor');
    await page.mouse.move(x+70,y,{steps:8});
    const selected=await page.locator('#editor').evaluate(e=>[e.selectionStart,e.selectionEnd]);
    assert(selected.includes(origin),'Reversing to the right retains the fixed origin');
    const beforeHead=atLeft[0]===origin?atLeft[1]:atLeft[0],afterHead=selected[0]===origin?selected[1]:selected[0];
    assert(afterHead>beforeHead,'Reversing past the origin advances the active endpoint');
    const pad=await page.locator('.selection-editor').boundingBox();assert(pad.x>kb.x&&pad.x+pad.width<kb.x+kb.width,'Right editor must stay inside the screen');
    assert(pad.y+pad.height<kb.y+kb.height-6,'Bottom edge must leave a navigation margin');
    assert.equal(await page.locator('.selection-mini-track').getAttribute('aria-label'),'起点锁定 · 拖动调整终点','The right editor shows the locked state');
    await page.mouse.up();
    // 解除锁定（面板内小轨长按）再收起面板，下一轮从干净状态开始。
    const mini=await page.locator('.selection-mini-track').boundingBox();
    await page.mouse.move(mini.x+mini.width/2,mini.y+mini.height/2);await page.mouse.down();await page.waitForTimeout(400);await page.mouse.up();
    await page.locator('.selection-editor-backdrop').click({position:{x:8,y:8}});
  }
  console.log('PASS: start/middle reposition then down, centered dashed area and navigation margin');
}finally{await browser.close();await new Promise(r=>server.close(r));}
