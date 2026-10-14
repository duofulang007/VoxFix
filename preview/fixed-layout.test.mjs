import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import {keyboardGeometry} from './core.mjs';
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
  for(const native of [false,true]){
    const page=await browser.newPage({viewport:{width:native?411:1080,height:native?351:1000}}),errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    await page.addInitScript(native=>{
      localStorage.setItem('voxfix-layout-v1',JSON.stringify({height:321,fontSize:25,gap:9,radius:11,background:'#ff0000',candidateCount:9,candidateFont:13,rememberInput:false,clipboardHistory:false,toolbar:native?['menu','clipboard']:[]}));
      if(native){window.calls=[];window.VoxFixIme=Object.fromEntries(['compose','commit','completeEnglish','copy','delete','enter','hide','switcher','startVoice','startLocalVoice','stopVoice','cancelVoice','moveCursor','cursorPosition','edit','ready','resize','refreshClipboard','importClipboard'].map(n=>[n,(...args)=>calls.push([n,...args])]));VoxFixIme.voiceReady=()=>true;}
    },native);
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForFunction(()=>document.querySelector('.key-rows'));
    const result=await page.evaluate(()=>{
      const k=document.getElementById('keyboard'),key=[...k.getElementsByTagName('button')].find(b=>b.dataset.action==='letter'),s=getComputedStyle(key);
      return {height:k.getBoundingClientRect().height,keyHeight:key.getBoundingClientRect().height,font:s.fontSize,radius:s.borderRadius,gap:getComputedStyle(key.parentElement).gap,bg:getComputedStyle(k).backgroundColor,stored:JSON.parse(localStorage.getItem('voxfix-layout-v1'))};
    });
    // 键高 = (351 - 候选栏 45 - 底部条 49 - key-rows 上下内边距 5+15 - 行间距 30) / 4 = 51.75。
    assert.equal(result.height,351);assert.equal(Math.round(result.keyHeight*100)/100,51.75);assert.equal(result.font,'22px');assert.equal(result.radius,'7px');assert.equal(result.gap,'4px');assert.equal(result.bg,'rgb(237, 239, 242)');
    for(const [key,value] of Object.entries(keyboardGeometry))assert.equal(result.stored[key],value);
    assert.equal(result.stored.rememberInput,false);assert.equal(result.stored.clipboardHistory,false);
    assert.equal(await page.locator('input[type="range"],input[type="color"]').count(),0);
    if(native){
      assert((await page.evaluate(()=>calls)).some(c=>c[0]==='resize'&&c[1]===351));
      await page.locator('[data-action="menu"]').click();await page.locator('[data-action="custom"]').click();
      assert.equal(await page.locator('dialog[open] input[type="range"],dialog[open] input[type="color"]').count(),0);
      await page.locator('dialog[open] > button').click();
      await page.evaluate(()=>voxfixReset());
    }else{
      await page.locator('[data-tab="custom"]').click();
      await page.locator('#config-file').setInputFiles({name:'old-layout.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify({schema:1,height:250,fontSize:28,gap:10,radius:14,background:'#000000',candidateCount:9,candidateFont:13,rememberInput:false}))});
      await page.waitForFunction(()=>JSON.parse(localStorage.getItem('voxfix-layout-v1')).height===351);
      assert.equal(await page.locator('#keyboard').evaluate(e=>e.getBoundingClientRect().height),351);
      await page.locator('#reset').click();assert.equal(await page.locator('#keyboard').evaluate(e=>e.getBoundingClientRect().height),351);
    }
    assert.deepEqual(errors,[]);await page.close();
  }
  console.log('PASS: old geometry migration, fixed official key/background sizes, removed controls, import/reset guard, feature settings preserved, native resize and desktop parity');
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
