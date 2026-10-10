import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
const {chromium}=createRequire(import.meta.url)('playwright');
const server=createServer(async(req,res)=>{
  try{const path=req.url==='/'?'index.html':req.url.slice(1);res.setHeader('Content-Type',path.endsWith('.mjs')?'text/javascript':path.endsWith('.json')?'application/json':path.endsWith('.css')?'text/css':'text/html');res.end(await readFile(new URL(path,import.meta.url)));}
  catch{res.writeHead(404);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({channel:'msedge',headless:true});
try{
  const page=await browser.newPage({viewport:{width:1200,height:1000},hasTouch:true}),errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(()=>{
    localStorage.setItem('voxfix-snippets-v1',JSON.stringify({clips:[],phrases:['我明天联系你']}));
    const track={stop(){},enabled:true};
    navigator.mediaDevices.getUserMedia=async()=>({getTracks:()=>[track]});
    window.AudioContext=class{
      constructor(){this.sampleRate=16000;this.state='running';this.destination={};this.audioWorklet={addModule:async()=>{}};}
      async resume(){this.state='running';}async suspend(){this.state='suspended';}async close(){this.state='closed';}
      createMediaStreamSource(){return {connect(){},disconnect(){}};}
    };
    window.AudioWorkletNode=class{
      constructor(){window.testCapture=this;this.port={postMessage:()=>queueMicrotask(()=>this.port.onmessage({data:{flushed:true}}))};}
      connect(){}disconnect(){}
    };
  });
  let failChunk=false,polishFailure=false;
  await page.route('**/api/**',async route=>{
    const path=new URL(route.request().url()).pathname;
    let body=path.endsWith('/status')?{ready:true,installed:true}:path.endsWith('/start')?{session:'test'}:path==='/api/polish'?{text:'我明天不跑10公里。',seconds:1,rejectedSegments:0}:{text:'我明天不跑10公里',final:path.endsWith('/finish')};
    const failed=path.endsWith('/chunk')&&failChunk||path==='/api/polish'&&polishFailure;
    await route.fulfill({status:failed?500:200,contentType:'application/json',body:JSON.stringify(failed?{error:'测试失败'}:body)});
  });
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(()=>document.querySelector('#pinyin-status').textContent.includes('已就绪'));
  await page.locator('#keyboard').getByRole('button',{name:'W',exact:true}).tap();
  await page.locator('#keyboard').getByRole('button',{name:'W',exact:true}).evaluate(button=>button.dispatchEvent(new PointerEvent('click',{bubbles:true,detail:0,pointerType:'touch'})));
  assert.equal(await page.locator('#editor').inputValue(),'w','detail=0 touch click must not repeat a pointer-up key');
  await page.locator('#clear-input').click();
  await page.locator('#keyboard').getByRole('button',{name:'W',exact:true}).evaluate(button=>button.click());
  assert.equal(await page.locator('#editor').inputValue(),'w','keyboard/programmatic activation must remain available');
  await page.locator('#clear-input').click();
  await page.locator('#editor').pressSequentially('wmtlxn');
  assert.equal(await page.locator('.candidate-visible button').first().textContent(),'我明天联系你');
  await page.locator('#clear-input').click();
  await page.locator('summary').filter({hasText:'长句与领域词库'}).click();
  await page.locator('#phrase-options input[value="quotes"]').check();
  await page.locator('#phrase-search').fill('千里之行');
  await page.locator('#phrase-results button').click();
  assert((await page.locator('#editor').inputValue()).includes('始于足下'));
  await page.locator('#clear-input').click();
  await page.locator('#long-voice').click();
  await page.waitForFunction(()=>!document.querySelector('#pause-voice').disabled);
  await page.evaluate(()=>testCapture.port.onmessage({data:{samples:new Float32Array(3200)}}));
  await page.waitForFunction(()=>document.querySelector('#editor').value.includes('10公里'));
  await page.locator('#pause-voice').click();
  await page.waitForFunction(()=>document.querySelector('#pause-voice').textContent==='继续');
  await page.locator('#pause-voice').click();
  await page.locator('#finish-voice').click();
  await page.waitForFunction(()=>!document.querySelector('#editor').readOnly);
  await page.locator('#polish-text').click();
  await page.waitForFunction(()=>!document.querySelector('#polish-apply').disabled);
  assert.equal(await page.locator('#editor').inputValue(),'我明天不跑10公里');
  await page.locator('#polish-apply').click();
  assert.equal(await page.locator('#editor').inputValue(),'我明天不跑10公里。');
  await page.locator('#undo-polish').click();
  assert.equal(await page.locator('#editor').inputValue(),'我明天不跑10公里');
  polishFailure=true;await page.locator('#polish-text').click();
  await page.waitForFunction(()=>document.querySelector('#polish-message').textContent.includes('原文未修改'));
  await page.locator('#polish-close').click();
  await page.locator('#clear-input').click();
  await page.locator('#long-voice').click();await page.waitForFunction(()=>!document.querySelector('#pause-voice').disabled);
  await page.evaluate(()=>testCapture.port.onmessage({data:{samples:new Float32Array(3200)}}));
  await page.waitForFunction(()=>document.querySelector('#editor').value.includes('10公里'));
  failChunk=true;await page.evaluate(()=>testCapture.port.onmessage({data:{samples:new Float32Array(3200)}}));
  await page.waitForFunction(()=>!document.querySelector('#editor').readOnly);
  assert.equal(await page.locator('#editor').inputValue(),'我明天不跑10公里');
  assert.deepEqual(errors,[]);
  await page.screenshot({path:new URL('../docs/offline-improvements.png',import.meta.url).pathname.replace(/^\/(\w:)/,'$1'),fullPage:true});
  console.log('PASS: personal initials, quote search, streaming, pause/resume, finish, explicit polish preview/apply/undo, failures preserve text');
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
