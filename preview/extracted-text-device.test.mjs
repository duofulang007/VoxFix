// Open an empty own-app test field in landscape; forward WebView to 19226 and JDWP to 19227.
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
const {chromium}=createRequire(import.meta.url)('playwright');
const root=fileURLToPath(new URL('../',import.meta.url));
const jdk=root+'.android/app-tools/jdk/bin/';
execFileSync(jdk+'javac.exe',['--add-modules','jdk.jdi','-d',root+'.android/ime-visibility-test',root+'tools/ExtractedTextProbe.java']);
const browser=await chromium.connectOverCDP('http://127.0.0.1:19226');
let page,socketUrl,seeded=false;
function probe(mode,length){
  const out=execFileSync(jdk+'java.exe',['--add-modules','jdk.jdi','-cp',root+'.android/ime-visibility-test','ExtractedTextProbe',mode,socketUrl,...(length===undefined?[]:[String(length)])],{encoding:'utf8',timeout:20000});
  if(out.trim())console.log(out.trim());
}
try{
  const targets=await(await fetch('http://127.0.0.1:19226/json/list')).json();
  const target=targets.find(t=>t.url==='https://voxfix.local/keyboard/index.html'&&JSON.parse(t.description).attached);
  assert(target);
  page=browser.contexts().flatMap(c=>c.pages()).find(p=>p.url()===target.url);assert(page);
  socketUrl=target.webSocketDebuggerUrl;
  assert(await page.evaluate(()=>document.documentElement.classList.contains('landscape-ime')));
  for(const voice of [false,true]){
    probe('seed');seeded=true;await page.waitForTimeout(300);probe('check',8);
    if(voice)probe('arm');
    await page.locator('#keyboard [data-action="delete"]').first().click();
    await page.waitForTimeout(300);probe('check',7);
    if(voice){
      await page.evaluate(()=>VoxFixIme.commit('B'));
      await page.waitForTimeout(300);probe('check',8);
      await page.locator('#keyboard [data-action="delete"]').first().click();
      await page.waitForTimeout(300);probe('check',7);
    }
    for(let remaining=6;remaining>=0;remaining--){
      await page.locator('#keyboard [data-action="delete"]').first().click();
      await page.waitForTimeout(100);probe('check',remaining);
    }
    console.log(`PASS: fullscreen ${voice?'voice-tracked':'ordinary'} deletion matches real host text`);
    probe('clear');seeded=false;await page.waitForTimeout(300);
  }
}finally{
  if(seeded)probe('clear');
  await browser.close();
}
