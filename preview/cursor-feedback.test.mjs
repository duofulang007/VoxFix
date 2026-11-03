import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFile,mkdir} from 'node:fs/promises';
const {chromium}=createRequire(import.meta.url)('playwright');
const server=createServer(async(req,res)=>{
  const name=req.url==='/'?'index.html':req.url.slice(1);
  if(!/^[\w.-]+$/.test(name)){res.writeHead(404);res.end();return;}
  try{res.setHeader('Content-Type',name.endsWith('.mjs')?'text/javascript':name.endsWith('.css')?'text/css':name.endsWith('.json')?'application/json':'text/html');res.end(await readFile(new URL(name,import.meta.url)));}catch{res.writeHead(404);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({channel:'msedge',headless:true});
try{
  await mkdir(new URL('../.android/rail-capsule-20261102/',import.meta.url),{recursive:true});
  for(const reducedMotion of ['no-preference','reduce']){
    const page=await browser.newPage({viewport:{width:393,height:551},hasTouch:true,reducedMotion}),errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    await page.addInitScript(()=>{
      window.VoxFixIme=Object.fromEntries(['ready','resize','clipHint','cursorPosition','gestureStart','gestureEnd','gestureDelta','gestureMove2D','gestureClearSteps','candidatesVisible','selectionEditing','refreshClipboard'].map(name=>[name,()=>{}]));
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForFunction(()=>typeof voxfixRedraw==='function');
    if(reducedMotion==='reduce')await page.locator('[data-action="night"]').click();
    await page.evaluate(()=>voxfixCursor(4,10));
    const zone=page.locator('.keyboard-footer .cursor-zone');
    for(const gap of [0,180,25,180]){
      if(gap)await page.waitForTimeout(gap);
      const r=await zone.boundingBox(),x=r.x+r.width/2,y=r.y+r.height/2;
      await page.mouse.move(x,y);await page.mouse.down();await page.mouse.move(x+20,y);
      await page.waitForTimeout(30);
      await page.evaluate(()=>voxfixRedraw());
      const state=await zone.evaluate(node=>{
        const line=node.querySelector('.cursor-contact');
        return {visible:node.isConnected,opacity:Number(getComputedStyle(line).opacity),inline:line.style.opacity,animations:line.getAnimations().map(a=>({state:a.playState,fill:a.effect.getTiming().fill}))};
      });
      assert(state.visible&&state.opacity>.5,'Drag indicator must stay visible on repeated drags and redraw: '+JSON.stringify({reducedMotion,gap,...state}));
      assert.equal(await zone.locator('.cursor-focus').evaluate(node=>Number(getComputedStyle(node).opacity)),0,'Slow precision movement does not show a background');
      await page.mouse.move(x+84,y);await page.waitForTimeout(30);
      const shape=await zone.evaluate(node=>{
        const thumb=node.querySelector('.cursor-focus'),s=getComputedStyle(thumb),track=getComputedStyle(node,'::before');
        return {radius:s.borderTopLeftRadius,height:thumb.getBoundingClientRect().height,width:thumb.getBoundingClientRect().width,opacity:Number(s.opacity),shadow:s.boxShadow,trackContent:track.content};
      });
      assert(!shape.radius.includes('%')&&parseFloat(shape.radius)>=shape.height/2,'Thumb must have straight sides and round capsule ends');
      assert(shape.height<=12&&shape.width<=56&&shape.opacity>0&&shape.shadow==='none','Fast movement uses only a small, flat capsule');
      assert.equal(shape.trackContent,'none','There is no permanent rail background');
      if(gap===180){
        await page.locator('.keyboard-footer').screenshot({path:new URL(`../.android/rail-capsule-20261102/${reducedMotion}-drag.png`,import.meta.url).pathname.replace(/^\/([A-Za-z]:)/,'$1')});
      }
      await page.mouse.up();
    }
    assert.deepEqual(errors,[]);await page.close();
  }
  console.log('PASS: drag indicator remains visible; small capsule only on fast movement, no idle rail, light/dark and reduced motion');
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
