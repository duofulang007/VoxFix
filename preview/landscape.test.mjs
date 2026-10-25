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
  for(const width of [640,889]){
    const page=await browser.newPage({viewport:{width,height:550},hasTouch:true}),errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    await page.addInitScript(()=>{
      window.calls=[];
      window.VoxFixIme=Object.fromEntries(['compose','commit','completeEnglish','delete','enter','hide','switcher','ready','resize','refreshClipboard','importClipboard','haptic'].map(name=>[name,(...args)=>calls.push([name,...args])]));
      VoxFixIme.isLandscape=()=>true;VoxFixIme.maxKeyboardHeight=()=>347;
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForFunction(()=>typeof voxfixReset==='function'&&document.querySelector('#pinyin-status').textContent.includes('已就绪'));
    const kb=page.locator('#keyboard');
    assert.equal((await kb.boundingBox()).height,258);
    assert.equal(await page.locator('.keyboard-footer').isVisible(),false);
    const q=await page.locator('[data-action="letter"][data-value="Q"]').boundingBox();
    const p=await page.locator('[data-action="letter"][data-value="P"]').boundingBox();
    assert.equal(q.height,47);assert(p.x+p.width-q.x<=700);
    assert(Math.abs(q.x-(width-p.x-p.width))<1,'键区应居中');
    await page.locator('[data-action="letter"][data-value="N"]').click();
    await page.locator('[data-action="letter"][data-value="I"]').click();
    await page.locator('.candidate-visible button').first().click();
    assert((await page.evaluate(()=>calls)).some(c=>c[0]==='commit'&&c[1]));
    await page.evaluate(()=>voxfixReset());
    for(const action of ['numbers','symbols']){
      await page.locator(`#keyboard [data-action="${action}"]`).first().click();
      const bounds=await kb.boundingBox();
      for(const box of await kb.locator('.number-body .key,.symbol-nav > button').evaluateAll(nodes=>nodes.map(n=>{const r=n.getBoundingClientRect();return {y:r.y,bottom:r.bottom,height:r.height};}))){
        assert(box.y>=bounds.y&&box.bottom<=bounds.y+bounds.height+1&&box.height>=35);
      }
      await page.evaluate(()=>voxfixReset());
    }
    await page.screenshot({path:`.android/landscape-preview-${width}.png`,clip:{x:0,y:292,width,height:258}});
    await page.locator('#keyboard [data-action="clipboard"]').first().click();
    await page.locator('#keyboard [data-action="clip-search-toggle"]').first().click();
    await page.locator('.clip-search-input').click();
    assert((await kb.boundingBox()).height<=347,'搜索输入不能撑满横屏');
    let field=await page.locator('.clip-search-input').boundingBox();
    let bounds=await kb.boundingBox();
    assert(field.y>=bounds.y&&field.y+field.height<=bounds.y+bounds.height);
    await page.evaluate(()=>voxfixReset());
    await page.locator('#keyboard [data-action="clipboard"]').first().click();
    await page.locator('#keyboard [data-action="clip-tab"][data-value="phrases"]').click();
    await page.locator('#keyboard [data-action="snippet-add"]').first().click();
    field=await page.locator('#snippet-content').boundingBox();bounds=await kb.boundingBox();
    assert(bounds.height<=347&&field.y>=bounds.y&&field.y+field.height<=bounds.y+bounds.height,`共享编辑框保持可见 ${JSON.stringify({field,bounds})}`);
    await page.evaluate(()=>voxfixReset());
    await page.locator('#keyboard [data-action="menu"]').click();
    await page.locator('#keyboard [data-action="custom"]').click();
    const settings=await page.locator('.settings-dialog[open]').boundingBox();
    assert(settings.y>=550-258&&settings.y+settings.height<=550,'横屏设置及返回按钮必须位于原生可见区');
    await page.locator('.settings-back').click();
    assert.deepEqual(errors,[]);
    assert((await page.evaluate(()=>calls)).filter(c=>c[0]==='resize').every(c=>c[1]<=347));
    await page.close();
  }
  console.log('PASS: landscape centered keys, 47px rows, no footer, candidate commits, numeric/symbol bounds and native height');
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
