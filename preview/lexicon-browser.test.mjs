import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
const {chromium}=createRequire(import.meta.url)('playwright');
const server=createServer(async(req,res)=>{
  const name=req.url==='/'?'index.html':req.url.slice(1);
  if(!/^[\w.-]+$/.test(name)){res.writeHead(404);res.end();return;}
  try{res.setHeader('Content-Type',name.endsWith('.mjs')?'text/javascript':name.endsWith('.css')?'text/css':name.endsWith('.json')?'application/json':'text/html');res.end(await readFile(new URL(name,import.meta.url)));}catch{res.writeHead(404);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({channel:'msedge',headless:true});
try{
  const page=await browser.newPage({viewport:{width:1080,height:1000}}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(()=>localStorage.setItem('voxfix-learning-v1',JSON.stringify([{key:'p:ni',word:'你',code:'ni',hits:7}])));
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(()=>document.querySelector('#pinyin-status').textContent.includes('已就绪'));
  await page.locator('[data-tab="custom"]').click();await page.locator('#personal-lexicon').click();
  const download=async id=>{const event=page.waitForEvent('download');await page.locator(id).click();const file=await event;return readFile(await file.path(),'utf8');};
  let value=JSON.parse(await download('#lexicon-backup'));assert.equal(value.entries[0].hits,7);
  assert.equal(await page.evaluate(()=>localStorage.getItem('voxfix-learning-v1')),null,'Legacy source is removed only after durable migration');
  const entries=Array.from({length:10001},(_,i)=>({key:'p:cini',word:'词'+String.fromCharCode(0x4e00+i),code:'ci ni',hits:2}));
  entries.push({key:'p:mingtian',word:'明天',code:'ming tian',hits:12},{key:'p:mingtian',word:'明天',code:'ming tian',hits:2});
  const backup=JSON.stringify({format:'voxfix-personal-lexicon',version:1,entries,legacy:{'rime:voxfix.tsv':'historical source'}});
  const upload=async text=>{await page.locator('#lexicon-file').setInputFiles({name:'words.json',mimeType:'application/json',buffer:Buffer.from(text)});await page.waitForFunction(()=>document.querySelector('#lexicon-file').value==='');};
  await upload(backup);await upload(backup);
  value=JSON.parse(await download('#lexicon-backup'));assert.equal(value.entries.length,10003);assert.equal(value.entries.find(row=>row.word==='明天').hits,12);assert.equal(value.entries.find(row=>row.word==='你').hits,7);
  await upload('明天\tming tian\t-1');assert.match(await page.locator('#toast').textContent(),/未导入/);assert.equal(JSON.parse(await download('#lexicon-backup')).entries.length,10003);
  const text=await download('#lexicon-text');assert(text.includes('明天\tming tian\t12'));assert(!text.includes('historical source'));
  await page.reload();await page.waitForFunction(()=>document.querySelector('#pinyin-status').textContent.includes('已就绪'));
  await page.locator('[data-tab="custom"]').click();await page.locator('#personal-lexicon').click();
  value=JSON.parse(await download('#lexicon-backup'));assert.equal(value.entries.length,10003);assert.equal(value.legacy['rime:voxfix.tsv'],'historical source');
  // UI candidate recall must use the imported persistent store after reload.
  await page.locator('[data-tab="try"]').click();
  for(const c of 'mingtian')await page.locator(`#keyboard [data-action="letter"][data-value="${c.toUpperCase()}"]`).first().click();
  assert.equal(await page.locator('#keyboard [data-action="candidate"]').first().getAttribute('data-value'),'明天');
  assert.deepEqual(errors,[]);
  console.log('PASS: real IndexedDB migration, 10000+ persistent records, UI backup/TSV downloads, duplicate and rejected imports, reload and imported candidate recall');
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
