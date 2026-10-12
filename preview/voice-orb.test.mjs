import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFile, mkdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const {chromium}=createRequire(import.meta.url)('playwright');
const root=new URL('../',import.meta.url),shots=new URL('../docs/语音交互概念稿.assets/',import.meta.url);
const server=createServer(async(req,res)=>{
  try{
    const file=new URL('.'+decodeURIComponent(req.url.split('?')[0]),root);
    if(!file.href.startsWith(root.href))throw Error('Outside fixture');
    const types={mjs:'text/javascript',css:'text/css',json:'application/json',html:'text/html',png:'image/png',webp:'image/webp'};
    res.setHeader('Content-Type',types[file.pathname.split('.').at(-1)]||'text/plain');res.end(await readFile(file));
  }catch{res.writeHead(404);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const origin=`http://127.0.0.1:${server.address().port}`;
const browser=await chromium.launch({channel:'msedge',headless:true});
try{
  const page=await browser.newPage({viewport:{width:393,height:351},hasTouch:true}),errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(()=>{
    window.calls=[];
    window.VoxFixIme=Object.fromEntries(['compose','commit','completeEnglish','copy','delete','enter','hide','switcher','startVoice','startLocalVoice','stopVoice','cancelVoice','moveCursor','cursorPosition','edit','ready','resize','refreshClipboard','importClipboard'].map(name=>[name,(...args)=>calls.push([name,...args])]));
    VoxFixIme.voiceReady=()=>true;VoxFixIme.handReady=()=>false;
  });
  await page.goto(origin+'/preview/index.html');
  await page.waitForFunction(()=>typeof voxfixVoiceLevel==='function');
  await mkdir(shots,{recursive:true});
  const start=async()=>{
    const r=await page.locator('[data-action="space"]').boundingBox(),point={x:r.x+r.width/2,y:r.y+r.height/2};
    await page.mouse.move(point.x,point.y);await page.mouse.down();await page.waitForTimeout(500);return point;
  };
  const pixels=()=>page.locator('.voice-texture').evaluate(canvas=>{
    const gl=canvas.getContext('webgl'),pixel=new Uint8Array(4),sample=(x,y)=>{gl.readPixels(Math.floor(canvas.width*x),Math.floor(canvas.height*y),1,1,gl.RGBA,gl.UNSIGNED_BYTE,pixel);return [...pixel];};
    return {top:sample(.5,.9),left:sample(.2,.5),right:sample(.8,.5),bottom:sample(.5,.1),error:gl.getError()};
  });
  const scale=()=>page.locator('.voice-follow').evaluate(el=>new DOMMatrix(getComputedStyle(el).transform).a);
  let point=await start();
  assert(await page.locator('.voice-texture').isVisible(),'Procedural shader must compile');
  const canvas=await page.locator('.voice-texture').elementHandle(),before=await pixels();
  assert.equal(before.error,0);assert(before.top[2]>before.top[0]+80,'Upper sky must stay blue');
  assert(before.right[0]>before.left[0]+35,'White cloud sits to the right');
  await page.screenshot({path:fileURLToPath(new URL('v5-listening.png',shots))});
  await page.evaluate(()=>voxfixRedraw());
  assert(await canvas.evaluate(el=>el===document.querySelector('.voice-texture')),'Redraw must retain the animated surface');
  await page.evaluate(()=>{window.meter=setInterval(()=>voxfixVoiceLevel(12),60);});await page.waitForTimeout(350);
  assert(await scale()>1.05,'Voice amplitude expands the ball');
  await page.evaluate(()=>clearInterval(window.meter));await page.waitForTimeout(1100);
  assert(Math.abs(await scale()-1)<.015,'A stale meter must settle to silence');
  assert.notDeepEqual(await pixels(),before,'Clouds change internally without rotating the whole sphere');
  await page.mouse.move(point.x,point.y-70);await page.waitForTimeout(150);
  assert(await page.locator('.voice-panel.cancel').isVisible());
  const red=await pixels();assert(red.top[0]>red.top[2]+50,'Cancel retains texture with a red palette');
  await page.screenshot({path:fileURLToPath(new URL('v5-cancel.png',shots))});
  await page.mouse.move(point.x,point.y);
  assert(await page.locator('.voice-panel.listening').isVisible(),'Sliding back resumes listening');
  assert(await canvas.evaluate(el=>el===document.querySelector('.voice-texture')));
  await page.mouse.up();await page.waitForTimeout(250);
  assert(await page.locator('.voice-panel.recognizing').isVisible());
  assert(await scale()<.9);assert(await canvas.evaluate(el=>el===document.querySelector('.voice-texture')));
  await page.screenshot({path:fileURLToPath(new URL('v5-recognizing.png',shots))});
  await page.evaluate(()=>voxfixVoiceDone(''));await page.waitForTimeout(250);
  assert.equal(await page.locator('.voice-panel,.voice-ghost').count(),0);
  for(const width of [320,393,480]){
    await page.setViewportSize({width,height:351});point=await start();
    const ball=await page.locator('.voice-follow').boundingBox();assert(ball.x>=0&&ball.y>=0&&ball.x+ball.width<=width&&ball.y+ball.height<=351);
    await page.mouse.up();await page.evaluate(()=>voxfixVoiceDone(''));await page.waitForTimeout(230);
  }
  await page.emulateMedia({reducedMotion:'reduce'});point=await start();
  const still=await pixels();await page.evaluate(()=>voxfixVoiceLevel(12));await page.mouse.move(point.x,point.y-30);await page.waitForTimeout(200);
  assert.equal(await scale(),1,'Reduced motion removes scale/drag displacement');
  await page.mouse.move(point.x,point.y);await page.waitForTimeout(400);
  const settled=await pixels();
  for(const key of ['top','left','right','bottom'])assert(settled[key].every((v,i)=>Math.abs(v-still[key][i])<=2),'Reduced motion freezes clouds while allowing subtle brightness feedback');
  await page.mouse.up();await page.evaluate(()=>voxfixVoiceDone(''));await page.waitForTimeout(230);
  await page.emulateMedia({reducedMotion:'no-preference'});point=await start();
  await page.locator('.voice-texture').evaluate(canvas=>canvas.getContext('webgl').getExtension('WEBGL_lose_context').loseContext());
  await page.waitForTimeout(80);assert.equal(await page.locator('.voice-texture').isVisible(),false);
  assert(await page.locator('.voice-orb').isVisible(),'Context loss retains the CSS orb');
  await page.mouse.up();await page.evaluate(()=>voxfixVoiceDone(''));await page.waitForTimeout(230);
  await page.goto(new URL('../键盘试用页.html',import.meta.url).href);
  await page.waitForFunction(()=>typeof voxfixVoiceLevel==='function');
  point=await start();assert(await page.locator('.voice-texture').isVisible(),'The double-click HTML must contain the renderer');
  await page.mouse.up();await page.evaluate(()=>voxfixVoiceDone(''));await page.waitForTimeout(230);
  assert.deepEqual(errors,[]);
  const demo=await browser.newPage({viewport:{width:1080,height:810}}),demoErrors=[];
  demo.on('pageerror',error=>demoErrors.push(error.message));
  await demo.goto(origin+'/docs/'+encodeURIComponent('语音球交互预览.html'));
  await demo.locator('[data-mode="talk"]:enabled').waitFor();
  await demo.locator('[data-mode="talk"]').click();await demo.waitForTimeout(400);
  await demo.screenshot({path:fileURLToPath(new URL('v5-preview.png',shots)),fullPage:true});
  await demo.locator('[data-mode="cancel"]').click();
  assert(await demo.frameLocator('iframe').locator('.voice-panel.cancel').isVisible());
  await demo.locator('[data-mode="finish"]').click();await demo.locator('[data-mode="restart"]').click();await demo.waitForTimeout(1300);
  assert(await demo.frameLocator('iframe').locator('.voice-panel.listening').isVisible(),'An old demo completion must not end the new session');
  assert.deepEqual(demoErrors,[]);
  console.log('PASS: shader/color, live amplitude/silence, stable DOM, reversible cancel, recognition/exit, 320–480px, reduced motion, context loss, shared demo');
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
