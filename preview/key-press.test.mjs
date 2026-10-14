// 26 键按压反馈回归：对齐讯飞输入法小米版白皮的按下态。
// 官方依据（base.apk）：
//   btn_keyboard_key_normal.png  = #FFFFFF 不透明
//   btn_keyboard_key_pressed.png = rgb(202,208,220) alpha 115/255 = .451
//   btn_keyboard_func_key_normal.png  = 同一个 45% 冷灰（功能键常态就是按下色）
//   btn_keyboard_func_key_pressed.png = rgb(197,203,212) alpha 203/255 = .796
// 这些 9-patch 是**直接叠在键盘底** #EDEFF2 上（不看白色键帽），所以：
//   字母键按下等效 rgb(221,226,232)，功能键按下等效 rgb(205,211,217)。
// 官方是原生 state drawable 直接换图：无过渡、无缩放、无位移，按下 0 延迟。
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
const {chromium}=createRequire(import.meta.url)('playwright');

// Chromium 的 computed style 会把 alpha 四舍五入到两位小数（.451 → .45），
// 所以这里比对数值 + 容差，而不是比对字符串。
const KEY_PRESS_RGB=[202,208,220];
const KEY_PRESS_ALPHA=.451;
const FUNC_PRESS_RGB=[197,203,212];
const FUNC_PRESS_ALPHA=.796;
// 等效色是覆层叠在键盘底 rgb(237,239,242) 上的合成结果，浏览器又会把 alpha
// 舍入到两位小数，所以合成值允许 ±1 的通道误差；覆层本身则严格比对。
const KEY_PRESS_EFFECTIVE=[221,225,232];
const FUNC_PRESS_EFFECTIVE=[205,211,218];
const KEY_IDLE='rgb(255, 255, 255)';
const parseColor=value=>{
  const m=String(value).match(/rgba?\(([\d.]+), ([\d.]+), ([\d.]+)(?:, ([\d.]+))?\)/);
  assert(m,`无法解析颜色：${value}`);
  return {rgb:[+m[1],+m[2],+m[3]],alpha:m[4]===undefined?1:+m[4]};
};
const assertOverlay=(actual,expectedRgb,expectedAlpha,message)=>{
  const got=parseColor(actual);
  assert.deepEqual(got.rgb,expectedRgb,`${message}（RGB 不符，实得 ${actual}）`);
  assert(Math.abs(got.alpha-expectedAlpha)<=.01,`${message}（alpha 不符，实得 ${actual}）`);
};
const assertEffective=(actual,expected,message)=>{
  const got=parseColor(actual).rgb;
  assert(got.every((c,i)=>Math.abs(c-expected[i])<=1),`${message}（期望约 rgb(${expected.join(',')})，实得 ${actual}）`);
};

// ① 源码级：按键变色只能由 .pressed 驱动，不能再出现 :active
const css=await readFile(new URL('style.css',import.meta.url),'utf8');
assert.match(css,/--key-press:rgba\(202,208,220,\.451\)/,'style.css 应定义 --key-press 令牌');
assert.match(css,/--func-press:rgba\(197,203,212,\.796\)/,'style.css 应定义 --func-press 令牌');
assert.match(css,/\.key-row \.key\.pressed\{background:var\(--key-press/,'字母键按下应使用令牌');
assert.match(css,/\.key-row \.key\.function\.pressed\{background:var\(--func-press/,'功能键按下应使用令牌');
assert.match(css,/\.t9-grid \.key\.pressed\{background:var\(--key-press/,'九宫格按下应使用令牌');
const offenders=[...css.matchAll(/([^{}]+)\{([^}]*)\}/g)].filter(([,sel])=>/:active/.test(sel)&&/\.key(?![\w-])/.test(sel));
assert.deepEqual(offenders.map(([,sel])=>sel.trim()),[],'按键不可再用 :active 变色：Android WebView 上会残留');
assert(!css.includes('#c1d5e5'),'旧的高饱和蓝按下色应彻底移除');

const server=createServer(async(req,res)=>{
  const path=req.url==='/'?'index.html':req.url.slice(1);
  if(!/^[a-zA-Z0-9.-]+$/.test(path)){res.writeHead(404);res.end();return;}
  try{res.setHeader('Content-Type',path.endsWith('.mjs')?'text/javascript':path.endsWith('.css')?'text/css':path.endsWith('.json')?'application/json':'text/html');res.end(await readFile(new URL(path,import.meta.url)));}
  catch{res.writeHead(404);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({channel:'msedge',headless:true});
try{
  for(const native of [false,true]){
    const label=native?'原生 IME':'网页';
    const page=await browser.newPage({viewport:{width:native?411:1080,height:native?351:1000}}),errors=[];
    page.on('pageerror',e=>errors.push(e.message));
    await page.addInitScript(native=>{
      if(native){window.calls=[];window.VoxFixIme=Object.fromEntries(['compose','commit','completeEnglish','copy','delete','enter','hide','switcher','startVoice','startLocalVoice','stopVoice','cancelVoice','moveCursor','cursorPosition','edit','ready','resize','refreshClipboard','importClipboard'].map(n=>[n,(...a)=>calls.push([n,...a])]));VoxFixIme.voiceReady=()=>true;}
    },native);
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForFunction(()=>document.querySelector('.key-rows'));
    // 合成 PointerEvent 前把指针捕获打桩：真机上 pointerId 一定有效，
    // 合成事件没有真实指针，setPointerCapture 会抛错并中断整个按下分支。
    await page.evaluate(()=>{
      const kb=document.getElementById('keyboard');
      kb.setPointerCapture=()=>{};kb.releasePointerCapture=()=>{};
      window.__press=el=>{const r=el.getBoundingClientRect();el.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,cancelable:true,pointerId:1,button:0,buttons:1,clientX:r.left+r.width/2,clientY:r.top+r.height/2,pointerType:'touch',isPrimary:true}));};
      window.__release=el=>{const r=el.getBoundingClientRect();el.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,cancelable:true,pointerId:1,button:0,buttons:0,clientX:r.left+r.width/2,clientY:r.top+r.height/2,pointerType:'touch',isPrimary:true}));};
      window.__bg=el=>getComputedStyle(el).backgroundColor;
      // 把半透明按下色按官方模型合成到键盘底上，得到屏幕上真正看到的等效色。
      window.__effective=el=>{
        const m=getComputedStyle(el).backgroundColor.match(/rgba?\(([\d.]+), ([\d.]+), ([\d.]+)(?:, ([\d.]+))?\)/);
        const rgb=[+m[1],+m[2],+m[3]],a=m[4]===undefined?1:+m[4];
        const base=getComputedStyle(document.getElementById('keyboard')).backgroundColor.match(/rgb\(([\d.]+), ([\d.]+), ([\d.]+)\)/).slice(1).map(Number);
        return `rgb(${rgb.map((c,i)=>Math.round(c*a+base[i]*(1-a))).join(', ')})`;
      };
      window.__letters=()=>[...document.querySelectorAll('#keyboard .key')].filter(b=>b.dataset.action==='letter');
    });

    // ② 按下即变色、松手即回落，全程 0 延迟且无过渡
    const one=await page.evaluate(()=>{
      const kb=document.getElementById('keyboard'),k=window.__letters()[0];
      const out={idle:window.__bg(k),transition:getComputedStyle(k).transitionDuration};
      window.__press(k);
      out.during=window.__bg(k);           // 同一次 dispatch 内同步读取
      out.duringEffective=window.__effective(k);
      out.pressedNow=kb.querySelectorAll('.key.pressed').length;
      window.__release(k);
      const fresh=window.__letters()[0];   // 按键会重建键盘，必须重新取节点
      out.after=window.__bg(fresh);
      out.afterEffective=window.__effective(fresh);
      out.pressedLeft=kb.querySelectorAll('.key.pressed').length;
      return out;
    });
    assert.equal(one.idle,KEY_IDLE,`${label}:字母键常态应为纯白`);
    assertOverlay(one.during,KEY_PRESS_RGB,KEY_PRESS_ALPHA,`${label}:按下应为官方 45% 冷灰覆层`);
    assertEffective(one.duringEffective,KEY_PRESS_EFFECTIVE,`${label}:按下等效色应为官方 rgb(221,225,232)`);
    assert.equal(one.pressedNow,1,`${label}:按下时只能有一个键处于按下态`);
    assert.equal(one.after,KEY_IDLE,`${label}:松手必须立刻回到纯白`);
    assert.equal(one.afterEffective,KEY_IDLE,`${label}:松手后等效色必须回到纯白`);
    assert.equal(one.pressedLeft,0,`${label}:松手后不得残留按压态`);
    assert.equal(one.transition,'0s',`${label}:按键不应有过渡（官方是瞬时换图）`);

    // ③ 功能键按下比常态更深一档（用「中/英」，它不会跳页，后面还要继续按字母键）
    const fun=await page.evaluate(()=>{
      const kb=document.getElementById('keyboard'),k=[...kb.querySelectorAll('.key.function')].find(b=>b.dataset.action==='language');
      const idle=window.__effective(k);
      window.__press(k);
      const during=window.__bg(k),effective=window.__effective(k);
      window.__release(k);
      return {idle,during,effective,left:kb.querySelectorAll('.key.pressed').length};
    });
    assertOverlay(fun.during,FUNC_PRESS_RGB,FUNC_PRESS_ALPHA,`${label}:功能键按下应为官方 80% 冷灰覆层`);
    assertEffective(fun.effective,FUNC_PRESS_EFFECTIVE,`${label}:功能键按下等效色应为官方 rgb(205,210,218)`);
    assert.notEqual(fun.effective,fun.idle,`${label}:功能键按下必须比常态更深`);
    assert.equal(fun.left,0,`${label}:功能键松手后不得残留按压态`);

    // ④ 连续快速点击不累积残色
    const rapid=await page.evaluate(()=>{
      const kb=document.getElementById('keyboard');let maxPressed=0,trace=[];
      for(let i=0;i<12;i++){
        const list=window.__letters();const k=list[i%list.length];
        window.__press(k);
        maxPressed=Math.max(maxPressed,kb.querySelectorAll('.key.pressed').length);
        window.__release(k);
        trace.push(kb.querySelectorAll('.key.pressed').length);
      }
      return {maxPressed,left:kb.querySelectorAll('.key.pressed').length,worst:Math.max(...trace)};
    });
    assert.equal(rapid.maxPressed,1,`${label}:连点期间同时最多一个按下态`);
    assert.equal(rapid.worst,0,`${label}:连点期间松手后不得残留`);
    assert.equal(rapid.left,0,`${label}:连点 12 次后不得残留按压态`);

    // ⑤ 自愈：上一次按压残留时，下一次按下先清掉旧的
    const heal=await page.evaluate(()=>{
      const kb=document.getElementById('keyboard'),list=[...kb.querySelectorAll('.key')];
      list[0].classList.add('pressed');
      window.__press(list[1]);
      return {stale:list[0].classList.contains('pressed'),total:kb.querySelectorAll('.key.pressed').length};
    });
    assert.equal(heal.stale,false,`${label}:残留按压态应被下一次按下清掉`);
    assert.equal(heal.total,1,`${label}:自愈后只应剩当前按下的那个键`);

    // ⑥ 兜底：失焦（与 pointerup 丢失等价）后视觉与手势都要复位
    const blur=await page.evaluate(()=>{
      const kb=document.getElementById('keyboard');
      window.__press(window.__letters()[0]);
      const before=kb.querySelectorAll('.key.pressed').length;
      window.dispatchEvent(new Event('blur'));
      const after=kb.querySelectorAll('.key.pressed').length;
      const k=window.__letters()[1];window.__press(k);       // 放开手后必须还能按
      const revived=k.classList.contains('pressed');
      window.__release(k);
      return {before,after,revived,left:kb.querySelectorAll('.key.pressed').length};
    });
    assert.equal(blur.before,1,`${label}:失焦前应有按下态`);
    assert.equal(blur.after,0,`${label}:失焦后按下色必须自愈`);
    assert.equal(blur.revived,true,`${label}:失焦后键盘必须仍能响应按下`);
    assert.equal(blur.left,0,`${label}:收尾不得残留按压态`);

    assert.deepEqual(errors,[],`${label}:页面不应有 JS 报错`);
    await page.close();
  }
  console.log('PASS: 26 键按下等效官方 rgb(221,225,232) / 功能键 rgb(205,211,218)，按下与松手 0 延迟无过渡，连点与失焦均不残留按压色');
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
