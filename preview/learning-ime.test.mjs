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
const browser=await chromium.launch({channel:'msedge',headless:true}),failures=[];
async function check(name,run){
  const session=await browser.newContext({viewport:{width:393,height:531},hasTouch:true}),page=await session.newPage(),errors=[];
  page.setDefaultTimeout(4000);page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(()=>{
    window.calls=[];window.acceptCommit=true;
    const codes={'明天':'ming tian','下午':'xia wu','见':'jian','是':'shi','时':'shi'};
    window.VoxFixIme=Object.fromEntries(['compose','commit','completeEnglish','copy','delete','enter','hide','switcher','startVoice','startLocalVoice','stopVoice','cancelVoice','moveCursor','cursorPosition','gestureStart','gestureEnd','edit','haptic','ready','resize','refreshClipboard','importClipboard'].map(name=>[name,(...args)=>calls.push([name,...args])]));
    VoxFixIme.voiceReady=()=>true;VoxFixIme.pinyinReady=()=>true;
    VoxFixIme.pinyinCandidates=raw=>{
      const word=raw.startsWith('mingtian')?'明天':raw.startsWith('xiawu')?'下午':raw==='jian'?'见':'默认';
      return JSON.stringify({words:raw==='shi'?['是','时']:[word],preedit:raw==='mingtianxiawujian'?'ming tian xia wu jian':raw==='xiawujian'?'xia wu jian':raw==='shi'?'shi':codes[word]||raw});
    };
    VoxFixIme.pinyinChoose=(raw,word,learn)=>{
      calls.push(['pinyinChoose',raw,word,learn]);
      return JSON.stringify({committed:acceptCommit,remaining:raw.slice(codes[word].replaceAll(' ','').length)});
    };
    VoxFixIme.commitAccepted=word=>{calls.push(['commitAccepted',word]);return JSON.stringify({committed:acceptCommit});};
    VoxFixIme.pinyinClearLearning=()=>{calls.push(['clearLearning']);return '{"cleared":true}';};
  });
  const ready=()=>page.waitForFunction(()=>typeof voxfixStartInput==='function'&&document.querySelector('#pinyin-status').textContent.includes('已就绪'));
  const key=(action,value)=>page.locator(`#keyboard [data-action="${action}"]${value===undefined?'':`[data-value="${value}"]`}`).first();
  const type=async raw=>{for(const letter of raw)await key('letter',letter.toUpperCase()).click();};
  const choose=async(raw,word)=>{await type(raw);await key('candidate',word).click();};
  const records=()=>page.evaluate(()=>JSON.parse(localStorage.getItem('voxfix-learning-v1')||'[]'));
  const reset=()=>page.evaluate(()=>voxfixStartInput(1,false,0));
  const train=async()=>{for(let i=0;i<2;i++){await reset();await type('mingtianxiawujian');for(const word of ['明天','下午','见'])await key('candidate',word).click();}};
  try{await page.goto(`http://127.0.0.1:${server.address().port}/`);await ready();await run({page,key,type,choose,records,reset,train,ready});assert.deepEqual(errors,[]);console.log('PASS '+name);}
  catch(e){failures.push(name+': '+e.message);console.log('FAIL '+name+': '+e.message.slice(0,500));}
  finally{await session.close();}
}
try{
  await check('native partial choices persist a phrase shared by full, initial and mixed spelling',async({page,key,type,records,reset,train,ready})=>{
    await page.evaluate(()=>localStorage.setItem('voxfix-learning-v1',JSON.stringify([{key:'p:ni',word:'泥',hits:3}])));await page.reload();await ready();
    await train();
    let rows=await records();assert.equal(rows.find(row=>row.word==='明天下午见').hits,2);assert.equal(rows.find(row=>row.word==='泥').hits,3,'Keep old learning');
    await page.reload();await ready();
    for(const raw of ['mtxwj','mingtxwjian','mingtianxiawujian']){
      await reset();await type(raw);
      assert.equal(await key('candidate').getAttribute('data-value'),'明天下午见',raw);
    }
    await reset();await type('mtxwj');await key('candidate','明天下午见').click();
    rows=await records();assert.equal(rows.filter(row=>row.word==='明天下午见').length,1);assert.equal(rows.find(row=>row.word==='明天下午见').hits,3);
    assert(await page.evaluate(()=>calls.some(row=>row[0]==='commitAccepted'&&row[1]==='明天下午见')));
  });
  await check('repeated native choices rise to first place and survive reload',async({page,key,type,choose,reset,ready})=>{
    for(let i=0;i<2;i++){await reset();await choose('shi','时');}
    await page.reload();await ready();await reset();await type('shi');
    assert.equal(await key('candidate').getAttribute('data-value'),'时');
  });
  await check('learned continuation takes priority in the existing association bar',async({key,choose,reset,train})=>{
    await train();await reset();await choose('mingtian','明天');
    assert.equal(await key('association-candidate').getAttribute('data-value'),'下午见');
  });
  await check('deletion, cursor move, field change and literal insertion stop phrase assembly',async({page,key,choose,reset,records})=>{
    for(const boundary of ['delete','cursor','field','literal']){
      await reset();await choose('mingtian','明天');
      if(boundary==='delete')await key('delete').click();
      if(boundary==='cursor')await page.evaluate(()=>voxfixClearComposition());
      if(boundary==='field')await reset();
      if(boundary==='literal')await key('space').click();
      await choose('xiawu','下午');
      assert(!(await records()).some(row=>row.word==='明天下午'),boundary);
    }
  });
  await check('disabled learning, private fields, no-learning flag and refused commits never save',async({page,key,choose,records,reset})=>{
    await page.evaluate(()=>{const box=document.querySelector('#remember-input');box.checked=false;box.dispatchEvent(new Event('change'));});
    assert.match(await page.locator('#learning-status').textContent(),/已关闭/);
    await choose('mingtian','明天');assert.deepEqual(await records(),[]);
    await page.evaluate(()=>{const box=document.querySelector('#remember-input');box.checked=true;box.dispatchEvent(new Event('change'));voxfixStartInput(1,false,0x1000000);});
    await choose('mingtian','明天');assert.deepEqual(await records(),[]);
    assert.equal(await page.evaluate(()=>calls.filter(row=>row[0]==='pinyinChoose').at(-1)[3]),false);
    await reset();await page.evaluate(()=>acceptCommit=false);await choose('mingtian','明天');assert.deepEqual(await records(),[]);
    await page.evaluate(()=>{acceptCommit=true;voxfixStartInput(129,true);});
    assert.equal(await key('candidate').count(),0);assert.deepEqual(await records(),[]);
  });
  await check('clearing removes learned recall and associations and keeps unrelated storage',async({page,key,type,records,reset,train,ready})=>{
    await train();await page.evaluate(()=>{localStorage.setItem('unrelated','keep');document.querySelector('#clear-learning').click();});
    assert((await records()).length>0,'First click must only confirm');
    await page.evaluate(()=>document.querySelector('#clear-learning').click());
    assert.deepEqual(await records(),[]);assert.equal(await page.evaluate(()=>localStorage.getItem('unrelated')),'keep');
    await page.reload();await ready();await reset();await type('mtxwj');
    assert.equal(await key('candidate','明天下午见').count(),0);
  });
  await check('storage failure is visibly temporary',async({page,choose})=>{
    await page.evaluate(()=>{const original=Storage.prototype.setItem;Storage.prototype.setItem=function(key,value){if(key==='voxfix-learning-v1')throw Error('full');return original.call(this,key,value);};});
    await choose('mingtian','明天');assert.match(await page.locator('#learning-status').textContent(),/保存失败/);
  });
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
assert.deepEqual(failures,[]);
