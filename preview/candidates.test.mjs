import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
const { chromium } = createRequire(import.meta.url)('playwright');
const words=['您好','您好呀','您好朋友','您好很高兴认识你','您','你好','你好呀','你好朋友','你好世界','您好大家好','你好很高兴认识你','你好欢迎回来'];
const server=createServer(async(req,res)=>{
  try{
    const path=req.url==='/'?'index.html':req.url.slice(1);
    res.setHeader('Content-Type',path.endsWith('.mjs')?'text/javascript':path.endsWith('.css')?'text/css':path.endsWith('.json')?'application/json':'text/html');
    if(path==='pinyin-data.json')res.end(JSON.stringify({nin:words.map((word,i)=>[word,100-i])}));
    else if(path==='pinyin-initials.json')res.end('{}');
    else if(path==='phrase-packs.json')res.end('{}');
    else res.end(await readFile(new URL(path==='bundle.html'?'../键盘试用页.html':path,import.meta.url)));
  }catch{res.writeHead(404);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({channel:'msedge',headless:true});
try{
  const page=await browser.newPage({viewport:{width:1000,height:1000},hasTouch:true});
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(()=>document.querySelector('#pinyin-status').textContent.includes('已就绪'));
  await page.locator('#editor').pressSequentially('nin');
  const metrics=await page.locator('.candidate-visible').evaluate(row=>({
    words:row.children.length,scrollWidth:row.scrollWidth,width:row.clientWidth,
    buttons:[...row.children].map(b=>({text:b.textContent,width:b.clientWidth,content:b.scrollWidth,ellipsis:getComputedStyle(b).textOverflow}))
  }));
  assert(metrics.buttons.every(b=>b.ellipsis!=='ellipsis'&&b.width>=b.content),'candidate text must be fully displayed');
  assert.equal(metrics.words,words.length,'all available candidates must be reachable in the strip');
  assert(metrics.scrollWidth>metrics.width,'long candidate strip must scroll');
  const row=page.locator('.candidate-visible'),box=await row.boundingBox();
  await page.mouse.move(box.x+box.width-20,box.y+box.height/2);
  await page.mouse.down();
  await page.mouse.move(box.x+20,box.y+box.height/2,{steps:12});
  await page.mouse.up();
  assert(await row.evaluate(el=>el.scrollLeft)>100,'left drag must reveal later candidates');
  assert.equal(await page.locator('#editor').inputValue(),'nin','drag must not commit a candidate');
  const scroll=await row.evaluate(el=>el.scrollLeft);
  await page.locator('.candidate-expand').click();
  assert.equal(await row.evaluate(el=>el.scrollLeft),scroll,'opening expanded candidates preserves scroll');
  assert(await page.locator('.candidate-grid button').evaluateAll(buttons=>buttons.every(b=>b.scrollWidth<=b.clientWidth)),'expanded candidates must fit their content');
  await page.locator('.candidate-expand').click();
  await row.evaluate(el=>el.scrollLeft=0);
  const cdp=await page.context().newCDPSession(page);
  const x=box.x+box.width-20,y=box.y+box.height/2;
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y}]});
  for(let i=1;i<=12;i++){
    await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:x-(box.width-40)*i/12,y}]});
    await page.waitForTimeout(20);
  }
  await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  await page.waitForTimeout(200);
  assert(await row.evaluate(el=>el.scrollLeft)>100,'native touch swipe must reveal later candidates');
  assert.equal(await page.locator('#editor').inputValue(),'nin','touch swipe must not commit a candidate');
  await row.evaluate(el=>el.scrollLeft=el.scrollWidth);
  await row.locator('button').last().click();
  assert.equal(await page.locator('#editor').inputValue(),words.at(-1),'later candidate remains selectable');
  for(const width of [320,360])for(const count of [3,9]){
    await page.evaluate(({count})=>localStorage.setItem('voxfix-layout-v1',JSON.stringify({candidateCount:count,candidateFont:22})),{count});
    await page.reload();
    await page.waitForFunction(()=>document.querySelector('#pinyin-status').textContent.includes('已就绪'));
    await page.locator('#phone').evaluate((el,width)=>el.style.width=width+'px',width);
    await page.locator('#editor').pressSequentially('nin');
    assert.equal(await row.evaluate(el=>getComputedStyle(el).getPropertyValue('--candidate-count')),'3','Imported density cannot change the fixed layout');
    assert(await row.locator('button').evaluateAll(buttons=>buttons.every(b=>b.clientWidth>=b.scrollWidth)),'text fits at narrow widths and largest font');
    await row.evaluate(el=>el.scrollLeft=el.scrollWidth);
    await page.locator('#editor').pressSequentially('h');
    assert.equal(await row.evaluate(el=>el.scrollLeft),0,'new composition resets candidate strip');
  }
  await page.evaluate(()=>localStorage.clear());
  await page.goto(`http://127.0.0.1:${server.address().port}/bundle.html`);
  await page.waitForFunction(()=>document.querySelector('#pinyin-status').textContent.includes('已就绪'));
  await page.locator('#editor').pressSequentially('nin');
  assert(await row.locator('button').count()>5,'bundled version includes later candidates');
  assert(await row.locator('button').evaluateAll(buttons=>buttons.every(b=>b.clientWidth>=b.scrollWidth)),'bundled candidate text fits');
  await page.locator('#editor').fill('');
  await page.locator('#editor').pressSequentially('srf');
  await page.locator('#phone').screenshot({path:new URL('../docs/candidate-scroll.png',import.meta.url).pathname.slice(1)});
  console.log('PASS: full text, mouse drag, native touch swipe, no accidental selection, expanded layout, 320/360px widths, fixed density/font, query reset and bundled version');
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
