// Isolated GPU probe: does not start recording, press keys, or change the host editor.
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFile} from 'node:fs/promises';
const {chromium}=createRequire(import.meta.url)('playwright');
const source=await readFile(new URL('voice-orb.mjs',import.meta.url),'utf8');
const browser=await chromium.connectOverCDP(process.env.VOXFIX_CDP||'http://127.0.0.1:18748');
try{
  const page=browser.contexts().flatMap(context=>context.pages()).find(page=>page.url()==='https://voxfix.local/keyboard/index.html');
  assert(page,'The own VoxFix keyboard WebView must be open');
  const result=await page.evaluate(async source=>{
    const {createVoiceOrb}=await import('data:text/javascript;base64,'+btoa(unescape(encodeURIComponent(source))));
    const canvas=document.createElement('canvas');canvas.style.cssText='position:fixed;left:-9999px;width:160px;height:160px';document.body.append(canvas);
    let orb;
    try{
      orb=createVoiceOrb(canvas);if(!orb)return {compiled:false};
      const gl=canvas.getContext('webgl'),pixel=new Uint8Array(4);
      const sample=()=>{gl.readPixels(canvas.width/2|0,canvas.height*.9|0,1,1,gl.RGBA,gl.UNSIGNED_BYTE,pixel);return [...pixel];};
      orb.draw(0,0,0,0);const blue=sample();orb.draw(0,0,1,0);const red=sample();
      const precision=gl.getShaderPrecisionFormat(gl.FRAGMENT_SHADER,gl.HIGH_FLOAT).precision;
      return {compiled:true,renderer:gl.getParameter(gl.RENDERER),size:canvas.width,precision,blue,red,error:gl.getError()};
    }finally{orb?.dispose();canvas.remove();}
  },source);
  assert(result.compiled,'The actual Android GPU must compile the cloud shader');
  assert.equal(result.error,0);assert(result.precision>=16);assert(result.blue[2]>result.blue[0]+80);assert(result.red[0]>result.red[2]+50);
  console.log('PASS: K60 native WebView shader, precision, blue/red colors and cleanup; isolated rendering only');
  console.log(JSON.stringify(result));
}finally{await browser.close();}
