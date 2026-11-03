import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
const {chromium}=createRequire(import.meta.url)('playwright');
const server=createServer(async(req,res)=>{
  const path=req.url==='/'?'index.html':req.url.slice(1);
  if(!/^[a-zA-Z0-9.-]+$/.test(path)){res.writeHead(404);res.end();return;}
  try{res.setHeader('Content-Type',path.endsWith('.mjs')?'text/javascript':path.endsWith('.css')?'text/css':'text/html');res.end(await readFile(new URL(path,import.meta.url)));}catch{res.writeHead(404);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({channel:'msedge',headless:true});
try{
  const page=await browser.newPage({viewport:{width:393,height:351}}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('https://voxfix.local/screenshots/**',route=>route.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100"><rect width="200" height="100" fill="green"/></svg>'}));
  await page.addInitScript(()=>{
    window.imageCalls=[];window.imageRows=[{id:'a'.repeat(32),width:200,height:100}];
    window.VoxFixIme={ready(){},resize(){},refreshClipboard(){},screenshotList(){return JSON.stringify({items:imageRows});},screenshotCopy(id){imageCalls.push(['copy',id]);return '{"ok":true}';},screenshotDelete(id){imageCalls.push(['delete',id]);imageRows=imageRows.filter(r=>r.id!==id);return JSON.stringify({items:imageRows});},screenshotCrop(...args){imageCalls.push(['crop',...args]);imageRows=[{id:'b'.repeat(32),width:80,height:40},...imageRows];return JSON.stringify({items:imageRows});}};
  });
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(()=>typeof voxfixOpenImages==='function');
  await page.evaluate(()=>voxfixOpenImages());
  const action=name=>page.locator(`#keyboard [data-action="${name}"]`).first();
  for(const width of [320,360,393,411]){
    await page.setViewportSize({width,height:351});
    assert.deepEqual(await page.locator('.tabs-pill button').allTextContents(),['文本','图片','常用']);
    assert(await page.locator('.tabs-pill button').evaluateAll(nodes=>nodes.every(n=>n.scrollWidth<=n.clientWidth)));
  }
  await page.evaluate(()=>{VoxFixIme.screenshotInsert=id=>{imageCalls.push(['send',id]);voxfixImageInserted('已交给聊天应用，请确认发送',true);};});
  await action('image-open').click();await action('image-copy').click();
  assert.deepEqual(await page.evaluate(()=>imageCalls[0]),['copy','a'.repeat(32)]);
  assert.equal(await page.locator('.clip-image-preview').count(),1,'Copy stays in keyboard preview');
  await action('image-send').click();assert.deepEqual(await page.evaluate(()=>imageCalls.find(r=>r[0]==='send')),['send','a'.repeat(32)]);
  await action('image-crop').click();
  const box=await page.locator('.clip-image-canvas').boundingBox();
  assert(box.width>250,'Preview canvas must fill the card instead of inheriting icon dimensions');
  await page.mouse.move(box.x+box.width*.3,box.y+box.height*.35);await page.mouse.down();await page.mouse.move(box.x+box.width*.65,box.y+box.height*.65);await page.mouse.up();
  await action('image-crop-save').click();
  const crop=await page.evaluate(()=>imageCalls.find(r=>r[0]==='crop'));
  assert(crop&&crop[2]>=0&&crop[3]>=0&&crop[4]<=200&&crop[5]<=100&&crop[4]>crop[2]&&crop[5]>crop[3]&&crop[4]-crop[2]<200,'Dragging must change the selected area');
  assert.equal(await page.locator('.clip-image-card').count(),2,'Crop preserves original');
  await action('image-open').click();await action('image-delete').click();await action('image-cancel').click();
  assert.equal(await page.evaluate(()=>imageCalls.filter(r=>r[0]==='delete').length),0,'Cancellation never deletes');
  await action('image-delete').click();await action('image-delete-confirm').click();
  assert.equal(await page.locator('.clip-image-card').count(),1);
  await page.locator('[data-action="clip-tab"][data-value="phrases"]').click();assert.equal(await page.locator('.group-pills').count(),1);
  await page.locator('[data-action="clip-tab"][data-value="clipboard"]').click();assert.equal(await page.locator('.clip-image-grid').count(),0);
  await page.evaluate(()=>{VoxFixIme.screenshotList=()=>'{"error":"读取失败，原图保留"}';voxfixOpenImages();});
  assert.match(await page.locator('.empty-state').textContent(),/读取失败/);
  assert.deepEqual(errors,[]);
  console.log('PASS: three tabs, narrow headers, in-keyboard preview/copy/crop/delete, cancel and errors');
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
