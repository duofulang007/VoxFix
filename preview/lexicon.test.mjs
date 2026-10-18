import assert from 'node:assert/strict';
import {parseLexicon,lexiconTsv} from './lexicon.mjs';
import {learnWord,validateLearning} from './core.mjs';
import {learnedCandidates} from './pinyin.mjs';

const row={key:'p:mingtian',word:'明天',code:'ming tian',hits:12};
const value={format:'voxfix-personal-lexicon',version:1,entries:[row,{key:'p:mt',word:'明天',hits:2}],legacy:{'rime:voxfix.tsv':'# untouched historical source\n'}};
assert.deepEqual(parseLexicon(JSON.stringify(value)),value);
assert.deepEqual(parseLexicon(lexiconTsv(value.entries)).entries,[{...row,pinned:true}]);
assert.deepEqual(parseLexicon('\uFEFF明天\tming tian\t12\r\n').entries,[{...row,pinned:true}]);
assert.equal(parseLexicon('明天\tming tian').entries[0].hits,2);
for(const text of ['明天 ming tian 12','明天\tming tian\t1.2','明天\tming tian\t-1','明天\tming tian\t','明天\tming\t2'])assert.throws(()=>parseLexicon(text),text);
assert.throws(()=>parseLexicon(JSON.stringify({...value,version:2})));
assert.throws(()=>parseLexicon(JSON.stringify({...value,legacy:{'../../private':'bad'}})));
assert.throws(()=>validateLearning([{...row,word:'明\n天'}]));
const many=Array.from({length:10001},(_,i)=>({key:'p:ci',word:'词'+String.fromCharCode(0x4e00+i),hits:2}));
assert.equal(validateLearning(many).length,10001);
const after=learnWord(many,'p:ni','你','ni');
assert.equal(after.length,10002);assert.equal(after.at(-1).word,many.at(-1).word,'No oldest-entry eviction');
assert(learnedCandidates('64648426',[row]).includes('明天'),'26-key records also recall on nine-key input');
console.log('PASS: lossless backup, TSV roundtrip, malformed input rejection, 10000+ retention and nine-key recall');

assert(learnedCandidates('mingtian',[{...row,hits:1,pinned:true}]).includes('明天'));

const voice={words:[{word:'张玥',code:'zhang yue',manual:true,hits:3}],corrections:[{heard:'张月',word:'张玥',left:'联系',right:'确认',hits:2}]};
assert.deepEqual(parseLexicon(JSON.stringify({...value,version:2,voice})).voice,voice);
const englishVoice={words:[...voice.words,{word:'Excel',code:'',manual:true,hits:1}],corrections:[...voice.corrections,...['AXCEL','EX CELL','艾克塞尔'].map(heard=>({heard,word:'Excel',left:'请用',right:'打开表格',hits:1}))]};
assert.deepEqual(parseLexicon(JSON.stringify({...value,version:2,voice:englishVoice})).voice,englishVoice);
const growingVoice={words:[{word:'Word',code:'',manual:false,hits:2},{word:'sherpa-onnx',code:'',manual:false,hits:2}],corrections:[{heard:'沃',word:'Word',left:'我的',right:'文档',hits:1},{heard:'卧',word:'Word',left:'我的',right:'文档',hits:1},{heard:'sherpa-onn',word:'sherpa-onnx',left:'use',right:'today'.slice(0,4),hits:2},{heard:'Xia.',word:'Word',left:'',right:'',hits:2}]};
assert.deepEqual(parseLexicon(JSON.stringify({...value,version:2,voice:growingVoice})).voice,growingVoice);
for(const [heard,word] of [['100元','900元'],['不要','需要'],['not','now']])assert.throws(()=>parseLexicon(JSON.stringify({...value,version:2,voice:{words:[{word,code:'',manual:false,hits:2}],corrections:[{heard,word,left:'',right:'',hits:2}]}})));
for(const heard of ['转账100','AXCEL!',' AXCEL','AXCEL '])assert.throws(()=>parseLexicon(JSON.stringify({...value,version:2,voice:{...englishVoice,corrections:[{heard,word:'Excel',left:'',right:'',hits:1}]}})));
for(const invalid of [{...voice,words:[{...voice.words[0],word:'词/非法'}]},{...voice,corrections:[{...voice.corrections[0],hits:1.5}]},{...voice,words:[]}])assert.throws(()=>parseLexicon(JSON.stringify({...value,version:2,voice:invalid})));
