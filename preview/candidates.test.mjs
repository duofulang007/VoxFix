import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
const { chromium } = createRequire(import.meta.url)('playwright');
const words=['您好','您好呀','您好朋友','您好很高兴认识你','您','你好','你好呀','你好朋友','你好世界','您好大家好','你好很高兴认识你','你好欢迎回来'];
const server=createServer(async(req,res)=>{
  try{
    const path=req.url==='/'?'index.html':req.url.slice(1);
    res.setHeader('Content-Type',path.endsWith('.mjs')?'text/javascript':path.endsWith('.css')?'text/css':path.endsWith('.json')?'application/json':'text/html');
    if(path==='pinyin-data.json')res.end(JSON.stringify({nin:words.map((word,i)=>[word,100-i])}));
    else if(path==='pinyin-initials.json')res.end('{}');
    else if(path==='phrase-packs.json')res.end('{}');
    else res.end(await readFile(new URL(path==='bundle.html'?'../键盘试用页.html':path,import.meta.url)));
  }catch{res.writeHead(404);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({channel:'msedge',headless:true});
try{
  const page=await browser.newPage({viewport:{width:1000,height:1000},hasTouch:true});
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(()=>document.querySelector('#pinyin-status').textContent.includes('已就绪'));
  await page.locator('#editor').pressSequentially('nin');
  const metrics=await page.locator('.candidate-visible').evaluate(row=>({
    words:row.querySelectorAll('[data-action="candidate"]:not(.clipboard-displaced)').length,scrollWidth:row.scrollWidth,width:row.clientWidth,
    buttons:[...row.querySelectorAll('[data-action="candidate"]:not(.clipboard-displaced)')].map(b=>({text:b.textContent,width:b.clientWidth,content:b.scrollWidth,ellipsis:getComputedStyle(b).textOverflow}))
  }));
  assert(metrics.buttons.every(b=>b.ellipsis!=='ellipsis'&&b.width>=b.content),'candidate text must be fully displayed');
  assert.equal(metrics.words,words.length,'all available candidates must be reachable in the strip');
  assert(metrics.scrollWidth>metrics.width,'long candidate strip must scroll');
  const row=page.locator('.candidate-visible'),box=await row.boundingBox();
  await page.mouse.move(box.x+box.width-20,box.y+box.height/2);
  await page.mouse.down();
  await page.mouse.move(box.x+20,box.y+box.height/2,{steps:12});
  await page.mouse.up();
  assert(await row.evaluate(el=>el.scrollLeft)>100,'left drag must reveal later candidates');
  assert.equal(await page.locator('#editor').inputValue(),'nin','drag must not commit a candidate');
  await page.locator('.candidate-expand').click();
  // 官方展开候选时顶部候选栏被面板整体取代（K60 实测：面板顶边即键盘顶边），不再重复显示候选条。
  assert.equal(await page.locator('.candidate-visible').count(),0,'expanded candidates must replace the strip');
  assert.equal(await page.locator('.candidate-card').count(),1,'expanded panel uses the official white card');
  assert.equal(await page.locator('.panel-pinyin').count(),1,'expanded panel keeps the nearby-pinyin column');
  assert.equal(await page.locator('.panel-actions .panel-key').count(),5,'expanded panel keeps the five-key action column');
  assert(await page.locator('.candidate-grid button').evaluateAll(buttons=>buttons.every(b=>b.scrollWidth<=b.clientWidth)),'expanded candidates must fit their content');
  await page.locator('.panel-actions [data-action="more-candidates"]').click();
  await page.waitForTimeout(120);
  assert.equal(await page.locator('.candidate-visible').count(),1,'closing the panel brings the strip back');
  await row.evaluate(el=>el.scrollLeft=0);
  const cdp=await page.context().newCDPSession(page);
  const x=box.x+box.width-20,y=box.y+box.height/2;
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y}]});
  for(let i=1;i<=12;i++){
    await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:x-(box.width-40)*i/12,y}]});
    await page.waitForTimeout(20);
  }
  await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  await page.waitForTimeout(200);
  assert(await row.evaluate(el=>el.scrollLeft)>100,'native touch swipe must reveal later candidates');
  assert.equal(await page.locator('#editor').inputValue(),'nin','touch swipe must not commit a candidate');
  await row.evaluate(el=>el.scrollLeft=el.scrollWidth);
  await row.locator('button').last().click();
  assert.equal(await page.locator('#editor').inputValue(),words.at(-1),'later candidate remains selectable');
  for(const width of [320,360])for(const count of [3,9]){
    await page.evaluate(({count})=>localStorage.setItem('voxfix-layout-v1',JSON.stringify({candidateCount:count,candidateFont:22})),{count});
    await page.reload();
    await page.waitForFunction(()=>document.querySelector('#pinyin-status').textContent.includes('已就绪'));
    await page.locator('#phone').evaluate((el,width)=>el.style.width=width+'px',width);
    await page.locator('#editor').pressSequentially('nin');
    assert.equal(await row.evaluate(el=>getComputedStyle(el).getPropertyValue('--candidate-count')),'3','Imported density cannot change the fixed layout');
    assert(await row.locator('button').evaluateAll(buttons=>buttons.every(b=>b.clientWidth>=b.scrollWidth)),'text fits at narrow widths and largest font');
    await row.evaluate(el=>el.scrollLeft=el.scrollWidth);
    await page.locator('#editor').pressSequentially('h');
    assert.equal(await row.evaluate(el=>el.scrollLeft),0,'new composition resets candidate strip');
  }
  await page.evaluate(()=>localStorage.clear());
  await page.goto(`http://127.0.0.1:${server.address().port}/bundle.html`);
  await page.waitForFunction(()=>document.querySelector('#pinyin-status').textContent.includes('已就绪'));
  await page.locator('#editor').pressSequentially('nin');
  assert(await row.locator('button').count()>5,'bundled version includes later candidates');
  assert(await row.locator('button').evaluateAll(buttons=>buttons.every(b=>b.clientWidth>=b.scrollWidth)),'bundled candidate text fits');
  await page.locator('#editor').fill('');
  await page.locator('#editor').pressSequentially('srf');
  await page.locator('#phone').screenshot({path:new URL('../docs/candidate-scroll.png',import.meta.url).pathname.slice(1)});
  const native=await browser.newPage({viewport:{width:411,height:351},hasTouch:true});
  const errors=[];native.on('pageerror',error=>errors.push(error.message));
  await native.addInitScript(()=>{
    window.calls=[];
    window.VoxFixIme=Object.fromEntries(['compose','commit','completeEnglish','copy','delete','enter','hide','switcher','startVoice','startLocalVoice','stopVoice','cancelVoice','moveCursor','cursorPosition','gestureStart','gestureMove','gestureEnd','edit','ready','resize','refreshClipboard','importClipboard'].map(name=>[name,(...args)=>calls.push([name,...args])]));
    VoxFixIme.voiceReady=()=>true;VoxFixIme.pinyinReady=()=>true;
    VoxFixIme.pinyinCandidates=raw=>JSON.stringify({preedit:raw,words:['你好','你好吗','你好很高兴认识你','你好很高兴认识你欢迎回来我们明天下午三点在咖啡店见面','你','呢',...Array.from({length:76},(_,i)=>String.fromCodePoint(0x4e00+i))]});
    VoxFixIme.pinyinChoose=(raw,word)=>{calls.push(['pinyinChoose',raw,word]);return JSON.stringify({committed:true,remaining:'haoma'});};
  });
  await native.goto(`http://127.0.0.1:${server.address().port}/`);
  await native.waitForFunction(()=>typeof voxfixReset==='function'&&document.querySelector('#pinyin-status').textContent.includes('已就绪'));
  const type=async raw=>{for(const letter of raw)await native.locator(`[data-action="letter"][data-value="${letter.toUpperCase()}"]`).click();};
  await type('nihaoma');
  assert.equal(await native.locator('.candidate-expand').count(),1,'official strip has one expand control');
  assert.equal(await native.locator('.candidate-visible button').first().evaluate(el=>getComputedStyle(el).fontSize),'22px');
  assert.equal(await native.locator('.candidate-visible button').first().evaluate(el=>getComputedStyle(el).paddingLeft),'17px');
  await native.locator('.candidate-expand').click();
  assert.deepEqual(await native.locator('.pinyin-item').allTextContents(),['nin','niu','bi','ji'],'multi-syllable alternatives match the measured nihao case');
  const geometry=await native.evaluate(()=>{
    const card=document.querySelector('.candidate-card'),pinyin=document.querySelector('.pinyin-item'),grid=document.querySelector('.candidate-grid'),actions=document.querySelector('.panel-actions');
    return {height:card.clientHeight,top:card.getBoundingClientRect().top,font:getComputedStyle(grid.firstElementChild).fontSize,pinyinFont:getComputedStyle(pinyin).fontSize,gap:getComputedStyle(actions).gap,border:getComputedStyle(grid.firstElementChild).borderRightWidth};
  });
  assert.equal(geometry.height,285);assert.equal(geometry.top,2);assert.equal(geometry.font,'22px');assert.equal(geometry.pinyinFont,'16px');assert.equal(geometry.gap,'6.4px');assert.equal(geometry.border,'0px');
  const long=native.locator('.candidate-grid [data-value="你好很高兴认识你"]');
  assert(await long.evaluate(el=>el.clientWidth>=el.scrollWidth),'long phrase uses enough columns without clipping');
  assert((await long.boundingBox()).width>(await native.locator('.candidate-grid [data-value="你好"]').boundingBox()).width,'long phrase expands beyond the short-word cell');
  const up=native.locator('[data-action="candidate-page"][data-value="up"]'),down=native.locator('[data-action="candidate-page"][data-value="down"]');
  assert(await up.isDisabled());assert(await down.isEnabled());
  await down.click();await native.waitForFunction(()=>document.querySelector('.candidate-grid').scrollTop>200);
  assert(await up.isEnabled());
  await native.locator('.candidate-grid').evaluate(el=>el.scrollTop=el.scrollHeight);
  await native.waitForFunction(()=>document.querySelector('[data-action="candidate-page"][data-value="down"]').disabled);
  await native.locator('[data-action="candidate-mode"]').click();
  assert.equal(await native.locator('[data-action="candidate-mode"]').getAttribute('aria-pressed'),'true');
  assert(await native.locator('.candidate-grid button').evaluateAll(items=>items.every(el=>/^[\u3400-\u9fff]$/u.test(el.textContent))),'character mode shows actual single-character candidates');
  await native.locator('[data-action="candidate-mode"]').click();
  assert.equal(await native.locator('.candidate-grid [data-value="你好"]').count(),1,'word mode restores the original list');
  await native.locator('[data-action="candidate-mode"]').click();
  await native.locator('.candidate-grid [data-value="你"]').click();
  assert((await native.evaluate(()=>calls)).some(call=>call[0]==='pinyinChoose'&&call[1]==='nihaoma'&&call[2]==='你'),'character selection keeps the native partial-selection path');
  await native.evaluate(()=>voxfixReset());
  await type('xi');await native.locator('[data-action="split"]').click();await type('an');
  await native.locator('.candidate-expand').click();
  await native.locator('.pinyin-item').first().click();
  const composed=(await native.evaluate(()=>calls)).filter(call=>call[0]==='compose').at(-1)[1];
  assert(composed.endsWith("'an"),'nearby-pinyin replacement preserves an explicit syllable boundary');
  for(const raw of ['ning','niang']){
    await native.evaluate(()=>voxfixReset());await type(raw);await native.locator('.candidate-expand').click();
    const positions=await native.locator('.pinyin-item').evaluateAll(items=>items.map(item=>{
      const range=document.createRange();range.selectNodeContents(item);
      const text=range.getBoundingClientRect(),cell=item.getBoundingClientRect();
      return {word:item.textContent,offset:Math.abs(text.x+text.width/2-cell.x-cell.width/2),inside:text.left>=cell.left&&text.right<=cell.right};
    }));
    assert(positions.length>0,'real nearby-pinyin buttons must be present');
    assert(positions.every(item=>item.offset<1&&item.inside),'nearby pinyin must be centred and complete: '+JSON.stringify(positions));
  }
  for(const width of [320,375,411,740]){
    await native.setViewportSize({width,height:351});
    await native.locator('[data-action="more-candidates"]').click();await native.locator('.candidate-expand').click();
    assert(await native.locator('.candidate-grid button').evaluateAll(items=>items.every(el=>el.scrollWidth<=el.clientWidth)),'panel candidates remain complete at '+width+'px');
  }
  assert.deepEqual(errors,[]);await native.close();
  console.log('PASS: complete candidate text, drag/swipe, official typography/geometry, word/character switch, paging boundaries, native partial selection, explicit pinyin boundary and bundled version');
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
