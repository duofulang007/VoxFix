import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFile,mkdir} from 'node:fs/promises';
const {chromium}=createRequire(import.meta.url)('playwright');
const id='0123456789abcdef01234567';
const pixel=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aV1sAAAAASUVORK5CYII=','base64');
const server=createServer(async(req,res)=>{
  const path=req.url==='/'?'index.html':req.url.slice(1);
  if(!/^[a-zA-Z0-9.-]+$/.test(path)){res.writeHead(404);res.end();return;}
  try{res.setHeader('Content-Type',path.endsWith('.mjs')?'text/javascript':path.endsWith('.css')?'text/css':path.endsWith('.json')?'application/json':'text/html');res.end(await readFile(new URL(path,import.meta.url)));}
  catch{res.writeHead(404);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({channel:'msedge',headless:true});
try {
  const page=await browser.newPage({viewport:{width:393,height:351},hasTouch:true});
  const errors=[],queries=[];page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(()=>{
    window.calls=[];window.VoxFixIme=Object.fromEntries(['compose','commit','copy','delete','enter','ready','resize','haptic','searchFocus','insertMeme'].map(name=>[name,(...args)=>calls.push([name,...args])]));
  });
  await page.route('**/meme/search?*',async route=>{
    const url=new URL(route.request().url());queries.push(url);
    await route.fulfill({json:{items:[{id,title:url.searchParams.get('q')+'表情'}],tags:['猫咪','打工人'],more:true}});
  });
  await page.route('**/meme/image/**',route=>route.fulfill({contentType:'image/png',body:pixel}));
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(()=>typeof voxfixReset==='function');
  assert.equal(queries.length,0,'Typing must not preload network search');
  const key=action=>page.locator(`#keyboard [data-action="${action}"]`).first();
  await key('emoji').click();
  assert.equal(await page.locator('.meme-categories,.meme-tags').count(),0);
  assert.equal(queries.length,0);
  await page.getByRole('searchbox',{name:'搜索表情包',exact:true}).focus();
  await page.setViewportSize({width:393,height:550});
  let search=page.getByRole('searchbox',{name:'搜索表情包',exact:true});
  await search.fill('熊大');await page.locator('.meme-grid button').first().waitFor();
  assert.equal(queries.at(-1).searchParams.get('q'),'熊大');assert(!queries.at(-1).searchParams.has('category'));
  assert(await search.isVisible());assert(await page.locator('.meme-results').isVisible());assert(await key('letter').isVisible());
  await search.evaluate(input=>window.savedMemeInput=input);
  await search.fill('收到');await page.waitForFunction(()=>document.querySelector('.meme-grid img')?.alt==='收到表情');
  assert(await search.evaluate(input=>document.activeElement===input&&input===window.savedMemeInput),'Result updates must preserve the focused input');
  assert((await page.evaluate(()=>calls)).some(row=>row[0]==='searchFocus'&&row[1]===true));
  assert(!(await page.evaluate(()=>calls)).some(row=>row[0]==='commit'||row[0]==='compose'),'Search stays inside the IME');
  const action=action=>page.locator(`#ime-local-editor [data-action="${action}"]`).first();
  await action('meme-clear').click();const previous=queries.length;
  await page.waitForFunction(()=>document.querySelector('#pinyin-status').textContent.includes('已就绪'));
  for(const letter of 'MAO')await page.locator(`#keyboard [data-action="letter"][data-value="${letter}"]`).click();
  await page.waitForTimeout(380);assert.equal(queries.length,previous,'Unconfirmed pinyin must not be searched');
  await page.locator('[data-action="candidate"][data-value="猫"]').first().click();
  await page.waitForFunction(()=>document.querySelector('.meme-grid img')?.alt==='猫表情');
  assert.equal(queries.at(-1).searchParams.get('q'),'猫');
  for(const width of [320,393,480]){
    await page.setViewportSize({width,height:550});
    assert(await page.locator('.meme-results').evaluate(node=>node.clientHeight>60),'Results remain visible with typing keys');
    assert(await page.locator('.meme-grid').evaluate(node=>node.scrollWidth<=node.clientWidth+1));
    assert(await search.isVisible());
  }
  await action('meme-done').click();await page.setViewportSize({width:393,height:351});
  assert(await page.getByRole('searchbox',{name:'搜索表情包'}).isVisible());assert(await page.locator('.meme-grid button').first().isVisible());
  await key('meme-preview').click();assert(await page.getByRole('dialog',{name:'表情包预览'}).isVisible());
  assert(await page.getByRole('searchbox',{name:'搜索表情包'}).isVisible(),'Preview keeps the search field available');
  await key('meme-insert').click();assert((await page.evaluate(()=>calls)).some(row=>row[0]==='insertMeme'&&row[1]===id));
  await key('meme-close').click();
  assert.equal(await page.locator('.emoji-top,[data-action="emoji-tab"],.emoji-grid').count(),0);
  const searchBox=await page.getByRole('searchbox',{name:'搜索表情包'}).boundingBox();assert(searchBox.y<25,'Search must start at the top, without a reserved tab row');
  await key('return').click();assert.equal(await page.locator('[data-action="letter"]').count(),26);
  assert.deepEqual(errors,[]);
  if(process.env.VOXFIX_MEME_LIVE==='1'){
    const live=JSON.parse(await readFile(new URL('../.android/meme-probe/inline-live.json',import.meta.url),'utf8'));
    await page.unroute('**/meme/search?*');await page.route('**/meme/search?*',route=>route.fulfill({json:live}));
    await page.unroute('**/meme/image/**');await page.route('**/meme/image/**',async route=>{
      const id=new URL(route.request().url()).pathname.split('/').at(-1);
      const bytes=await readFile(new URL(`../.android/meme-probe/${id}.image`,import.meta.url));
      const contentType=bytes[0]===255?'image/jpeg':bytes[0]===137?'image/png':bytes.subarray(0,3).toString()==='GIF'?'image/gif':'image/webp';
      await route.fulfill({contentType,body:bytes});
    });
  }
  await page.setViewportSize({width:393,height:351});await page.evaluate(()=>voxfixReset());await key('emoji').click();
  await page.getByRole('searchbox',{name:'搜索表情包'}).focus();await page.setViewportSize({width:393,height:550});
  await page.getByRole('searchbox',{name:'搜索表情包'}).fill('熊大');await page.locator('.meme-grid button').first().waitFor();
  await page.waitForFunction(()=>[...document.querySelectorAll('.meme-grid img')].slice(0,5).every(image=>image.complete&&image.naturalWidth>0));
  await mkdir(new URL('../.android/meme-probe/',import.meta.url),{recursive:true});
  await page.screenshot({path:new URL('../.android/meme-probe/inline-search.png',import.meta.url).pathname.replace(/^\/(.:)/,'$1')});
  await page.locator('#ime-local-editor [data-action="meme-done"]').click();await page.setViewportSize({width:393,height:351});
  await page.screenshot({path:new URL('../.android/meme-probe/search-only-browse.png',import.meta.url).pathname.replace(/^\/(.:)/,'$1')});
  console.log('meme-search-ime: inline field/results/keys, debounce, pinyin confirmation, focus, clearing, preview and three widths passed');
} finally {await browser.close();await new Promise(resolve=>server.close(resolve));}
