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
    window.listCalls=0;window.VoxFixIme={ready(){},resize(){},refreshClipboard(){},screenshotList(){window.listCalls++;return JSON.stringify({items:imageRows});},screenshotCopy(id){imageCalls.push(['copy',id]);return '{"ok":true}';},screenshotDelete(id){imageCalls.push(['delete',id]);if(window.imageDeleteFailure===id)return '{"error":"删除失败，请重试"}';imageRows=imageRows.filter(r=>r.id!==id);return JSON.stringify({items:imageRows});},screenshotCrop(...args){imageCalls.push(['crop',...args]);imageRows=[{id:'b'.repeat(32),width:80,height:40},...imageRows];return JSON.stringify({items:imageRows});}};
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
  assert.equal(await action('image-delete').count(),0,'Preview has no deletion control');
  for(const width of [320,360,393,411]){
    await page.setViewportSize({width,height:351});
    const controls=await page.locator('.clip-image-actions button').evaluateAll(nodes=>nodes.map(n=>{const r=n.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height,clipped:n.scrollWidth>n.clientWidth};}));
    assert.equal(controls.length,4);
    assert(controls.every(r=>r.y===controls[0].y&&r.height>=44&&!r.clipped&&r.x>=0&&r.x+r.width<=width),'Preview actions fit one row');
    assert(controls[3].width<width/2,'Insert must not occupy a full row');
    assert(await page.locator('.clip-image-canvas').evaluate(n=>n.getBoundingClientRect().height>150),'Preview keeps useful image space');
  }
  await page.evaluate(()=>{VoxFixIme.screenshotEdit=id=>{imageCalls.push(['edit',id]);return '{"ok":true}';};});
  const listsBefore=await page.evaluate(()=>listCalls);
  await action('image-edit').click();
  assert.deepEqual(await page.evaluate(()=>imageCalls.filter(r=>r[0]==='edit').at(-1)),['edit','a'.repeat(32)],'Edit asks the native editor for this record');
  assert.equal(await page.locator('.clip-image-preview').count(),1,'Opening the editor keeps the preview');
  await page.evaluate(()=>voxfixImageSaved(true));
  assert(await page.evaluate(()=>listCalls)>listsBefore,'A finished edit refreshes the image list');
  assert.match(await page.locator('#toast').textContent(),/已保存图片修改/);
  const src=await page.locator('.clip-image-canvas image').getAttribute('href');
  assert.match(src,/[?&]v=\d+/,'Refreshed previews must not reuse a cached image URL');
  await page.evaluate(()=>{VoxFixIme.screenshotEdit=()=>'{"error":"请在屏幕取字设置中启用服务后再编辑图片"}';});
  await action('image-edit').click();
  assert.match(await page.locator('#toast').textContent(),/启用服务/);
  assert.equal(await page.locator('.clip-image-preview').count(),1,'Unavailable editor keeps the preview instead of leaving the page');
  assert.deepEqual(await page.evaluate(()=>imageRows.map(r=>r.id)),['a'.repeat(32)],'Failed edit does not drop the record');
  await action('image-send').click();assert.deepEqual(await page.evaluate(()=>imageCalls.find(r=>r[0]==='send')),['send','a'.repeat(32)]);
  await action('image-crop').click();
  const box=await page.locator('.clip-image-canvas').boundingBox();
  assert(box.width>250,'Preview canvas must fill the card instead of inheriting icon dimensions');
  await page.mouse.move(box.x+box.width*.3,box.y+box.height*.35);await page.mouse.down();await page.mouse.move(box.x+box.width*.65,box.y+box.height*.65);await page.mouse.up();
  await action('image-crop-save').click();
  const crop=await page.evaluate(()=>imageCalls.find(r=>r[0]==='crop'));
  assert(crop&&crop[2]>=0&&crop[3]>=0&&crop[4]<=200&&crop[5]<=100&&crop[4]>crop[2]&&crop[5]>crop[3]&&crop[4]-crop[2]<200,'Dragging must change the selected area');
  assert.equal(await page.locator('.clip-image-card').count(),2,'Crop preserves original');
  const holdImage=async()=>{const b=await action('image-open').boundingBox();await page.mouse.move(b.x+b.width/2,b.y+b.height/2);await page.mouse.down();await page.waitForTimeout(560);await page.mouse.up();};
  await holdImage();
  assert.equal(await page.locator('.clip-image-preview').count(),0,'Long press never opens preview');
  assert.equal(await page.locator('.image-remove').count(),1,'Long press exposes one red cross on the thumbnail');
  assert(await action('image-delete').evaluate(n=>{const a=n.getBoundingClientRect(),b=n.parentElement.getBoundingClientRect();return a.width>=44&&a.height>=44&&a.right<=b.right+1&&a.top>=b.top;}),'Red cross has a usable target at the top right');
  await action('image-delete').click();await action('image-cancel').click();
  assert.equal(await page.evaluate(()=>imageCalls.filter(r=>r[0]==='delete').length),0,'Cancellation never deletes');
  await action('image-delete').click();await action('image-delete-confirm').click();
  assert.equal(await page.locator('.clip-image-card').count(),1);
  await page.evaluate(()=>{imageRows.push({id:'c'.repeat(32),width:100,height:180},{id:'d'.repeat(32),width:300,height:200});voxfixOpenImages();});
  // Moving, cancellation and a second finger must not arm thumbnail deletion.
  await action('image-open').dispatchEvent('pointerdown',{pointerId:4,button:0,clientX:20,clientY:90});
  await page.locator('#keyboard').dispatchEvent('pointermove',{pointerId:4,clientX:50,clientY:90});
  await page.waitForTimeout(560);
  assert.equal(await page.locator('.image-remove').count(),0);
  await action('image-open').dispatchEvent('pointerdown',{pointerId:5,button:0});
  await page.locator('#keyboard').dispatchEvent('pointercancel',{pointerId:5});
  await page.waitForTimeout(560);
  assert.equal(await page.locator('.image-remove').count(),0);
  await action('image-open').dispatchEvent('pointerdown',{pointerId:6,button:0});
  await action('image-open').dispatchEvent('pointerdown',{pointerId:7,button:0});
  await page.waitForTimeout(560);
  assert.equal(await page.locator('.image-remove').count(),0);
  await action('image-manage').click();
  assert(await action('image-delete-selected').isDisabled());
  assert.equal(await page.locator('[data-action="image-toggle"]').count(),3);
  await action('image-toggle').click();
  assert.equal(await page.locator('.clip-image-preview').count(),0,'Selection never opens preview');
  await action('image-select-all').click();
  assert.equal(await page.locator('[aria-pressed="true"][data-action="image-toggle"]').count(),3);
  await action('image-select-all').click();
  assert(await action('image-delete-selected').isDisabled(),'Deselect all disables deletion');
  await page.locator('[data-action="image-toggle"]').nth(0).click();
  await page.locator('[data-action="image-toggle"]').nth(2).click();
  const beforeBatch=await page.evaluate(()=>imageCalls.filter(r=>r[0]==='delete').length);
  for(const width of [320,360,393,411]){
    await page.setViewportSize({width,height:351});
    assert(await page.locator('.image-managing').evaluate(n=>n.scrollWidth<=n.clientWidth),'Selection header fits narrow screens');
  }
  await action('image-delete-selected').click();
  assert.match(await page.getByRole('dialog').textContent(),/删除这 2 张图片/);
  assert.equal(await page.evaluate(()=>imageCalls.filter(r=>r[0]==='delete').length),beforeBatch,'Batch delete waits for confirmation');
  assert.equal(await page.evaluate(()=>document.activeElement.dataset.action),'image-cancel','Confirmation defaults to the safe action');
  await page.keyboard.press('Shift+Tab');
  assert.equal(await page.evaluate(()=>document.activeElement.dataset.action),'image-delete-confirm');
  await page.keyboard.press('Tab');
  assert.equal(await page.evaluate(()=>document.activeElement.dataset.action),'image-cancel','Tab stays in the confirmation');
  await page.keyboard.press('Escape');
  assert.equal(await page.locator('[aria-pressed="true"][data-action="image-toggle"]').count(),2,'Cancel keeps the selection');
  await action('image-delete-selected').click();await action('image-delete-confirm').click();
  assert.deepEqual(await page.evaluate(()=>imageRows.map(r=>r.id)),['c'.repeat(32)],'Only selected images are deleted');
  assert(await action('image-delete-selected').isDisabled());
  await action('image-manage').click();
  await page.evaluate(()=>{imageRows.push({id:'e'.repeat(32),width:100,height:100},{id:'f'.repeat(32),width:100,height:100});imageDeleteFailure='e'.repeat(32);voxfixOpenImages();});
  await action('image-manage').click();await action('image-select-all').click();
  await action('image-delete-selected').click();await action('image-delete-confirm').click();
  assert.deepEqual(await page.evaluate(()=>imageRows.map(r=>r.id)),['e'.repeat(32),'f'.repeat(32)],'Failure stops deletion and preserves remaining images');
  assert.equal(await page.locator('[aria-pressed="true"][data-action="image-toggle"]').count(),2,'Remaining selection survives partial failure');
  await action('image-manage').click();
  await page.locator('[data-action="clip-tab"][data-value="phrases"]').click();assert.equal(await page.locator('.group-pills').count(),1);
  await page.locator('[data-action="clip-tab"][data-value="clipboard"]').click();assert.equal(await page.locator('.clip-image-grid').count(),0);
  await page.evaluate(()=>{VoxFixIme.screenshotList=()=>'{"error":"读取失败，原图保留"}';voxfixOpenImages();});
  assert.match(await page.locator('.empty-state').textContent(),/读取失败/);
  await page.evaluate(()=>{imageRows=[];VoxFixIme.screenshotList=()=>JSON.stringify({items:imageRows});voxfixOpenImages();});
  assert(await action('image-manage').isDisabled(),'Empty lists do not enter selection');
  // 来源角标只标「电脑」：编辑过的记录（kind=project）也必须保留来源，不能被"可编辑"之类顶掉。
  // 每条记录都能点编辑，标"可编辑"没有区分度，还会盖掉真正有用的来源信息。
  await page.evaluate(()=>{
    imageRows=[{id:'c'.repeat(32),width:200,height:100,source:'pc',kind:'project'}];
    VoxFixIme.screenshotList=()=>JSON.stringify({items:imageRows});
    voxfixOpenImages();
  });
  assert.equal(await page.locator('.clip-image-tile .clip-source-ribbon').count(),1,'缩略图只有一个来源角标');
  assert.equal((await page.locator('.clip-image-tile .clip-source-ribbon').first().textContent()).trim(),'电脑','编辑过的电脑图片仍要标"电脑"');
  await page.evaluate(()=>{
    imageRows=[{id:'d'.repeat(32),width:200,height:100,kind:'project'}];
    VoxFixIme.screenshotList=()=>JSON.stringify({items:imageRows});
    voxfixOpenImages();
  });
  assert.equal(await page.locator('.clip-image-tile .clip-source-ribbon').count(),0,'手机内的记录不加来源角标');
  assert.deepEqual(errors,[]);
  console.log('PASS: compact preview/copy/crop/insert, long-press thumbnail delete, batch selection/confirmation, cancellation, partial failure and narrow layouts');
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
