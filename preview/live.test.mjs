import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {writeFile} from 'node:fs/promises';
const {chromium}=createRequire(import.meta.url)('playwright');
const browser=await chromium.launch({channel:'msedge',headless:true});
try{
  const page=await browser.newPage({viewport:{width:1200,height:1000}}),errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  await page.goto('http://127.0.0.1:18743/');
  await page.waitForFunction(()=>!document.querySelector('#polish-text').disabled);
  const original='我明天下午三点去跑步然后然后跑10公里不要跑15公里回家以后打开Obsidian记录一下';
  await page.locator('#editor').fill(original);
  await page.locator('#polish-text').click();
  await page.waitForFunction(()=>!document.querySelector('#polish-apply').disabled,{},{timeout:120000});
  const result=await page.locator('#polish-preview').inputValue();
  assert.equal(await page.locator('#editor').inputValue(),original,'preview must not overwrite input');
  assert(result.includes('Obsidian')&&result.includes('不要')&&result.includes('10')&&result.includes('15'));
  assert(!result.includes('然后然后'));
  await page.screenshot({path:new URL('../docs/offline-polish-live.png',import.meta.url).pathname.replace(/^\/(\w:)/,'$1'),fullPage:true});
  await page.locator('#polish-apply').click();
  assert.equal(await page.locator('#editor').inputValue(),result);
  await page.locator('#undo-polish').click();
  assert.equal(await page.locator('#editor').inputValue(),original);
  assert.deepEqual(errors,[]);
  const report={scope:'Real desktop browser, HTTP service and installed CPU model; synthetic text; not Redmi K60',original,result,status:await page.locator('#polish-status').textContent(),validation:['preview preserves input','protected numeric and English content present','repetition removed','apply and undo'],errors};
  await writeFile(new URL('../docs/offline-polish-live.json',import.meta.url),JSON.stringify(report,null,2));
  console.log('PASS: real browser -> local HTTP -> installed Q4_K_M -> preview/apply/undo');
}finally{await browser.close();}
