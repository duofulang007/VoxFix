import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
const {chromium}=createRequire(import.meta.url)('playwright');
const baseline=process.argv.includes('--baseline');
const server=createServer(async(req,res)=>{
  const name=req.url==='/'?'index.html':req.url.slice(1);
  if(!/^[\w.-]+$/.test(name)){res.writeHead(404);res.end();return;}
  try{res.setHeader('Content-Type',name.endsWith('.mjs')?'text/javascript':name.endsWith('.css')?'text/css':name.endsWith('.json')?'application/json':'text/html');res.end(await readFile(new URL(name,import.meta.url)));}catch{res.writeHead(404);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({channel:'msedge',headless:true});
try{
  const page=await browser.newPage({viewport:{width:393,height:551},hasTouch:true}),errors=[];
  page.setDefaultTimeout(4000);page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(()=>{
    window.calls=[];
    window.VoxFixIme=Object.fromEntries(['compose','commit','delete','enter','hide','switcher','stopVoice','cancelVoice','cursorPosition','gestureStart','gestureEnd','gestureLock','gestureUnlock','gestureDelta','selectionStep','haptic','edit','ready','resize','refreshClipboard','candidatesVisible','selectionEditing'].map(name=>[name,(...args)=>calls.push([name,...args])]));
    VoxFixIme.voiceReady=VoxFixIme.pinyinReady=()=>true;
    VoxFixIme.startVoice=()=>{calls.push(['startVoice']);setTimeout(()=>voxfixVoiceListening(),0);};
    VoxFixIme.pinyinCandidates=raw=>JSON.stringify({words:raw.startsWith('mingtian')?['明天','明天见']:['你','你好'],preedit:raw});
    VoxFixIme.pinyinChoose=()=>'{"committed":true,"remaining":""}';
    VoxFixIme.pinyinChooseLocal=()=>'{"committed":true,"remaining":""}';
    VoxFixIme.commitAccepted=text=>{calls.push(['commitAccepted',text]);return '{"committed":true}';};
    localStorage.setItem('voxfix-snippets-v1',JSON.stringify({clips:['明天到会议室开会',...Array.from({length:25},(_,i)=>'体验检查记录'+i)],phrases:[{name:'常用语',items:['好的']}]}));
  });
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(()=>typeof voxfixStartInput==='function'&&document.querySelector('#pinyin-status').textContent.includes('已就绪'));
  const key=(action,value)=>page.locator(`#keyboard [data-action="${action}"]${value===undefined?'':`[data-value="${value}"]`}`).first();
  const reset=()=>page.evaluate(()=>voxfixStartInput(1,false,0));
  const type=async raw=>{for(const c of raw)await key('letter',c.toUpperCase()).click();};
  await page.evaluate(()=>{
    window.experience={elapsed:[],replacements:0,row:document.querySelector('.key-rows')};
    document.addEventListener('pointerdown',event=>{experience.letter=Boolean(event.target.closest('[data-action="letter"]'));},true);
    document.addEventListener('pointerup',()=>{
      if(!experience.letter)return;experience.letter=false;
      const start=performance.now();
      requestAnimationFrame(()=>{
        const next=document.querySelector('.key-rows');if(next!==experience.row)experience.replacements++;
        experience.row=next;experience.elapsed.push(performance.now()-start);
      });
    },true);
  });
  await type('nihaomingtianshangwu');
  await page.waitForFunction(()=>experience.elapsed.length===20);
  const metrics=await page.evaluate(()=>{
    const elapsed=experience.elapsed.slice().sort((a,b)=>a-b);
    return {scope:'Desktop browser automated pointer-up to next frame; not K60 touch latency',keys:elapsed.length,keyRowReplacements:experience.replacements,p50Ms:elapsed[Math.floor(elapsed.length*.5)],p95Ms:elapsed[Math.ceil(elapsed.length*.95)-1]};
  });
  await mkdir(new URL('../.android/input-experience-20261102/',import.meta.url),{recursive:true});
  await writeFile(new URL(`../.android/input-experience-20261102/${baseline?'before':'after'}.json`,import.meta.url),JSON.stringify(metrics,null,2));
  console.log('MEASURE '+JSON.stringify(metrics));
  if(!baseline){
    assert.equal(metrics.keyRowReplacements,0,'Continuous candidate updates must keep letter keys mounted');
    await reset();await key('voice-toggle').click();
    assert.equal(await key('voice-stop').count(),0,'Sticky voice keeps a single ring control');
    for(const width of [320,393,411]){
      await page.setViewportSize({width,height:551});
      const mic=await key('voice-toggle').boundingBox(),rail=await page.locator('.keyboard-footer .cursor-zone').boundingBox();
      assert(mic.width>=44&&mic.height>=44&&rail.width>=140,'Ring remains reachable without crowding the rail');
    }
    await page.setViewportSize({width:393,height:551});
    await page.screenshot({path:new URL('../.android/input-experience-20261102/sticky-voice.png',import.meta.url).pathname.replace(/^\/([A-Za-z]:)/,'$1')});
    await key('numbers').click();await key('insert','8').click();
    assert.equal(await page.evaluate(()=>calls.filter(row=>['cancelVoice','stopVoice'].includes(row[0])).length),0,'Digits must not terminate sticky voice');
    assert.deepEqual(await page.evaluate(()=>calls.filter(row=>row[0]==='selectionEditing').at(-1)),['selectionEditing',true],'Number page pauses voice delivery');
    await key('symbols').click();await key('symbol','%').click();
    await key('letters').click();
    assert.deepEqual(await page.evaluate(()=>calls.filter(row=>row[0]==='selectionEditing').at(-1)),['selectionEditing',false],'Returning to letters releases the panel pause');
    await key('voice-toggle').click();
    assert.equal(await page.evaluate(()=>calls.filter(row=>row[0]==='stopVoice').length),1,'Stop submits once without discarding the session');
    await page.evaluate(()=>voxfixVoiceDone(''));
    await reset();
    const rail=await page.locator('.keyboard-footer .cursor-zone').boundingBox(),x=rail.x+rail.width/2,y=rail.y+rail.height/2;
    await page.mouse.move(x,y);await page.mouse.down();
    const burst=await page.evaluate(async({x,y})=>{
      const zone=document.querySelector('.keyboard-footer .cursor-zone'),read=zone.getBoundingClientRect.bind(zone);let reads=0;
      zone.getBoundingClientRect=()=>{reads++;return read();};
      for(let i=1;i<=24;i++)document.querySelector('#keyboard').dispatchEvent(new PointerEvent('pointermove',{bubbles:true,pointerId:1,pointerType:'mouse',clientX:x+i,clientY:y}));
      const immediate=reads;await new Promise(requestAnimationFrame);await new Promise(requestAnimationFrame);
      zone.getBoundingClientRect=read;return {immediate,frames:reads};
    },{x,y});
    assert.deepEqual(burst,{immediate:0,frames:1},'A burst of movement paints the latest cursor indicator once per frame');
    await page.mouse.up();
    assert(!await page.locator('.keyboard-footer .cursor-zone').evaluate(node=>node.classList.contains('dragging')),'Release clears movement feedback');
    await reset();await key('clipboard').click();
    await page.locator('.clip-list').evaluate(node=>node.scrollTop=320);
    const scroll=await page.locator('.clip-list').evaluate(node=>node.scrollTop);
    await page.locator('.clip-text').nth(4).dispatchEvent('contextmenu');await key('context-edit').click();
    assert.equal(await page.locator('#snippet-form button[type=submit]').textContent(),'保存');
    assert.equal(await page.locator('#snippet-form button[type=submit]').evaluate(node=>getComputedStyle(node,'::after').content),'none','Save has no extra explanatory line');
    await key('language').click();await page.locator('#snippet-content').fill('取消的修改');
    await page.locator('#snippet-cancel').click();
    assert.equal(await page.locator('.clip-list').evaluate(node=>node.scrollTop),scroll,'Cancel restores the source list position');
    assert.equal(await key('letter','Q').count(),0,'Cancel returns to the source panel');
    await key('return').click();assert.equal(await key('letter','Q').count(),1,'Local editing language does not leak into host typing');
    await reset();await type('mingtian');
    await page.waitForSelector('.clipboard-slot.has-reference');await key('clipboard-reference').click();
    await page.waitForTimeout(220);
    const reference=await page.locator('.clipboard-result').first().boundingBox();
    await page.mouse.move(reference.x+reference.width/2,reference.y+reference.height/2);await page.mouse.down();await page.waitForTimeout(600);await page.mouse.up();
    await page.screenshot({path:new URL('../.android/input-experience-20261102/editor.png',import.meta.url).pathname.replace(/^\/([A-Za-z]:)/,'$1')});
    assert.equal(await page.locator('#snippet-form button[type=submit]').textContent(),'保存');
    await key('language').click();await page.locator('#snippet-cancel').click();
    assert.equal(await key('candidate','明天').count(),1,'Cancel restores original Chinese composition');
    await page.waitForSelector('.clipboard-results',{state:'visible'});
    assert(await page.locator('.clipboard-results').isVisible(),'Cancel restores the expanded reference source');
    await page.screenshot({path:new URL('../.android/input-experience-20261102/reference-return.png',import.meta.url).pathname.replace(/^\/([A-Za-z]:)/,'$1')});
    await reset();await key('clipboard').click();
    await page.locator('.clip-text').first().dispatchEvent('contextmenu');await key('context-edit').click();
    await page.locator('#snippet-content').fill('跨输入框保留的草稿');
    await reset();await key('language').click();await key('clipboard').click();
    await page.locator('.clip-text').first().dispatchEvent('contextmenu');await key('context-edit').click();
    assert.equal(await page.locator('#snippet-content').inputValue(),'跨输入框保留的草稿');
    await key('language').click();await page.locator('#snippet-cancel').click();await key('return').click();
    assert.equal(await key('letter','q').count(),1,'Resumed draft returns to the current host language, not the old host language');
    assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('voxfix-keyboard-v1')).language),'en','Local language changes must not overwrite the host preference');
    console.log('PASS: stable keys, sticky voice digits/symbols/ring, frame-coalesced cursor, simple save and source restoration');
  }
  assert.deepEqual(errors,[]);
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
