import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const {chromium}=createRequire(import.meta.url)('playwright');
const adb=fileURLToPath(new URL('../.android/sdk/platform-tools/adb.exe',import.meta.url));
const shell=(...args)=>execFileSync(adb,['shell',...args],{encoding:'utf8'});
function inputText(){
  shell('uiautomator','dump','/data/local/tmp/voxfix-ime-ui.xml');
  const xml=shell('cat','/data/local/tmp/voxfix-ime-ui.xml');
  const node=xml.match(/<node\b[^>]*class="android.widget.EditText"[^>]*package="local.voxfix.debug"[^>]*>/)?.[0];
  assert(node,'Open the VoxFix test input before running this check');
  const text=node.match(/\btext="([^"]*)"/)[1].replace(/&#10;/g,'\n').replace(/&quot;/g,'"').replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&amp;/g,'&');
  // UIAutomator reports the own test field's placeholder as text when empty.
  return text==='在这里试用 VoxFix 输入法'?'':text;
}
const browser=await chromium.connectOverCDP('http://127.0.0.1:18746');
try{
  const page=browser.contexts().flatMap(context=>context.pages()).find(page=>page.url()==='https://voxfix.local/keyboard/index.html');
  assert(page,'The VoxFix native WebView must be open');
  const base=inputText();
  assert(base.trim()==='','This check only runs in a blank own-app test field');
  await page.evaluate(()=>window.VoxFixIme.moveCursor(100));await page.waitForTimeout(150);
  const click=(action,value)=>page.evaluate(({action,value})=>{
    const buttons=[...document.querySelectorAll('#keyboard [data-action]')];
    const button=buttons.find(button=>button.dataset.action===action&&(value===undefined||button.dataset.value===value));
    if(!button)throw Error('Missing key '+action);button.click();
  },{action,value});
  for(const letter of 'NIHAO'){await click('letter',letter);await page.waitForTimeout(150);}
  assert.equal(inputText(),base+'nihao','Composing text must reach the real Android editor');
  await click('candidate','你好');await page.waitForTimeout(250);
  assert.equal(inputText(),base+'你好','Candidate must replace composing text');
  await click('language');await click('letter','q');await click('space');await page.waitForTimeout(250);
  assert.equal(inputText(),base+'你好q ');
  await click('delete');await page.waitForTimeout(250);assert.equal(inputText(),base+'你好q');
  await click('numbers');
  for(const number of ['1','2','3']){await click('insert',number);await page.waitForTimeout(200);}
  assert.equal(inputText(),base+'你好q123');
  assert(await page.locator('.number-body').isVisible(),'Real editor callbacks must preserve number mode');
  await click('letters');await click('language');
  for(const letter of 'NIHAO'){await click('letter',letter);await page.waitForTimeout(100);}
  await click('insert','，');await page.waitForTimeout(250);
  assert.equal(inputText(),base+'你好q123你好，','Punctuation must first commit the Chinese candidate');
  // Only clear the exact fixed test text; leave any concurrent user edit untouched.
  if(inputText()===base+'你好q123你好，'){
    await page.evaluate(()=>window.VoxFixIme.edit('select-all'));await page.waitForTimeout(150);
    await page.evaluate(base=>window.VoxFixIme.commit(base),base);await page.waitForTimeout(150);
    assert.equal(inputText(),base);
  }
  await click('language');
  for(const letter of 'abcdefgh'){await click('letter',letter);await page.waitForTimeout(60);}
  assert.equal(inputText(),base+'abcdefgh');
  const key=await page.locator('#keyboard [data-action="delete"]').boundingBox();
  await page.mouse.move(key.x+key.width/2,key.y+key.height/2);await page.mouse.down();await page.waitForTimeout(750);await page.mouse.up();await page.waitForTimeout(200);
  const afterHold=inputText();
  assert(afterHold.startsWith(base)&&afterHold.length<=base.length+6,'Long delete must continue across real selection callbacks');
  if(afterHold.startsWith(base)&&'abcdefgh'.startsWith(afterHold.slice(base.length))){
    await page.evaluate(()=>window.VoxFixIme.edit('select-all'));await page.waitForTimeout(150);
    await page.evaluate(base=>window.VoxFixIme.commit(base),base);await page.waitForTimeout(150);assert.equal(inputText(),base);
  }
  await click('language');
  console.log('PASS: K60 real composing/candidates, English/space, continuous numbers, punctuation, long delete and test-text cleanup');
}finally{await browser.close();}
