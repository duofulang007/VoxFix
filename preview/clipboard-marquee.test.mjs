// 剪贴板条目超长时向左拖动＝跟手查看全部内容（不自动播放）：
// 只有内容确实溢出显示区才判定；换行在显示层合并为空格，data-value 始终是原始文本；
// 拖动全程抑制误触粘贴，松手停在当前位置，列表纵向滚动即复位；管理多选模式不拖。
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
// LONG2 溢出量适中（约 200px），视口内一屏就能拖到终点验证夹紧；LONG 更长，验证一般跟手。
const LONG='长条目内容'.repeat(60),LONG2='长条目内容'.repeat(9),SHORT='软件问题已记录',MULTI='第一行\n第二行\n第三行';
const failures=[];
const seed={clips:[LONG2,LONG,SHORT,MULTI,...Array.from({length:24},(_,i)=>'记录'+i+'：明天下午开会。')],phrases:[{name:'常用语',items:[]}]};
async function scenario(name,run){
  const context=await browser.newContext({viewport:{width:393,height:600},hasTouch:true}),page=await context.newPage(),errors=[];
  page.setDefaultTimeout(5000);page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(seedText=>{
    window.calls=[];window.accept=true;
    window.VoxFixIme=Object.fromEntries(['compose','commit','delete','enter','hide','switcher','ready','resize','refreshClipboard','importClipboard','clipHint','cursorPosition','moveCursor','searchFocus','gestureStart','gestureEnd','gestureClearSteps','gestureLock','haptic'].map(name=>[name,(...args)=>calls.push([name,...args])]));
    VoxFixIme.pinyinReady=()=>true;VoxFixIme.voiceReady=()=>true;
    VoxFixIme.commitAccepted=text=>{calls.push(['commitAccepted',text]);return JSON.stringify({committed:true})};
    localStorage.setItem('voxfix-snippets-v1',seedText);
  },JSON.stringify(seed));
  await page.goto(`http://127.0.0.1:${server.address().port}`);
  await page.waitForFunction(()=>typeof voxfixStartInput==='function'&&document.querySelector('#pinyin-status').textContent.includes('已就绪'));
  await page.locator('#keyboard [data-action="clipboard"]').first().click();
  try{
    await run(page);
    assert.deepEqual(errors,[]);
    console.log('PASS '+name);
  }catch(error){failures.push(name+': '+error.stack);console.error('FAIL '+name+': '+error.message)}
  finally{await context.close()}
}
const firstRow=page=>page.locator('.clip-text[data-action="paste"]').first();
const transform=locator=>locator.evaluate(n=>n.querySelector('.clip-row-text')?.style.transform||'');
const offsetOf=v=>Math.abs(parseFloat((v.match(/-?[\d.]+(?=px)/)||['0'])[0]));
// 按住后向左拖 dx 像素，返回拖动中的位移；是否抬手、抬在哪里由调用方决定
//（抬在条目上会触发点击语义，抬在键盘外则不触发）。
// 两段式移动：先精确落在 24px 门槛上再拖到目标，保证判定锚点确定、位移可精确断言。
const press=async(locator,dx)=>{
  const box=await locator.boundingBox(),y=box.y+box.height/2,x0=box.x+box.width-24;
  const page=locator.page();
  await page.mouse.move(x0,y);await page.mouse.down();
  await page.mouse.move(x0+Math.sign(dx)*24,y,{steps:2});
  await page.mouse.move(x0+dx,y,{steps:8});
  return transform(locator);
};
// 不跨过门槛的原始按下移动，专用于「门槛内不判定」的探测。
const rawPress=async(locator,dx)=>{
  const box=await locator.boundingBox(),y=box.y+box.height/2,x0=box.x+box.width-24;
  const page=locator.page();
  await page.mouse.move(x0,y);await page.mouse.down();
  await page.mouse.move(x0+dx,y,{steps:2});
  return transform(locator);
};
const releaseOn=async locator=>{await locator.page().mouse.up();};
// 抬在键盘上方的宿主页面区（y=180），不落在任何 data-action 元素上。
const releaseOff=async locator=>{const box=await locator.boundingBox();await locator.page().mouse.move(box.x+box.width/2,180);await locator.page().mouse.up();};
// 主列表粘贴走 nativeIme.commit，搜索结果粘贴走 commitAccepted：两者都算提交。
const commits=page=>page.evaluate(()=>calls.filter(r=>r[0]==='commit'||r[0]==='commitAccepted').map(r=>r[1]));

await scenario('long entry follows finger, accumulates across drags, suppresses accidental paste',async page=>{
  const item=firstRow(page);
  assert.equal(await item.getAttribute('data-value'),LONG2);
  // 门槛内（<24px）不判定：不进入拖动查看；抬手在条目外，不触发粘贴，页面不动。
  await rawPress(item,-20);
  assert.equal(await item.evaluate(n=>n.classList.contains('clip-scrolling')),false,'门槛内不得进入拖动查看');
  assert.equal(await transform(item),'');
  await releaseOff(item);
  // 超过门槛进入跟手：位移＝横移量减去 24px 门槛，随手指线性增长。
  await press(item,-80);
  assert.equal(await transform(item),'translateX(-56px)','位移应等于横移量减去 24px 门槛');
  const max=await item.evaluate(n=>n.scrollWidth-n.clientWidth);
  assert(max>150,'长条目必须确实溢出显示区');
  const box=await item.boundingBox(),y=box.y+box.height/2,x0=box.x+box.width-24;
  await page.waitForTimeout(150);
  await item.page().mouse.up();
  // 第二次拖动从上次停留的位移续拖（56 + (60-24)＝92），不清零。
  await press(item,-60);
  assert.equal(await transform(item),'translateX(-92px)','多次拖动必须累加记忆，不得回头');
  // 继续向左拖到视口左缘，位移夹紧在终点 max，不得超出。
  await item.page().mouse.move(box.x-300,y,{steps:10});
  assert.equal(await transform(item),`translateX(${-max}px)`,'拖到末尾后停在终点，不得超出');
  // 拖回起按点再静置抬手（无惯性）：位移回到 56-(0+24)＝32，松手保留；点击被抑制。
  await item.page().mouse.move(x0,y,{steps:6});
  await page.waitForTimeout(150);
  await item.page().mouse.up();
  assert.equal(await transform(item),'translateX(-32px)','松手保留本次手势后的位置');
  assert.deepEqual(await commits(page),[],'拖动查看不能误触粘贴');
  // 之后重新点按＝正常粘贴原文。
  await page.waitForTimeout(600);await item.click();
  assert.deepEqual(await commits(page),[LONG2]);
});

await scenario('multiline entry keeps original newlines, reads as one line, pastes original',async page=>{
  const multi=page.locator('.clip-text',{hasText:'第二行'});
  assert.equal(await multi.count(),1);
  assert.equal(await multi.getAttribute('data-value'),MULTI,'data-value 必须保留原始换行');
  assert.equal(await multi.evaluate(n=>n.querySelector('.clip-row-text').textContent),MULTI,'DOM 文本必须是原文，换行由 CSS 合并显示');
  assert.equal(await multi.evaluate(n=>getComputedStyle(n).whiteSpace),'nowrap');
  const height=await multi.evaluate(n=>n.clientHeight);
  await press(multi,-120);
  assert.equal(await multi.evaluate(n=>n.classList.contains('clip-scrolling')),false,'不溢出的多行条目不得进入拖动查看');
  assert.equal(await transform(multi),'');
  assert.equal(await multi.evaluate(n=>n.clientHeight),height,'多行条目在列表内保持单行高度');
  // 松手在条目上＝照常粘贴，提交原始多行文本。
  await releaseOn(multi);
  assert.deepEqual(await commits(page),[MULTI],'粘贴提交原始多行文本');
});

await scenario('short entry ignores horizontal drag and pastes normally',async page=>{
  const short=page.locator(`.clip-text[data-value="${SHORT}"]`);
  assert.equal(await short.count(),1);
  await press(short,-120);
  assert.equal(await short.evaluate(n=>n.classList.contains('clip-scrolling')),false,'未溢出条目不得进入拖动查看');
  assert.equal(await transform(short),'');
  await releaseOn(short);
  assert.deepEqual(await commits(page),[SHORT],'未溢出条目的滑动松手仍照常粘贴');
});

await scenario('reading offsets persist on the page and reset on re-entry',async page=>{
  const item=firstRow(page),other=page.locator(`.clip-text[data-value="${LONG}"]`),list=page.locator('.clip-list');
  await press(item,-200);
  assert.equal(await transform(item),'translateX(-176px)');
  await page.waitForTimeout(150);await releaseOn(item);
  // 另一条目有自己独立的位移，互不影响（先拖第二条，再做滚动验证）。
  await press(other,-100);
  assert.equal(await transform(other),'translateX(-76px)');
  await page.waitForTimeout(150);await releaseOn(other);
  assert.equal(await transform(item),'translateX(-176px)','条目之间的记忆互相独立');
  // 列表纵向滚动不清零：同页面内保留阅读位置。
  await list.evaluate(n=>{n.scrollTop=120;n.dispatchEvent(new Event('scroll'))});
  assert.equal(await transform(item),'translateX(-176px)','列表滚动不得清掉阅读位置');
  // 返回键盘后再点开剪贴板＝重置到开头。
  await page.locator('#keyboard [data-action="return"]').first().click();
  await page.locator('#keyboard [data-action="clipboard"]').first().click();
  assert.equal(await transform(firstRow(page)),'','重新点开剪贴板必须回到开头');
  assert.equal(await firstRow(page).evaluate(n=>n.classList.contains('clip-scrolling')),false);
});

await scenario('manage mode does not drag',async page=>{
  await page.locator('#keyboard [data-action="clip-manage-menu"]').first().click();
  await page.locator('#keyboard [data-action="clip-manage"]').first().click();
  const pick=page.locator('.clip-pick').first();
  assert(await pick.count(),'管理模式出现多选条目');
  await press(pick,-200);
  assert.equal(await pick.evaluate(n=>n.classList.contains('clip-scrolling')),false,'管理模式不得进入拖动查看');
  assert.equal(await transform(pick),'');
  await releaseOn(pick);
});

// 真机是触摸输入，与鼠标走的浏览器手势判定不同：用 CDP 派发真实触摸序列复现设备路径。
// 触摸事件会被渲染器按帧合并，判定锚点落在 24–40px 之间不可精确断言；
// 因此断言线性度：两个读数之间的位移差必须精确等于手指移动差（这才是跟手契约）。
await scenario('touch drag follows finger (device path)',async page=>{
  const item=firstRow(page),box=await item.boundingBox();
  const y=box.y+box.height/2,x0=box.x+box.width-24;
  const client=await page.context().newCDPSession(page);
  const moveTo=async(x,page)=>{await client.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x,y}]});await page.waitForTimeout(30);};
  await client.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:x0,y}]});
  await moveTo(x0-40,page);
  await moveTo(x0-100,page);const t1=offsetOf(await transform(item));
  await moveTo(x0-160,page);const t2=offsetOf(await transform(item));
  await page.waitForTimeout(150);
  await client.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  await client.detach();
  assert.equal(await item.evaluate(n=>n.classList.contains('clip-scrolling')),true,'触摸横拖必须进入拖动查看');
  assert.equal(Math.round(t2-t1),60,'两个读数间的位移差必须精确等于 60px 手指移动');
  assert(t2>100,'触摸位移必须跟手');
  assert.equal(offsetOf(await transform(item)),t2,'松手后保留阅读位置');
  assert.deepEqual(await commits(page),[],'触摸拖动不能误触粘贴');
});

// 松手惯性：快速拖动后松手，位移继续向前滚并指数衰减，最终停住（像候选栏）。
await scenario('release fling keeps scrolling with decay',async page=>{
  const item=firstRow(page);
  await press(item,-120);
  const atRelease=offsetOf(await transform(item));
  assert.equal(await item.evaluate(n=>n.classList.contains('clip-scrolling')),true,'拖动必须已进入判定态, atRelease='+atRelease);
  await item.page().mouse.up();
  await page.waitForTimeout(120);
  const moving=offsetOf(await transform(item));
  assert(moving>atRelease,'松手后惯性必须继续向前滚, atRelease='+atRelease+' moving='+moving);
  await page.waitForTimeout(2000);
  const s1=offsetOf(await transform(item));
  await page.waitForTimeout(150);
  const s2=offsetOf(await transform(item));
  assert.equal(s1,s2,'惯性衰减后必须停住');
  assert(s2>=moving,'惯性净位移应继续向前或到达终点');
  assert.deepEqual(await commits(page),[],'惯性滚动不能触发粘贴');
});

await browser.close();await new Promise(resolve=>server.close(resolve));
if(failures.length)throw Error(failures.join('\n'));
console.log('PASS clipboard marquee drag: threshold, finger-follow, clamp, paste suppression, newline merge, scroll reset, manage mode');
