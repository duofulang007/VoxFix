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
// 字母键长按悬停选字：只回归这一条链路。全部用真实触摸事件 + 实测浮层/命中区坐标，
// 不看候选数组顺序就下结论——浮层顺序、默认档、位移判定、松手结算都按屏幕实测坐标核对。
async function check(name,run){
  const page=await browser.newPage({viewport:{width:393,height:351},hasTouch:true}),errors=[];
  page.setDefaultTimeout(4000);page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(()=>{
    window.calls=[];
    window.VoxFixIme=Object.fromEntries(['compose','commit','completeEnglish','copy','delete','enter','hide','switcher','startVoice','startLocalVoice','stopVoice','cancelVoice','moveCursor','cursorPosition','gestureStart','gestureMove','gestureEnd','haptic','edit','ready','resize','refreshClipboard','importClipboard','clipHint'].map(name=>[name,(...args)=>window.calls.push([name,...args])]));
    VoxFixIme.voiceReady=()=>true;VoxFixIme.pinyinReady=()=>true;
    VoxFixIme.pinyinCandidates=()=>JSON.stringify({words:['你好','你','好']});
    VoxFixIme.pinyinChoose=()=>JSON.stringify({committed:true,remaining:''});
    VoxFixIme.commitAccepted=()=>JSON.stringify({committed:true});
  });
  const touch=await page.context().newCDPSession(page);
  const send=(type,x,y)=>touch.send('Input.dispatchTouchEvent',{type,touchPoints:type==='touchStart'||type==='touchMove'?[{x,y}]:[]});
  const calls=type=>page.evaluate(name=>window.calls.filter(row=>row[0]===name),type);
  // 实测浮层每一档的屏幕矩形：顺序、宽度、当前高亮档位置都以实际渲染为准。
  const options=()=>page.evaluate(()=>[...document.querySelectorAll('.key-popup button')].map(node=>{const rect=node.getBoundingClientRect();return {value:node.dataset.value,selected:node.classList.contains('selected'),left:rect.left,right:rect.right,top:rect.top,bottom:rect.bottom,width:rect.width,center:rect.left+rect.width/2};}));
  const selectedValue=()=>page.locator('.key-popup button.selected').getAttribute('data-value');
  const popupCount=()=>page.locator('.key-popup').count();
  const pointer=async(value)=>{
    const box=await page.locator(`#keyboard [data-action="letter"][data-value="${value}"]`).boundingBox();
    return {box,origin:{x:box.x+box.width/2,y:box.y+box.height/2}};
  };
  // press=true 时按住到浮层出现（420ms 阈值之后）。
  async function hold(value,{ms=470,release=false}={}){
    const point=await pointer(value);
    await send('touchStart',point.origin.x,point.origin.y);await page.waitForTimeout(ms);
    if(release){await send('touchEnd',point.origin.x,point.origin.y);await page.waitForTimeout(60);}
    return point;
  }
  const move=async(point,dx,dy=0)=>{await send('touchMove',point.origin.x+dx,point.origin.y+dy);await page.waitForTimeout(12);};
  const up=async point=>{await send('touchEnd',point.origin.x,point.origin.y);await page.waitForTimeout(60);};
  const keyboardBox=()=>page.locator('#keyboard').boundingBox();
  const commits=()=>calls('commit');
  try{
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForFunction(()=>typeof voxfixReset==='function'&&document.querySelector('#keyboard [data-action="letter"]'));
    await run({page,send,calls,options,selectedValue,popupCount,pointer,hold,move,up,keyboardBox,commits});
    assert.deepEqual(errors,[]);
    console.log('PASS '+name);
  }catch(error){failures.push(name+': '+error.message);console.log('FAIL '+name+': '+error.message.slice(0,240));}
  finally{try{await page.close();}catch{}}
}
try{
  await check('D 键浮层从左到右固定为大写/键帽提示/小写，首次出现即选中中间',async({page,options,selectedValue,hold,move,up,keyboardBox})=>{
    const point=await hold('D',{ms:200});
    assert.equal(await page.locator('.key-popup').count(),0,'长按阈值(420ms)之前不得出现浮层');
    await page.waitForTimeout(320);
    const rows=await options();
    assert.deepEqual(rows.map(row=>row.value),['D','@','d'],'D 键浮层顺序必须是 D｜@｜d');
    for(const row of rows)assert.equal(row.width,44,'每一档 44px 宽');
    assert(rows[0].right<=rows[1].left+0.01&&rows[1].right<=rows[2].left+0.01,'三档必须从左到右依次排列');
    assert(Math.abs(rows[0].top-rows[2].top)<0.5,'三档必须在同一行');
    assert.equal(rows[1].selected,true,'浮层出现时默认必须选中中间的 @，不能先亮左项再跳中间');
    assert.equal(rows[0].selected||rows[2].selected,false,'首帧不得同时高亮左项或右项');
    const keyboard=await keyboardBox();
    assert(rows[0].left>=keyboard.x-0.5&&rows[2].right<=keyboard.x+keyboard.width+0.5,'浮层必须完整落在键盘内');
    // 中间档被压在长按触点上：默认档与手指位置必须对应，不能各在一边。
    assert(Math.abs(rows[1].center-point.origin.x)<2,`中间档中心 ${rows[1].center} 应压在触点 ${point.origin.x}`);
    await move(point,0);
    assert.equal(await selectedValue(),'@','浮层出现后再收到移动事件，静止时仍必须是中间档');
    await up(point);
  });
  await check('其它字母键使用各自的键帽提示内容',async({options,selectedValue,hold,up})=>{
    for(const [letter,hint] of [['Q','1'],['S','!'],['L','?'],['N',';']]){
      const point=await hold(letter);
      const rows=await options();
      assert.deepEqual(rows.map(row=>row.value),[letter,hint,letter.toLowerCase()],`${letter} 键的顺序应为 ${letter}｜${hint}｜${letter.toLowerCase()}`);
      assert.equal(rows[1].selected,true,`${letter} 键默认应选中中间的 ${hint}`);
      await up(point);
    }
  });
  await check('静止与轻微抖动保持中间，只有明确左右移动才切换',async({selectedValue,hold,move,up})=>{
    const point=await hold('D');
    for(const dx of [0,4,-5,7,-7,3,-3]){
      await move(point,dx);
      assert.equal(await selectedValue(),'@',`相对落点位移 ${dx}px 时应稳定停在中间 @`);
    }
    await move(point,-40);
    assert.equal(await selectedValue(),'D','明确左移应选左侧大写');
    await move(point,40);
    assert.equal(await selectedValue(),'d','越过中间明确右移应选右侧小写');
    await move(point,0);
    assert.equal(await selectedValue(),'@','回到落点应回到中间');
    await up(point);
  });
  await check('切换边界有滞回，边界抖动不会来回跳',async({selectedValue,hold,move,up})=>{
    const point=await hold('D');
    await move(point,-26);
    assert.equal(await selectedValue(),'D');
    await move(point,-14);
    assert.equal(await selectedValue(),'D','回到边界带内不得立刻跳回中间');
    await move(point,-18);
    assert.equal(await selectedValue(),'D');
    await move(point,-11);
    assert.equal(await selectedValue(),'@','越过回中阈值才回中间');
    await move(point,-14);
    assert.equal(await selectedValue(),'@','刚回中后的抖动不得再次跳走');
    await move(point,-8);
    assert.equal(await selectedValue(),'@');
    await move(point,-26);
    assert.equal(await selectedValue(),'D','再次越过进入阈值才切回左档');
    await move(point,0);
    assert.equal(await selectedValue(),'@');
    await move(point,26);
    assert.equal(await selectedValue(),'d');
    await move(point,14);
    assert.equal(await selectedValue(),'d','右侧边界同样有滞回');
    await move(point,11);
    assert.equal(await selectedValue(),'@');
    await move(point,14);
    assert.equal(await selectedValue(),'@');
    await move(point,26);
    assert.equal(await selectedValue(),'d');
    await up(point);
  });
  await check('左右边缘键浮层防越界偏移后，默认档与左右切换手感一致',async({options,selectedValue,hold,move,up,keyboardBox})=>{
    const keyboard=await keyboardBox();
    for(const [letter,hint] of [['A','~'],['P','0']]){
      const point=await hold(letter);
      const rows=await options();
      assert.deepEqual(rows.map(row=>row.value),[letter,hint,letter.toLowerCase()]);
      assert(rows[0].left>=keyboard.x-0.5&&rows[2].right<=keyboard.x+keyboard.width+0.5,'防越界浮层必须留在键盘内');
      assert.equal(rows[1].selected,true,`${letter} 是边缘键，默认仍必须选中中间档`);
      // 边缘键的浮层被顶回键盘内，中间档不可能压在触点上——正因如此命中判定不能看浮层坐标。
      if(letter==='A')assert(rows[1].left>point.origin.x+8,`A 键浮层应被右推（中间档左沿 ${rows[1].left} > 触点 ${point.origin.x}）`);
      else assert(rows[1].right<point.origin.x-8,`P 键浮层应被左推（中间档右沿 ${rows[1].right} < 触点 ${point.origin.x}）`);
      await move(point,-30);
      assert.equal(await selectedValue(),letter,`${letter} 键左移仍应选左侧大写`);
      await move(point,30);
      assert.equal(await selectedValue(),letter.toLowerCase(),`${letter} 键右移仍应选右侧小写`);
      await move(point,0);
      assert.equal(await selectedValue(),hint,`${letter} 键回中仍应选中间档`);
      await up(point);
    }
  });
  await check('浮层重绘期间选择与位置都不变',async({page,options,selectedValue,hold,move,up})=>{
    const point=await hold('D');
    await move(point,-40);
    assert.equal(await selectedValue(),'D');
    const before=await options();
    await page.evaluate(()=>voxfixRedraw());
    await page.waitForTimeout(20);
    const after=await options();
    assert.deepEqual(after.map(row=>row.value),before.map(row=>row.value));
    assert.equal(after[0].selected,true,'重绘后必须保持原来选中的档');
    assert.equal(after[0].left,before[0].left,'重绘不得移动浮层');
    assert.equal(after[0].top,before[0].top);
    await page.evaluate(()=>window.dispatchEvent(new Event('resize')));
    await page.waitForTimeout(20);
    assert.equal(await selectedValue(),'D','宿主重排引起的重绘不得改变选项');
    await move(point,0);
    assert.equal(await selectedValue(),'@');
    await up(point);
  });
  await check('松手只提交当前档一次，取消/失焦/切换输入框都不提交也不残留',async({page,send,calls,commits,popupCount,selectedValue,hold,move,up})=>{
    for(const [dx,expected] of [[0,'@'],[-40,'D'],[40,'d']]){
      const point=await hold('D');
      if(dx)await move(point,dx);
      assert.equal(await selectedValue(),expected);
      const before=(await commits()).length;
      await up(point);
      const after=await commits();
      assert.equal(after.length-before,1,`松手应只提交一次（${expected}）`);
      assert.equal(after.at(-1)[1],expected);
      assert.equal(await popupCount(),0,'松手后不得残留浮层');
      assert.equal(await page.locator('.key.pressed').count(),0,'松手后不得残留按压态');
    }
    const cancelled=await hold('D');
    await move(cancelled,-40);
    let before=(await commits()).length;
    await send('touchCancel');
    await page.waitForTimeout(60);
    assert.equal((await commits()).length,before,'取消不得提交');
    assert.equal(await popupCount(),0,'取消不得残留浮层');
    const blurred=await hold('D');
    await move(blurred,40);
    before=(await commits()).length;
    await page.evaluate(()=>window.dispatchEvent(new Event('blur')));
    await page.waitForTimeout(30);
    assert.equal((await commits()).length,before,'失焦不得提交');
    assert.equal(await popupCount(),0,'失焦不得残留浮层');
    await up(blurred);
    assert.equal((await commits()).length,before,'失焦后松手也不得补交');
    const switched=await hold('D');
    await move(switched,-40);
    before=(await commits()).length;
    await page.evaluate(()=>voxfixStartInput(1,false));
    await page.waitForTimeout(30);
    assert.equal((await commits()).length,before,'切换输入框不得提交');
    assert.equal(await popupCount(),0,'切换输入框不得残留浮层');
    await up(switched);
    assert.equal((await commits()).length,before,'切换输入框后松手也不得补交');
    const hidden=await hold('D');
    await move(hidden,-40);
    before=(await commits()).length;
    await page.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,get:()=>true});document.dispatchEvent(new Event('visibilitychange'));});
    await page.waitForTimeout(30);
    assert.equal((await commits()).length,before,'进入后台不得提交');
    assert.equal(await popupCount(),0,'进入后台不得残留浮层');
    assert.equal(await page.locator('.key.pressed').count(),0,'进入后台不得残留按压态');
    await up(hidden);
    assert.equal((await commits()).length,before,'进入后台后松手也不得补交');
  });
  await check('普通点按、长按阈值前移动与上滑提示都不受影响',async({page,send,calls,commits,popupCount,pointer,hold})=>{
    const tap=await pointer('D');
    await send('touchStart',tap.origin.x,tap.origin.y);
    await page.waitForTimeout(120);
    await send('touchMove',tap.origin.x-30,tap.origin.y);
    await page.waitForTimeout(60);
    await send('touchEnd',tap.origin.x-30,tap.origin.y);
    await page.waitForTimeout(60);
    assert.equal(await popupCount(),0,'长按阈值之前的移动不得弹出浮层');
    assert((await calls('compose')).some(row=>row[1]==='d'),'普通点按仍应输入字母');
    // 上滑仍然直接输入键帽提示。
    const slide=await pointer('D');
    await send('touchStart',slide.origin.x,slide.origin.y);
    await page.waitForTimeout(140);
    await send('touchMove',slide.origin.x,slide.origin.y-46);
    await page.waitForTimeout(60);
    await send('touchEnd',slide.origin.x,slide.origin.y-46);
    await page.waitForTimeout(60);
    assert.equal(await popupCount(),0,'上滑不应弹出长按浮层');
    assert((await commits()).some(row=>row[1]==='@'),'上滑仍应直接输入键帽上方的 @');
    // 长按松手若不发生位移，提交中间档；浮层只在长按期间存在。
    const long=await hold('D',{release:true});
    assert.equal(await popupCount(),0);
    assert((await commits()).at(-1)[1]==='@','长按不动松手应提交中间档');
    // 删除长按连删不受影响。
    const del=await page.locator('#keyboard [data-action="delete"]').boundingBox();
    await send('touchStart',del.x+del.width/2,del.y+del.height/2);
    await page.waitForTimeout(900);
    await send('touchEnd',del.x+del.width/2,del.y+del.height/2);
    await page.waitForTimeout(60);
    assert((await calls('delete')).length>=4,'删除长按连删仍要重复');
    assert.equal(await popupCount(),0,'删除键不得弹出字母浮层');
    // 空格长按语音不受影响。
    const space=await page.locator('#keyboard [data-action="space"]').boundingBox();
    await send('touchStart',space.x+space.width/2,space.y+space.height/2);
    await page.waitForTimeout(360);
    assert.equal((await calls('startVoice')).length,1,'空格长按仍应进入语音');
    await send('touchEnd',space.x+space.width/2,space.y+space.height/2);
    await page.waitForTimeout(60);
    assert.equal(await popupCount(),0);
  });
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
assert.deepEqual(failures,[]);
