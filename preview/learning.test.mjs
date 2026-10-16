import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {learnWord, learnSelection, validateLearning, rankLearned} from './core.mjs';
import {setPinyinData, selectionCode, learnedCandidates, learnedAssociations} from './pinyin.mjs';

setPinyinData(JSON.parse(readFileSync(new URL('./pinyin-lite.json',import.meta.url),'utf8')));
assert.equal(selectionCode('mingtian','明天','ming tian xia wu'),'ming tian');
assert.equal(selectionCode('mt','明天'),'ming tian');
assert.equal(selectionCode('mingt','明天'),'ming tian');
assert.equal(selectionCode('abc','明天'),'','Do not invent a pronunciation for unrelated input');
assert.equal(selectionCode('xian','西安',"xi an"),'xi an');

let records=[],chain=[];
function choose(raw,word,code,now=100){({records,chain}=learnSelection(records,chain,raw,word,code,now));}
for(let i=0;i<2;i++){
  chain=[];
  choose('mingtian','明天','ming tian');
  choose('xiawu','下午','xia wu');
  choose('jian','见','jian');
}
for(const raw of ['mingtianxiawujian','mtxwj','mingtxwjian',"ming'tian'xia'wu'jian"]){
  assert(learnedCandidates(raw,records).includes('明天下午见'),raw+' must share the same memory');
}
assert(!learnedCandidates('m',records).includes('明天下午见'),'One initial must keep offering characters');
assert(!learnedCandidates('ni',records).includes('明天下午见'));
assert.equal(records.find(row=>row.word==='明天下午见').hits,2,'Each phrase counts once per occurrence');
assert.equal(learnedAssociations('明天',records)[0],'下午见');
assert.equal(learnedAssociations('我明天',records)[0],'下午见');
assert.equal(rankLearned(['明天见','明天下午见'],records,'p:mtxwj')[0],'明天下午见');
assert.deepEqual(validateLearning(JSON.parse(JSON.stringify(records))),records,'Persistence retains phonetic codes');
records=learnWord(records,'p:mtxwj','明天下午见','ming tian xia wu jian');
assert.equal(records.filter(row=>row.word==='明天下午见').length,1,'Abbreviations increment the canonical entry');
assert.equal(records.find(row=>row.word==='明天下午见').hits,3);
assert.throws(()=>validateLearning([{key:'p:ni',word:'你',hits:1,code:'ni hao'}]));
assert.throws(()=>validateLearning([{key:'p:ni',word:'你',hits:1,code:'<script>'}]));
assert.deepEqual(validateLearning([{key:'p:ni',word:'你',hits:1}]),[{key:'p:ni',word:'你',hits:1}],'Old records stay valid');

records=[];chain=[];
choose('mingtian','明天','ming tian',100);
choose('xiawu','下午','xia wu',30101);
assert(!records.some(row=>row.word==='明天下午'),'A long pause breaks the phrase');
chain=[];choose('jian','见','jian',30200);
assert(!records.some(row=>row.word==='下午见'),'A caller boundary breaks the phrase');
choose('abc','错词','',30300);
assert.equal(chain.length,0,'Unknown pronunciation breaks phrase assembly');
assert(!records.some(row=>row.word==='见错词'));
records=[];chain=[];
for(let i=0;i<30;i++)choose('ni','你','ni',i);
assert(records.every(row=>row.word.length<=16),'Automatic phrases stay bounded');
assert(records.length<=2000);
setPinyinData({schema:2,syllables:['zhuang'],characters:{'庄':0},entries:[['庄'.repeat(16),1,Array(16).fill(0)]]});
assert.equal(selectionCode('z'.repeat(16),'庄'.repeat(16)),'','Long expanded codes must keep exact-input memory without breaking a successful commit');
console.log('PASS: shared full/initial/mixed spelling, phrase learning, association, persistence and boundaries');
