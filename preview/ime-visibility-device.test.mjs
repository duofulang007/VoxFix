// Forward the shown K60 WebView to 19226 and its JDWP process to 19227 first.
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

const endpoint=process.env.VOXFIX_CDP||'http://127.0.0.1:19226';
const targets=await (await fetch(endpoint+'/json/list')).json();
const target=targets.find(p=>p.url==='https://voxfix.local/keyboard/index.html'&&JSON.parse(p.description).attached);
assert(target,'Open the VoxFix keyboard before this check');
const root=fileURLToPath(new URL('../',import.meta.url));
const output=root+'.android/ime-visibility-test';
const jdk=root+'.android/app-tools/jdk/bin/';
mkdirSync(output,{recursive:true});
execFileSync(jdk+'javac.exe',['--add-modules','jdk.jdi','-d',output,root+'tools/ImeVisibilityProbe.java']);
function probe(mode,page=target) {
  execFileSync(jdk+'java.exe',['--add-modules','jdk.jdi','-cp',output,'ImeVisibilityProbe',mode,page.webSocketDebuggerUrl],{timeout:15000,stdio:'pipe'});
}
probe('hidden');
const socket=new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve,reject)=>{socket.onopen=resolve;socket.onerror=reject;});
let nextId=0;
const pending=new Map();
socket.onmessage=event=>{const result=JSON.parse(event.data);pending.get(result.id)?.(result);};
async function call(method,params={}) {
  const id=++nextId;
  return new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{pending.delete(id);reject(Error(method+' timed out'));},4000);
    pending.set(id,result=>{clearTimeout(timer);pending.delete(id);result.error?reject(Error(result.error.message)):resolve(result.result);});
    socket.send(JSON.stringify({id,method,params}));
  });
}
async function evaluate(expression) {
  const result=await call('Runtime.evaluate',{expression,returnByValue:true});
  assert(!result.exceptionDetails,JSON.stringify(result.exceptionDetails));
  return result.result.value;
}
try {
  assert.equal(await evaluate('document.visibilityState'),'hidden','Inject the visibility mismatch first');
  await evaluate('globalThis.voxfixRedraw()');
  await new Promise(resolve=>setTimeout(resolve,300));
  assert.equal(await evaluate('document.visibilityState'),'visible','A render/resize must restore the shown native WebView');
  assert(await evaluate('document.querySelectorAll(".key").length>0'));
  assert((await call('Page.captureScreenshot',{format:'png'})).data,'The restored page must produce a frame');
  console.log('PASS: native hidden-page mismatch recovers through the real render/resize bridge and produces a frame');
} finally {socket.close();probe('repair');}
for(let i=0;i<2;i++) {
  const before=(await (await fetch(endpoint+'/json/list')).json()).find(p=>JSON.parse(p.description).attached);
  probe('recreate',before);
  await new Promise(resolve=>setTimeout(resolve,600));
  const pages=(await (await fetch(endpoint+'/json/list')).json()).filter(p=>p.url===target.url);
  assert.equal(pages.length,1,'Recreating the input view must destroy its previous WebView');
  assert(JSON.parse(pages[0].description).attached,'The replacement must be attached');
}
console.log('PASS: two native input-view recreations keep exactly one keyboard page');
