import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
const {chromium}=createRequire(import.meta.url)('playwright');
const server=createServer(async(req,res)=>{
  const name=req.url==='/'?'index.html':req.url.slice(1);
  if(!/^[\w.-]+$/.test(name)){res.writeHead(404);res.end();return;}
  try{res.setHeader('Content-Type',name.endsWith('.mjs')?'text/javascript':name.endsWith('.css')?'text/css':'text/html');res.end(await readFile(new URL(name,import.meta.url)));}catch{res.writeHead(404);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({channel:'msedge',headless:true}),failures=[];
async function check(name,run){
  const page=await browser.newPage({viewport:{width:393,height:531}}),errors=[];
  page.setDefaultTimeout(3000);page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript((imageRows)=>{
    window.VoxFixIme=Object.fromEntries(['compose','commit','completeEnglish','copy','delete','enter','hide','switcher','startVoice','startLocalVoice','stopVoice','cancelVoice','moveCursor','cursorPosition','gestureStart','gestureMove','gestureEnd','haptic','edit','ready','resize','refreshClipboard','importClipboard','clipHint'].map(name=>[name,()=>{}]));
    VoxFixIme.voiceReady=()=>true;VoxFixIme.pinyinReady=()=>true;
    VoxFixIme.pinyinCandidates=()=>JSON.stringify({words:['你好']});
    VoxFixIme.pinyinChoose=()=>JSON.stringify({committed:true,remaining:''});
    VoxFixIme.commitAccepted=()=>JSON.stringify({committed:true});
    VoxFixIme.screenshotList=()=>JSON.stringify({items:imageRows});
    localStorage.setItem('voxfix-snippets-v1',JSON.stringify({clips:[],phrases:[{name:'常用语',items:[]}]}));
    // reload 复用同一浏览器上下文：只在首轮播种/清空存储，否则持久化断言会被脚手架自己清掉
    if(!sessionStorage.getItem('__pcTestInit')){
      sessionStorage.setItem('__pcTestInit','1');
      localStorage.removeItem('voxfix-pc-clips-v1');
    }else{
      localStorage.setItem('voxfix-snippets-v1',JSON.stringify({clips:['电脑发来的内容','手机复制的'],phrases:[{name:'常用语',items:[]}]}));
    }
  },[{id:'a'.repeat(32),width:200,height:100,source:'pc'},{id:'b'.repeat(32),width:80,height:40}]);
  const click=(action,value)=>page.locator(`#keyboard [data-action="${action}"]${value===undefined?'':`[data-value="${value}"]`}`).first().click();
  try{await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(()=>typeof voxfixReset==='function'&&document.querySelector('#pinyin-status').textContent.includes('已就绪'));
  await run({page,click});assert.deepEqual(errors,[]);console.log('PASS '+name);}
  catch(error){failures.push(name+': '+error.message);console.log('FAIL '+name+': '+error.message.slice(0,220));}
  finally{await page.close();}
}
try{
  await check('PC-imported text rows carry the 电脑 tag, local copies do not, marks persist across reload',async({page,click})=>{
    await page.evaluate(()=>{voxfixClipboard('电脑发来的内容','pc-sig',false,true);voxfixClipboard('手机复制的','local-sig',false,false);});
    await click('clipboard');
    const rows=page.locator('.clip-text');
    assert.equal(await rows.count(),2,'Both clips are listed');
    const pcRow=rows.filter({hasText:'电脑发来的内容'}),localRow=rows.filter({hasText:'手机复制的'});
    assert.equal(await pcRow.locator('.clip-source-tag').count(),1,'PC row shows the 电脑 tag');
    assert.equal((await pcRow.locator('.clip-source-tag').textContent()).trim(),'电脑');
    assert.equal(await localRow.locator('.clip-source-tag').count(),0,'Local copy shows no tag');
    const stored=await page.evaluate(()=>JSON.parse(localStorage.getItem('voxfix-pc-clips-v1')));
    assert.deepEqual(stored,['电脑发来的内容'],'PC mark persists to its own storage key');
    await page.reload();
    await page.waitForFunction(()=>typeof voxfixReset==='function');
    await click('clipboard');
    assert.equal(await rows.filter({hasText:'电脑发来的内容'}).locator('.clip-source-tag').count(),1,'Tag survives reload');
  });
  await check('PC-imported image shows the 电脑 chip on its tile, local images do not',async({page})=>{
    await page.route('https://voxfix.local/screenshots/**',route=>route.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"/>'}));
    await page.evaluate(()=>voxfixOpenImages());
    const cards=page.locator('.clip-image-tile');
    assert.equal(await cards.count(),2);
    assert.equal(await cards.filter({has:page.locator('[data-value="'+'a'.repeat(32)+'"]')}).locator('.clip-source-float').count(),1,'PC image tile has floating 电脑 chip');
    assert.equal(await cards.filter({has:page.locator('[data-value="'+'b'.repeat(32)+'"]')}).locator('.clip-source-float').count(),0,'Local image tile has none');
    const text=await cards.locator('.clip-source-float').first().textContent();
    assert.equal(text.trim(),'电脑');
  });
  await check('fromPc with empty text never records a mark',async({page,click})=>{
    await page.evaluate(()=>voxfixClipboard('','empty-sig',false,true));
    await click('clipboard');
    assert.equal(await page.locator('.clip-source-tag').count(),0);
    assert.equal(await page.evaluate(()=>localStorage.getItem('voxfix-pc-clips-v1')),null);
  });
  if(failures.length){console.error(failures.join('\n'));process.exit(1);}
  console.log('PASS clipboard PC-import tags');
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
