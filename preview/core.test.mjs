import assert from 'node:assert/strict';
import {repeatDelay,deleteRepeatDelay,joystickHoldRate,cursorFeedbackPosition,joystick,joystickLevel,joystickRadii,joystickRho} from './core.mjs';
// 量「形状」用一组各向同性半径（a=b），这样斜推衰减只剩 n=3 本身带来的那部分；
// 若 a≠b，衰减里会混进椭圆的各向异性，那是设计使然、不是形状的锅。
const R={a:150,bUp:150,bDown:150};
// 触控点本身零速度；任何方向推出去都要有输出；越界只能钉在最高档，不能变成停住。
assert.equal(joystickRho(0,0,R),0);
assert.equal(joystickLevel(0,0),0,'触控点本身是唯一零速率的点');
assert.equal(joystickLevel(0.5,0),0,'短距离推不出速率（门槛 0.78），否则「稍微动一下就跨行」');
assert.equal(joystickLevel(0.8,0),1);
assert.equal(joystickLevel(9,0),1,'推到键盘外仍是最高档，不是停住');
assert.equal(joystickLevel(0.74,1),1,'迟滞：没跌破降档线就不回落');
assert.equal(joystickLevel(0.7,1),0,'跌破降档线才回落');
assert.equal(joystickLevel(0.85,0),1,'最外圈才升档');
assert.equal(joystick.rates.length,2,'只要两个速度分区');
// 半径随按下点收缩到最小行程，避免贴边按下时向外推不动。
assert.deepEqual(joystickRadii(20,300,500),{a:joystick.minSide,bUp:96,bDown:96});
assert.deepEqual(joystickRadii(140,40,10),{a:96,bUp:joystick.minUp,bDown:joystick.minDown});
for(const radii of [joystickRadii(100,320,31),joystickRadii(80,225,126)]){
  assert.equal(joystickLevel(joystickRho(0,-80,radii),0),1,'主滑轨和方向盘上推80px都应进入持续档，不用推到键盘顶');
  assert.equal(joystickLevel(joystickRho(0,-30,radii),0),0,'向上30px仍可跟手精调');
}
// 上下必须分开定半径：滑轨贴着键盘底、向下只剩几十像素，共用一个半径时正下方永远推不动
// （斜下能推只是因为水平分量在撑 ρ，表现为「只有底下左右两边能用」）。
const railEdge=joystickRadii(131,329,joystick.minDown);
assert.equal(joystickLevel(joystickRho(0,-30,railEdge),0),0,'向上 30px 仍是跟手档');
assert.equal(joystickLevel(joystickRho(0,20,railEdge),0),0,'向下 20px 仍是跟手档，才定位得准行');
assert.equal(joystickLevel(joystickRho(0,32,railEdge),0),1,'推到键盘底（向下约 32px）才切最快档');
// 圆角方不该像正方形那样斜推迟钝（正方形斜推要多走 1.41 倍，这里必须在 15% 内）。
for(const ratio of [0.4,0.8]){
  const straight=joystickRho(ratio*150,0,R);
  const diagonal=joystickRho(ratio*150/Math.SQRT2,ratio*150/Math.SQRT2,R);
  assert(diagonal/straight>0.85,`斜推衰减 ${(1-diagonal/straight).toFixed(3)} 过大`);
}
for(const width of [110,148,196,253]){
  let previous=-Infinity;
  for(const x of [-10000,-100,0,41,57,width/2,width-57,width-41,width,width+100,width+10000].sort((a,b)=>a-b)){
    const center=cursorFeedbackPosition(width,x);
    assert(center-33>=8&&center+33<=width-8,'The whole feedback halo stays inside its 8px inset');
    assert(center>=previous,'Edge resistance must not reverse the pointer direction');previous=center;
    assert(Math.abs(center+cursorFeedbackPosition(width,width-x)-width)<1e-9,'Both edges use the same resistance');
  }
}
assert.equal(cursorFeedbackPosition(0,0),0);
import { defaults, keyboardGeometry, settingsFrom, replaceRange, erase, moveCursor, voiceResult, validateSnippets, addSnippet, validateLearning, learnWord, rankLearned, snippetLimit, validatePhraseGroups, migratePhraseGroups, flattenPhrases, phraseGroupLimit, phraseItemLimit, phraseLengthLimit } from './core.mjs';
assert.deepEqual(replaceRange('前后',1,1,'中'),{text:'前中后',cursor:2});
assert.deepEqual(replaceRange('abc',1,3,'你'),{text:'a你',cursor:2});
for(const emoji of ['😊','👨‍👩‍👧‍👦','👍🏽']) {
  const text='前'+emoji+'后';
  assert.deepEqual(erase(text,1+emoji.length,1+emoji.length),{text:'前后',cursor:1});
}
assert.deepEqual(erase('abc',1,3),{text:'a',cursor:1});
assert.deepEqual(erase('你好',0,0),{text:'你好',cursor:0});
assert.equal(moveCursor('你好',2,-1),1);
assert.equal(moveCursor('你好',1,1),2);
assert.equal(moveCursor('你好',0,-20),0);
assert.equal(moveCursor('你好',1,20),2);
assert.equal(moveCursor('',0,1),0);
assert.equal(moveCursor('前👨‍👩‍👧‍👦后',1,1),'前👨‍👩‍👧‍👦'.length);
assert.equal(moveCursor('前👨‍👩‍👧‍👦后','前👨‍👩‍👧‍👦'.length,-1),1);
const legacy=settingsFrom({height:321,fontSize:25,gap:9,radius:11,background:'#ff0000',candidateCount:9,candidateFont:13,clipboardHistory:false,rememberInput:false,phrasePacks:['running']});
for(const [key,value] of Object.entries(keyboardGeometry))assert.equal(legacy[key],value,'Saved geometry must be replaced: '+key);
assert.equal(legacy.clipboardHistory,false);assert.equal(legacy.rememberInput,true);assert.deepEqual(legacy.phrasePacks,['running']);
const snapshot={text:'前原文后',start:1,end:3};
assert.deepEqual(voiceResult(snapshot,'你好。'),{text:'前你好。后',cursor:4});
assert.deepEqual(voiceResult(snapshot,'演示',true),{text:snapshot.text,cursor:1});
assert.deepEqual(settingsFrom({}),defaults);
assert.deepEqual(settingsFrom({toolbar:[],footerClipboard:false}).toolbar,[]);
assert.deepEqual(settingsFrom({toolbar:['emoji','emoji']}).toolbar,['emoji']);
assert.equal(settingsFrom({height:NaN}).height,351);
assert.equal(settingsFrom({height:999}).height,351);
assert.throws(()=>settingsFrom({toolbar:['unknown']}));
assert.equal(settingsFrom({background:'url(x)'}).background,'#edeff2');
assert.throws(()=>settingsFrom({voiceText:''}));
assert.throws(()=>settingsFrom({footerSwitcher:'false'}));
assert.deepEqual(addSnippet(['旧内容'],'新内容'),['新内容','旧内容']);
assert.deepEqual(addSnippet(['旧内容','新内容'],'新内容'),['新内容','旧内容']);
assert.deepEqual(addSnippet(['旧内容'],'修改后',0),['修改后']);
assert.throws(()=>addSnippet(['旧内容'],' '));
assert.throws(()=>addSnippet(['旧内容'],'修改后',5));
assert.throws(()=>validateSnippets([42]));
assert.deepEqual(addSnippet([],'  保留空格  '),['  保留空格  ']);
let memory=learnWord([],'p:ni','泥');
assert.deepEqual(rankLearned(['你','泥'],memory,'p:ni'),['你','泥']);
memory=learnWord(memory,'p:ni','泥');
assert.deepEqual(rankLearned(['你','泥'],memory,'p:ni'),['泥','你']);
assert.deepEqual(rankLearned(['你','泥'],memory,'9:64'),['你','泥']);
assert.deepEqual(rankLearned(['从','出'],[{key:'p:c',word:'吃饭了吗',hits:10}],'p:c'),['从','出'],'Learning must only reorder current valid candidates');
assert.deepEqual(rankLearned(['年会','你好'],[{key:'p:nihao',word:'你好',hits:2}],'p:nh'),['你好','年会']);
assert.deepEqual(validateLearning(JSON.parse(JSON.stringify(memory))),memory);
assert.throws(()=>validateLearning([{key:'p:ni',word:'泥',hits:-1}]));
assert.equal(settingsFrom({rememberInput:false}).rememberInput,true);
// 剪贴板：上限对齐官方 300 条，到上限丢最旧而不是报错（R2-07 / R2-08）。
let full=[];
for(let i=0;i<snippetLimit+7;i++)full=addSnippet(full,'条目'+i);
assert.equal(full.length,snippetLimit);
assert.equal(full[0],'条目'+(snippetLimit+6));
assert.equal(full[full.length-1],'条目7');
assert.equal(validateSnippets(full).length,snippetLimit);
assert.throws(()=>validateSnippets(new Array(snippetLimit+1).fill('x')));
// 常用语分组：旧扁平列表迁移进默认分组，分组与条目都受上限约束（R2-15）。
assert.deepEqual(migratePhraseGroups(['旧一','旧二']),[{name:'常用语',items:['旧一','旧二']}]);
assert.deepEqual(migratePhraseGroups([]),[{name:'常用语',items:[]}]);
assert.deepEqual(migratePhraseGroups([{name:'工作',items:['周报','会议']}]),[{name:'工作',items:['周报','会议']}]);
assert.deepEqual(flattenPhrases([{name:'a',items:['1','2']},{name:'b',items:['2','3']}]),['1','2','3']);
assert.throws(()=>validatePhraseGroups(new Array(phraseGroupLimit+1).fill({name:'x',items:[]})));
assert.throws(()=>validatePhraseGroups([{name:'x',items:new Array(phraseItemLimit+1).fill('y')}]));
assert.throws(()=>validatePhraseGroups([{name:'x',items:['z'.repeat(phraseLengthLimit+1)]}]));
// 工具栏允许混合内置入口与自定义项（R2-18）。
assert.deepEqual(settingsFrom({toolbar:['menu','custom:ab12cd','clipboard']}).toolbar,['menu','custom:ab12cd','clipboard']);
assert.throws(()=>settingsFrom({toolbar:['menu','custom:']}));
assert.throws(()=>settingsFrom({customToolbar:[{label:'',text:'x'}]}));
const custom={id:'custom:ab12cd',label:'问候',text:'你好'};
const customSettings=settingsFrom({toolbar:['menu',custom.id],customToolbar:[custom]});
assert.deepEqual(settingsFrom(JSON.parse(JSON.stringify(customSettings))).customToolbar,[custom],'Saved/imported toolbar keeps its identity');
assert.deepEqual(settingsFrom({toolbar:['menu',custom.id],customToolbar:[{label:custom.label,text:custom.text}]}).customToolbar,[custom],'Old entries without IDs reuse their toolbar reference');
const orphan=settingsFrom({customToolbar:[{label:'旧项',text:'保留旧内容'}]});
assert(orphan.toolbar.includes(orphan.customToolbar[0].id),'Old content without a reference still has a reachable button');
assert.throws(()=>settingsFrom({customToolbar:[{...custom,id:'invalid'}]}));
assert.throws(()=>settingsFrom({customToolbar:[custom,custom]}));
console.log('PASS: text selection, grapheme deletion/cursor movement, voice cancellation and layout validation');

assert.equal(repeatDelay(0),110);
assert.equal(repeatDelay(20),35);
assert.equal(joystickHoldRate(0),1000/110);
assert(joystickHoldRate(550)>joystickHoldRate(0));
assert.equal(joystickHoldRate(5000),24);
// 删除连删必须比摇杆快：起步 90ms、封底 30ms，且始终单调更快。
assert.equal(deleteRepeatDelay(0),90);
assert.equal(deleteRepeatDelay(50),30);
assert(deleteRepeatDelay(0)<repeatDelay(0),'连删起步快于摇杆');
assert(deleteRepeatDelay(50)<repeatDelay(50),'连删封底快于摇杆');
for(let step=1;step<=10;step++)assert(deleteRepeatDelay(step)<=deleteRepeatDelay(step-1),'连删间隔只能变短');
