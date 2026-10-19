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
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({channel:'msedge',headless:true});
try{
  for(const delayed of ['app.mjs','core.mjs']){
    const page=await browser.newPage({viewport:{width:393,height:351}});
    await page.addInitScript(()=>{window.VoxFixIme={ready(){window.keyboardReady=true;},resize(){}};});
    let release;
    const blocked=new Promise(resolve=>{release=resolve;});
    await page.route(`**/${delayed}`,async route=>{await blocked;await route.continue();});
    try{
      await page.goto(`http://127.0.0.1:${server.address().port}/`,{waitUntil:'commit'});
      await page.waitForSelector('.page-header',{state:'attached'});
      await page.waitForFunction(()=>[...document.styleSheets].some(sheet=>sheet.href?.endsWith('native-ime.css')));
      // 等两次绘制，确保断言覆盖模块尚未执行时实际能显示的页面。
      await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
      for(const selector of ['.page-header','.controls','.host-header','.host-body','.composer']){
        assert.equal(await page.locator(selector).isVisible(),false,`${delayed} 加载期间闪出了 ${selector}`);
      }
    }finally{release();}
    await page.waitForFunction(()=>window.keyboardReady===true);
    assert(await page.locator('#keyboard .key').first().isVisible(),'加载完成后键盘可见');
    assert.equal(await page.locator('#keyboard').evaluate(node=>Math.round(node.getBoundingClientRect().height)),351);
    await page.close();
  }
  const desktop=await browser.newPage();
  await desktop.goto(`http://127.0.0.1:${server.address().port}/`);
  assert(await desktop.locator('.page-header').isVisible(),'电脑试用页仍可见');
  assert(await desktop.locator('.controls').isVisible(),'电脑试用设置仍可见');
  console.log('PASS: IME 在入口及依赖延迟时均不闪试用页，加载后键盘可用，电脑试用页保留');
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
