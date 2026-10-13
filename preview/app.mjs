import { defaults, keyboardGeometry, cursorFeedbackPosition, joystick, joystickLevel, joystickRadii, joystickRho, toolbarItems, settingsFrom, replaceRange, erase, moveCursor, voiceResult, validateSnippets, addSnippet, validateLearning, learnWord, rankLearned, snippetLimit, phraseItemLimit, phraseLengthLimit, phraseGroupLimit, flattenPhrases, migratePhraseGroups } from './core.mjs';
import { LocalVoice, voiceStatus, enableMicrophone } from './voice.mjs';
import { createVoiceOrb } from './voice-orb.mjs';
import { loadPinyin, pinyinCandidates, nineCandidates, setPhraseOptions, phraseCandidates, phraseSearch, associationCandidates, pinyinConsumed, hasPinyinMatch, isSyllable } from './pinyin.mjs';
import { InkPad } from './ink.mjs';
import { loadEnglish,loadPersonalEnglish,englishCandidates,hasEnglishWord } from './english.mjs';

const $ = id => document.getElementById(id);
const hostEditor=$('editor'), keyboard=$('keyboard');
let editor=hostEditor,localInput=null,consumedClick=null,enterLabel='换行',privateField=false,englishPrefix='',fieldPreference=null;
let nativeCursor={position:0,total:0};
const nativeIme = globalThis.VoxFixIme;
const hasNativePinyin=typeof nativeIme?.pinyinCandidates==='function';
const nativePinyinActive=()=>hasNativePinyin&&!localInput&&state.language==='zh'&&state.layout==='26'&&nativeIme.pinyinReady();
let fullPinyinLoaded=!hasNativePinyin,fullPinyinLoading=null;
function loadFullPinyin(){return fullPinyinLoading??=loadPinyin().then(()=>{fullPinyinLoaded=true;updatePhraseOptions();render();});}
if(nativeIme){document.documentElement.classList.add('native-ime');document.body.append($('phone'));}
const localPanel=nativeIme?document.createElement('div'):null;
if(localPanel){localPanel.id='ime-local-editor';localPanel.hidden=true;$('phone').append(localPanel);}
const snippetForm=$('snippet-form'),snippetDialog=$('snippet-dialog');
let snippetDraft=null;
function closeLocalEditor(keepDraft=false){if(!localInput)return;if(state.voice){if(keepDraft)preserveVoice(state.voice);else cancelVoice();}if(keepDraft&&localInput===$('snippet-content'))snippetDraft={edit:snippetEdit,text:localInput.value};state.comp=null;context='';associatedWords=[];editor=hostEditor;localInput=null;snippetDialog.append(snippetForm);localPanel.replaceChildren();localPanel.hidden=true;}
const nativeSettings=nativeIme?document.createElement('dialog'):null;
if(nativeSettings){nativeSettings.className='snippet-dialog';const close=document.createElement('button');close.textContent='返回键盘';close.onclick=()=>nativeSettings.close();nativeSettings.append(close,$('custom-panel'));document.body.append(nativeSettings);}
let settings = structuredClone(defaults);
try { const stored = localStorage.getItem('voxfix-layout-v1'); if (stored) {const saved=JSON.parse(stored);settings=settingsFrom(saved);localStorage.setItem('voxfix-layout-v1',JSON.stringify({...saved,...keyboardGeometry}));} } catch { /* Keep malformed or unwritable saved data untouched. */ }
if(nativeIme&&!settings.toolbar.includes('menu'))settings.toolbar.unshift('menu');
const state = { page: 'letters', returnPage:'letters', symbolReturn:'letters', language: 'zh', shift: 0, symbol: '常用', locked: false, night: false, clipTab: 'clipboard', clipLocked: false, popup: null, comp: null, voice: null, hidden: false, emojiTab: 'Emoji', emojiCategory: '常用', layout: '26', voiceTimer: null, cursorDrag: null, selectionActions: false, clipGroup: 0, clipManage: false, clipSelected: [], clipConfirm: null, clipCandidate: '', toolbarSort: false, clipGroupMenu: null };
let clipTabTap=null;
try{const saved=JSON.parse(localStorage.getItem('voxfix-keyboard-v1'));if(saved){if(['9','26','fullhand'].includes(saved.layout))state.layout=saved.layout;if(['zh','en'].includes(saved.language))state.language=saved.language;if(typeof saved.night==='boolean')state.night=saved.night;}}catch{}
if(nativeIme&&!nativeIme.handReady()&&state.layout==='fullhand'){state.layout='26';saveKeyboard();}
function saveKeyboard(){const preference=fieldPreference||state;try{localStorage.setItem('voxfix-keyboard-v1',JSON.stringify({layout:preference.layout,language:preference.language,night:state.night}));}catch{}}
let clips = ['有没有免费的', 'https://example.com/演示链接', 'obsidian'];
// 常用语按官方结构分组存放：每个分组有自己的名称和条目。
let phraseGroups = [{name:'常用语',items:['常用语示例','example@example.com','稍后回复你']}];
let nativeClipSignature='';
if(nativeIme){clips=[];phraseGroups=[{name:'常用语',items:[]}];}
try{const saved=JSON.parse(localStorage.getItem('voxfix-snippets-v1'));if(saved){clips=validateSnippets(saved.clips);phraseGroups=migratePhraseGroups(saved.phrases);if(/^[a-f0-9]{64}$/.test(saved.clipSignature||''))nativeClipSignature=saved.clipSignature;}}catch{/* Keep malformed stored data untouched. */}
function currentGroup(){return phraseGroups[Math.min(state.clipGroup,phraseGroups.length-1)]||phraseGroups[0];}
function currentList(){return state.clipTab==='clipboard'?clips:currentGroup().items;}
function allPhrases(){return flattenPhrases(phraseGroups);}
// 常用语新增：官方是"到上限就提示失败"（与剪贴板丢最旧的策略不同）。
function addPhraseItem(group,text){
  if(group.items.includes(text))return false;
  if(group.items.length>=phraseItemLimit)throw Error('当前分组内常用语已达上限（'+phraseItemLimit+' 条）');
  if(text.length>phraseLengthLimit)throw Error('单条常用语最多 '+phraseLengthLimit+' 字');
  group.items.unshift(text);
  return true;
}
let snippetEdit=null,snippetUndo=null,groupUndo=null;
let clipMenu=null,clipHold=null,clipSuppressUntil=0,learning=[];
try{const saved=localStorage.getItem('voxfix-learning-v1');if(saved)learning=validateLearning(JSON.parse(saved));}catch{/* Keep invalid learning data untouched. */}
let inkPad=null,inkWords=[],handReady=false,lastPinyin=state.layout==='9'?'9':'26';
let emojiQuery='',emojiSearchOpen=false;
let gesture = null, candidateSuppressUntil=0, toastTimer, speechReady = false, nativeComposition = false;
// 底部滑轨每一格对应的像素：越小越"无极"（每滑一小段就动一个字），同时大于触摸抖动阈值。
const cursorStep = 11, cursorHoldDelay = 350;
let cursorFeedback={clientX:0,visible:false,selecting:false};
// ── 起点锁定 ────────────────────────────────────────────────────────────────
// 长按任一条滑轨都在两种状态之间切换「一次」：
//   未锁定：拖动 = 移动光标，长按 = 把当前光标标定为固定起点并呼出右侧编辑框；
//   已锁定：拖动 = 只调整选区终点（起点不动），长按 = 把选区收成活动终点处的光标并解除锁定。
// 键盘底部主轨与编辑框内小轨共用这套手势，区别只有尺寸和位置。
// 锁定只活在本次输入会话里：换输入框、结束输入或正文让旧起点失效时静默清理。
let selectedCount=0,localSelection=null,lockedSelection=null,selectionLocked=false;
const selectionRowStep=28;
function releaseSelectionLock(){selectionLocked=false;lockedSelection=null;localSelection=null;selectedCount=0;}
function clearSelectionLock(){releaseSelectionLock();state.selectionActions=false;}
// 面板可见 ≠ 已锁定：收起面板不动锁定，重新触碰滑轨时状态与收起前一致。
function lockedNow(){
  if(!selectionLocked)return false;
  if(nativeIme&&!localInput)return true;
  if(lockedSelection&&lockedSelection.editor===editor&&lockedSelection.text===editor.value)return true;
  releaseSelectionLock();state.selectionActions=false;return false;
}
function resetDragDelta(g){g.steps=0;g.dragX=0;g.dragY=0;g.extraX=0;g.extraY=0;delete g.targetX;delete g.targetY;}
// 二维拖动要按字符索引落点，先量一遍每个字符的坐标。
function primeSelectionGeometry(g,head){
  g.origin=head;g.points=selectionPoints();
  const base=g.points.find(p=>p.index===head)||g.points.at(-1),next=g.points.find(p=>p.y===base.y&&p.x>base.x);
  g.charWidth=next?next.x-base.x:parseFloat(getComputedStyle(editor).fontSize)||16;
  g.lineHeight=parseFloat(getComputedStyle(editor).lineHeight)||base.height||20;
}
// 锁定起点：把「当前光标」钉成固定起点（有选区时取活动的那一端）。
function lockSelection(g){
  if(selectionLocked)return;
  selectionLocked=true;resetDragDelta(g);g.selecting=true;g.selectionReady=false;
  state.cursorDrag.selecting=true;state.selectionActions=true;cursorFeedback.selecting=true;
  if(nativeIme&&!localInput){selectedCount=0;nativeIme.gestureEnd?.();nativeIme.gestureLock?.();nativeIme.gestureStart?.(true);return;}
  const start=editor.selectionStart,end=editor.selectionEnd;
  const head=editor.selectionDirection==='backward'?start:end;
  lockedSelection={editor,text:editor.value,anchor:head};
  localSelection={editor,text:editor.value,anchor:head,head};
  editor.setSelectionRange(head,head);selectedCount=0;
  primeSelectionGeometry(g,head);
}
// 已锁定：手势一开始就进入「调终点」模式，长按不再重标起点。
function beginSelectionAdjust(g){
  resetDragDelta(g);g.selecting=true;g.selectionReady=true;
  state.cursorDrag.selecting=true;cursorFeedback.selecting=true;
  if(nativeIme&&!localInput){nativeIme.gestureStart?.(true);return;}
  const start=editor.selectionStart,end=editor.selectionEnd;
  const valid=localSelection?.editor===editor&&localSelection.text===editor.value&&Math.min(localSelection.anchor,localSelection.head)===start&&Math.max(localSelection.anchor,localSelection.head)===end;
  const head=valid?localSelection.head:editor.selectionDirection==='backward'?start:end;
  if(!valid)localSelection={editor,text:editor.value,anchor:lockedSelection?.editor===editor&&lockedSelection.text===editor.value?lockedSelection.anchor:start,head};
  editor.setSelectionRange(Math.min(localSelection.anchor,head),Math.max(localSelection.anchor,head),head<localSelection.anchor?'backward':'forward');
  primeSelectionGeometry(g,head);
  selectedCount=characterCount(editor.value.slice(Math.min(localSelection.anchor,head),Math.max(localSelection.anchor,head)));
}
// 解除锁定：把选区收成「活动终点」处的光标。反向拖选后 head 才是手指那一端，
// 不能用较大的索引 —— 那会跳到起点那一侧。
function unlockSelection(g){
  const native=nativeIme&&!localInput;
  let head=-1;
  if(native)nativeIme.gestureUnlock?.();
  else{
    head=localSelection&&localSelection.editor===editor&&localSelection.text===editor.value?localSelection.head:(editor.selectionDirection==='backward'?editor.selectionStart:editor.selectionEnd);
    if(head>=0)editor.setSelectionRange(head,head);
  }
  releaseSelectionLock();
  // 同一次长按后续的拖动不再移动任何东西，抬手后滑轨恢复自由移动。
  if(g){g.selecting=false;g.selectionReady=false;g.ceased=true;haltJoystick(g);}
  if(state.cursorDrag)state.cursorDrag.selecting=false;
  cursorFeedback.selecting=false;
}
function characterCount(text){return [...new Intl.Segmenter('zh',{granularity:'grapheme'}).segment(text)].length;}
function selectionPoints(){
  const style=getComputedStyle(editor),mirror=document.createElement('div');
  for(const property of ['font','lineHeight','letterSpacing','padding','borderWidth','boxSizing','wordBreak','overflowWrap','textIndent'])mirror.style[property]=style[property];
  Object.assign(mirror.style,{position:'absolute',left:'-100000px',top:'0',width:editor.clientWidth+'px',whiteSpace:editor.tagName==='TEXTAREA'?'pre-wrap':'pre',overflowWrap:'break-word',border:'0',visibility:'hidden'});
  const segments=[...new Intl.Segmenter('zh',{granularity:'grapheme'}).segment(editor.value)];
  for(const {segment,index} of segments){const span=document.createElement('span');span.textContent=segment;span.dataset.index=index;mirror.append(span);}
  const end=document.createElement('span');end.textContent='\u200b';end.dataset.index=editor.value.length;mirror.append(end);document.body.append(mirror);
  const rect=mirror.getBoundingClientRect(),points=[...mirror.children].map(span=>{const r=span.getBoundingClientRect();return {index:Number(span.dataset.index),x:r.left-rect.left,y:r.top-rect.top+r.height/2,height:r.height};});
  mirror.remove();return points;
}
function moveSelection(x,y){
  const g=gesture;if(!g?.selecting)return;
  const dx=x-(g.dragX||0),dy=y-(g.dragY||0);g.dragX=x;g.dragY=y;
  if(nativeIme&&!localInput){
    if(typeof nativeIme.gestureMove2D==='function')nativeIme.gestureMove2D(dx,dy);
    else nativeIme.gestureMove?.(Math.trunc(x/cursorStep));
    return;
  }
  if(!localSelection||localSelection.editor!==editor||localSelection.text!==editor.value)return;
  const base=g.points.find(p=>p.index===g.origin)||g.points.at(-1);
  g.targetX=(g.targetX??base.x)+dx/cursorStep*g.charWidth;
  g.targetY=Math.max(g.points[0].y,Math.min(g.points.at(-1).y,(g.targetY??base.y)+dy/selectionRowStep*g.lineHeight));
  const targetX=g.targetX,targetY=g.targetY;
  const nearestY=g.points.reduce((best,p)=>Math.abs(p.y-targetY)<Math.abs(best-targetY)?p.y:best,base.y);
  const point=g.points.filter(p=>Math.abs(p.y-nearestY)<1).reduce((best,p)=>Math.abs(p.x-targetX)<Math.abs(best.x-targetX)?p:best);
  const row=g.points.filter(p=>Math.abs(p.y-nearestY)<1);g.targetX=Math.max(row[0].x,Math.min(row.at(-1).x,g.targetX));
  localSelection.head=point.index;
  const {anchor,head}=localSelection;editor.setSelectionRange(Math.min(anchor,head),Math.max(anchor,head),head<anchor?'backward':'forward');
  const height=editor.clientHeight;editor.scrollTop=Math.max(0,Math.min(editor.scrollTop,point.y-12));if(point.y>editor.scrollTop+height-12)editor.scrollTop=point.y-height+24;
  selectedCount=characterCount(editor.value.slice(Math.min(anchor,head),Math.max(anchor,head)));syncSelectionFeedback();
}
function syncSelectionFeedback(){
  const count=keyboard.querySelector('.selection-count');if(count)count.textContent=selectedCount<0?'已选文字':`已选 ${selectedCount} 字`;
  for(const button of keyboard.querySelectorAll('.selection-editor [data-action="copy-text"],.selection-editor [data-action="cut-text"]'))button.disabled=selectedCount===0;
}
function moveCursorGesture(g){
  const steps=Math.trunc((g.lastX-g.x)/cursorStep)+g.joy.steps;
  if(steps===g.steps)return;
  g.steps=steps;
  if(nativeIme&&!localInput){nativeIme.gestureMove?.(steps);return;}
  const position=moveCursor(editor.value,editor.selectionEnd,steps-(g.cursorSteps||0));g.cursorSteps=steps;
  localSelection=null;editor.setSelectionRange(position,position);
  keyboard.querySelector('.cursor-zone')?.setAttribute('aria-valuenow',position);
}
// 摇杆速率档：手指停在远处不动、光标也要继续走，所以按帧推进而不是按位移。
// 必须攒小数 —— 8 字/秒 ÷ 60fps = 0.13 步/帧，直接取整永远是 0，光标会一动不动。
// dt 夹到 50ms：WebView 切后台回来时 rAF 会跳一大帧，不夹会瞬间冲出去几十个字。
// 摇杆速率档：手指停在远处不动、光标/选区也要继续走，所以按帧推进而不是按位移。
// 必须攒小数 —— 8 字/秒 ÷ 60fps = 0.13 步/帧，直接取整永远是 0，光标会一动不动。
// dt 夹到 50ms：WebView 切后台回来时 rAF 会跳一大帧，不夹会瞬间冲出去几十个字。
// 移动光标是一维（方向只取 dx 符号）；框选是二维，按单位方向在两个轴上同时推进。
function jogCursor(g){
  if(g.joy.raf)return;
  const frame=ts=>{
    g.joy.raf=0;
    if(gesture!==g||!g.joy.rate)return;
    const dt=Math.min(.05,(ts-(g.joy.ts||ts))/1000);g.joy.ts=ts;
    if(g.selecting){
      const length=Math.hypot(g.joy.dx,g.joy.dy)||1;
      g.extraX+=g.joy.dx/length*g.joy.rate*cursorStep*dt;
      g.extraY+=g.joy.dy/length*g.joy.rate*selectionRowStep*dt;
      moveSelection(g.lastX-g.x+g.extraX,g.lastY-g.y+g.extraY);
    }else{
      g.joy.acc+=dt*g.joy.rate;
      const whole=Math.trunc(g.joy.acc);
      if(whole){g.joy.acc-=whole;g.joy.steps+=whole;moveCursorGesture(g);}
    }
    g.joy.raf=requestAnimationFrame(frame);
  };
  g.joy.raf=requestAnimationFrame(frame);
}
// 框选：算一次「离长按激活点多远」→ 档位 → 速率，再交给 rAF。就位完成时也必须调一次——
// 「垂直向上拖」的那步 pointermove 会走 lifting 分支提前 return，不在这里启动就永远不会持续扩展。
function joystickSync(g,x,y){
  const joy=g.joy;
  joy.dx=x-joy.originX;joy.dy=y-joy.originY;
  joy.level=joystickLevel(joystickRho(joy.dx,joy.dy,joy),joy.level);
  const rate=joystick.rates[joy.level];
  if(rate!==joy.rate){joy.rate=rate;joy.ts=0;}
  jogCursor(g);
}
// 摇杆原点 = 长按激活点，此后不再变（理由见 selectionReady 里的说明）。
function rejoy(g,x,y){
  const kb=keyboard.getBoundingClientRect();
  g.joy={...g.joy,...joystickRadii(Math.min(x-kb.left,kb.right-x),y-kb.top,kb.bottom-y),originX:x,originY:y,dx:0,dy:0,level:0,rate:0,acc:0,steps:0,ts:0};
}
// 停掉正在跑的摇杆速率（原生的「选区被阻止」和重置钩子都要用）。
function haltJoystick(g=gesture){
  if(!g?.joy)return;
  g.joy.rate=0;g.joy.level=0;
  if(g.joy.raf){cancelAnimationFrame(g.joy.raf);g.joy.raf=0;}
}
function selectionReady(g,x=g.lastX,y=g.lastY){
  if(gesture!==g)return;
  // 不重设摇杆原点：原点固定为「长按激活点」。若跟着手指走，就位后停住时 ρ 立刻回 0，
  // 选区又变回「不动就不扩展」——那正是这套摇杆要解决的问题。
  clearTimeout(g.liftTimer);g.selectionReady=true;g.x=x;g.y=y;g.dragX=0;g.dragY=0;g.extraX=0;g.extraY=0;joystickSync(g,x,y);
  syncSelectionFeedback();
}
// 表情长按连发、候选词长按、工具栏长按排序共用的按住计时状态。
let itemHold = null, toolbarHold = null, toolbarDrag = null, groupHold = null;
let context='',associatedWords=[],candidateMode='words',candidateGridWords=[],candidateGridShown=0;
function rememberContext(text){context=(context+text).match(/[\u3400-\u9fff]+$/)?.[0]?.slice(-6)||'';associatedWords=associationCandidates(context,240);}
const scrollPositions=new Map(),nightShade=document.createElement('div');
nightShade.id='night-shade';nightShade.className='night-shade';nightShade.hidden=true;$('phone').append(nightShade);
let polishReady=false,polishBusy=false,polishDraft=null,polishUndo=null;
let hotwords=[];
try{const saved=JSON.parse(localStorage.getItem('voxfix-hotwords-v1'));if(Array.isArray(saved)&&saved.length<=100&&saved.every(word=>typeof word==='string'&&/^[\u3400-\u9fff]{1,30}$/.test(word)))hotwords=saved;}catch{}
const icons = {
  menu: '<rect x="3" y="3" width="6" height="6" rx="1.5"/><rect x="14" y="3" width="6" height="6" rx="1.5"/><rect x="3" y="14" width="6" height="6" rx="1.5"/><rect x="14" y="14" width="6" height="6" rx="1.5"/>',
  layouts: '<path d="M3 18h18"/><path d="M3 3h3v3H3zM8 3h3v3H8zM13 3h3v3h-3zM18 3h3v3h-3zM3 8h3v3H3zM8 8h3v3H8zM13 8h3v3h-3zM18 8h3v3h-3zM3 13h3v3H3zM8 13h3v3H8zM13 13h3v3h-3zM18 13h3v3h-3z" fill="currentColor" stroke="none"/>',
  night: '<path d="M19 14A8 8 0 0 1 10 3a9 9 0 1 0 9 11Z"/>',
  clipboard: '<circle cx="6" cy="18" r="3"/><circle cx="18" cy="18" r="3"/><path d="m5 3 14 12M19 3 5 15"/>',
  copy: '<rect x="8" y="7" width="12" height="14" rx="2"/><path d="M15 7V3H3v14h5m3-6h6m-6 4h6"/>',
  emoji: '<circle cx="12" cy="12" r="9"/><path d="M8 15q4 4 8 0M8 9h.1M16 9h.1"/>',
  hide: '<path d="m5 9 7 7 7-7"/>', back: '<path d="M20 12H4m5-5-5 5 5 5"/>',
  delete: '<path d="m8 5-6 7 6 7h13V5Z"/><path d="m11 9 6 6m0-6-6 6"/>',
  enter: '<path d="M20 6v8H4m5-5-5 5 5 5"/>', space: '<path d="M3 14v4h18v-4"/>',
  mic: '<rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2m-7 9v3m-3 0h6"/>',
  keyboard: '<rect x="2" y="2" width="20" height="20" rx="3"/><path d="M6 6h3v3H6zM11 6h3v3h-3zM16 6h3v3h-3zM6 11h3v3H6zM11 11h3v3h-3zM16 11h3v3h-3zM6 16h13v3H6z" fill="currentColor" stroke="none"/>',
  lock: '<rect x="5" y="10" width="14" height="12" rx="2"/><path d="M8 10V6a4 4 0 0 1 8 0v4"/>',
  trash: '<path d="M4 6h16M9 6V3h6v3m-9 0 1 15h10l1-15M10 10v7m4-7v7"/>',
  add: '<path d="M12 4v16M4 12h16"/>', settings: '<circle cx="12" cy="12" r="3"/><path d="m10 2-1 3-3 1-3-1-1 4 2 2v3l-2 2 2 4 3-1 3 1 1 3 4-1 1-3 3-1 3 1 1-4-2-2v-3l2-2-2-4-3 1-3-1-1-3Z"/>',
  send: '<path d="M12 20V5m-5 5 5-5 5 5"/>', voice: '<path d="M4 9v6m4-10v14m4-17v20m4-17v14m4-10v6"/>',
  shift: '<path d="m12 3-9 9h6v8h6v-8h6Z" fill="currentColor" stroke="none"/>', caps: '<path d="m12 2-9 9h6v6h6v-6h6ZM9 20h6" fill="currentColor"/>',
  search: '<circle cx="10" cy="10" r="6"/><path d="m15 15 6 6"/>',
  forward: '<path d="m9 5 7 7-7 7"/>',
  add: '<path d="M12 5v14M5 12h14"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
  more: '<circle cx="5" cy="12" r="1.7" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.7" fill="currentColor" stroke="none"/><circle cx="19" cy="12" r="1.7" fill="currentColor" stroke="none"/>',
  check: '<path d="m5 13 5 5 9-11"/>',
};
function icon(name, cls = '') { return `<svg class="${cls}" viewBox="0 0 24 24" aria-hidden="true">${icons[name] || icons.menu}</svg>`; }
function esc(value) { return String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
function button(label, action, value = '', cls = '', body = '', extra = '') {
  return `<button type="button" class="${cls}" data-action="${action}" data-value="${esc(value)}" aria-label="${esc(label)}"${extra}>${body || esc(label)}</button>`;
}
function key(label, action = 'insert', value = label, cls = '', hint = '') {
  return button(label, action, value, `key ${cls}`, (hint ? `<span class="hint">${esc(hint)}</span>` : '') + esc(label));
}
function func(label, action, symbol = '', cls = '') { return button(label, action, '', `key function ${cls}`, symbol ? icon(symbol) : esc(label)); }
function enterKey(cls=''){const label=localInput?'换行':enterLabel;return func(label,'enter',label==='换行'?'enter':'',cls);}
function spaceKey() { return button('空格，长按语音输入', 'space', '', 'key space', icon('mic', 'microphone') + icon('space')); }
function languageKey() { return button('切换中英文', 'language', '', 'key function', state.language === 'zh' ? '中<span class="lang-small">/英</span>' : '<span class="lang-small">中/</span>英'); }
// 面板页头：固定三件套 —— 左圆形返回、中部（胶囊分段／已选计数）、右一两个圆形图标钮。
function ctl(action, label, symbol, cls = '') { return `<button type="button" class="ctl${cls ? ' ' + cls : ''}" data-action="${action}" aria-label="${esc(label)}">${icon(symbol)}</button>`; }
function clipHeader() {
  const list = currentList();
  if (state.clipManage && state.clipTab === 'clipboard') {
    const remove = `<button type="button" class="ctl danger" data-action="clip-manage-delete" aria-label="删除所选"${state.clipSelected.length ? '' : ' disabled'}>${icon('trash')}</button>`;
    return `<div class="panel-title">${ctl('clip-manage', '完成', 'check')}<span class="title-note">已选 ${state.clipSelected.length} 条</span>${state.clipSelected.length < list.length ? button('全选', 'clip-select-all', '', 'text-action') : ''}${remove}</div>`;
  }
  const clipTab = `<button type="button" class="${state.clipTab === 'clipboard' ? 'selected' : ''}${state.clipLocked ? ' clip-locked' : ''}" data-action="clip-tab" data-value="clipboard" aria-label="剪贴板，${state.clipLocked ? '已锁定，双击解锁' : '双击锁定'}" aria-pressed="${state.clipLocked}">剪贴板${state.clipLocked ? icon('lock', 'clip-lock') : ''}</button>`;
  const tabs = `<div class="tabs-pill">${clipTab}${button('常用语', 'clip-tab', 'phrases', state.clipTab === 'phrases' ? 'selected' : '')}</div>`;
  const trailing = state.clipTab === 'clipboard' ? ctl('clip-manage-menu', '更多管理操作', 'more') : button('添加常用语', 'snippet-add', '', 'ctl', icon('add')) + ctl('clip-manage-menu', '更多管理操作', 'more');
  return `<div class="panel-title">${ctl('return', '返回键盘', 'hide')}${tabs}${trailing}</div>`;
}
// 全局唯一一种浮层：标题行 + 文字行 + 取消块，所有菜单与确认都走它。
function sheet(title, rows) {
  return `<div class="clip-menu-backdrop" data-action="clip-close"><div class="clip-menu-sheet"><div class="clip-menu-title">${esc(title)}</div>${rows.map(([action, label, cls]) => button(label, action, '', 'row' + (cls ? ' ' + cls : ''))).join('')}${button('取消', 'clip-close', '', 'clip-menu-cancel')}</div></div>`;
}
function footer() {
  const total=nativeIme&&!localInput?nativeCursor.total:editor.value.length,cursor=nativeIme&&!localInput?nativeCursor.position:editor.selectionEnd;
  const drag=state.cursorDrag,locked=lockedNow(),zoneClass='cursor-zone'+(drag?drag.selecting?' selecting':' dragging':'')+(locked?' locked':'');
  const label=locked?'起点锁定，拖动调整终点，长按解除锁定':'底部中间空白可拖动光标，长按后拖动框选文字';
  return `<div class="keyboard-footer">${settings.footerSwitcher ? button('底部输入法菜单', 'switcher', '', '', icon('keyboard')) : '<span class="empty"></span>'}<div class="${zoneClass}" data-action="cursor-gesture" role="slider" tabindex="0" aria-label="${label}" aria-valuemin="0" aria-valuemax="${total}" aria-valuenow="${cursor}"><span class="cursor-hint"></span><span class="cursor-focus"></span><span class="cursor-contact"></span></div>${settings.footerClipboard ? button('底部剪贴板', 'clipboard', '', '', icon('copy')) : '<span class="empty"></span>'}</div>${joyGuide()}`;
}
function selectionEditor(){
  if(!state.selectionActions||state.voice||state.hidden)return '';
  // 面板只由外部点击关闭，页头不再放「完成」：左边选中字数、右边「全选」，中间不留空位。
  const locked=lockedNow(),mode=locked?'起点锁定 · 拖动调整终点':'移动光标 · 长按锁定起点';
  const zero=selectedCount===0?' disabled':'';
  return `<div class="selection-editor-backdrop" data-action="selection-close"></div><section class="selection-editor" aria-label="选字编辑框"><div class="selection-editor-header"><span class="selection-count">${selectedCount<0?'选字':`已选 ${selectedCount} 字`}</span>${button('全选','select-all','','selection-all','全选')}</div><div class="selection-paragraphs">${button('向上选段','selection-paragraph','-1','','↑ 向上选段',locked?'':' disabled')}${button('向下选段','selection-paragraph','1','','↓ 向下选段',locked?'':' disabled')}</div><div class="cursor-zone selection-mini-track${locked?' locked':''}" data-action="cursor-gesture" data-track="mini" role="slider" tabindex="0" aria-label="${esc(mode)}"><span class="cursor-hint"></span><span class="cursor-focus"></span><span class="cursor-contact"></span><span class="selection-mini-label">${esc(mode)}</span><span class="selection-mini-left">←</span><span class="selection-mini-right">→</span></div><div class="selection-editor-clipboard">${button('剪切','cut-text','','',`剪切`,zero)}${button('复制','copy-text','','selection-copy','复制',zero)}${button('粘贴',nativeIme&&!localInput?'paste-text':'paste-last','','','粘贴')}</div></section>`;
}
function feedbackZone(){return keyboard.querySelector(cursorFeedback.mini?'.selection-mini-track':'.keyboard-footer .cursor-zone');}
// 摇杆辅助线：门槛环画出「推到这里才开始加速」，原点标出「长按激活的中心」，
// 连线给出当前推力方向。没有它根本看不出该往哪拖、拖多远。
function joyGuide() {
  return '<div class="joy-guide" hidden><i class="joy-band"></i><i class="joy-ray"></i><i class="joy-origin"></i></div>';
}
function syncJoyGuide() {
  const guide=keyboard.querySelector('.joy-guide');if(!guide)return;
  const joy=gesture?.joy;
  if(!joy||!cursorFeedback.visible||gesture?.mini){guide.hidden=true;return;}
  guide.hidden=false;
  const kb=keyboard.getBoundingClientRect();
  const ox=joy.originX-kb.left,oy=joy.originY-kb.top;
  // 环画在「最后一档的门槛」上，不是 ρ=1 的边界——那才是实际开始加速的位置。
  const gate=joystick.gates[joystick.gates.length-1];
  const halfW=Math.max(2,joy.a*gate),up=Math.max(2,joy.bUp*gate),down=Math.max(2,joy.bDown*gate);
  const height=up+down;
  const band=guide.querySelector('.joy-band');
  band.style.cssText=`left:${ox-halfW}px;top:${oy-up}px;width:${halfW*2}px;height:${height}px;border-radius:50% / ${(up/height*100).toFixed(2)}% ${(up/height*100).toFixed(2)}% ${(down/height*100).toFixed(2)}% ${(down/height*100).toFixed(2)}%`;
  const origin=guide.querySelector('.joy-origin');
  origin.style.cssText=`left:${ox-4}px;top:${oy-4}px`;
  const dx=cursorFeedback.clientX-kb.left-ox,dy=cursorFeedback.clientY-kb.top-oy;
  const ray=guide.querySelector('.joy-ray');
  ray.style.cssText=`left:${ox}px;top:${oy}px;width:${Math.hypot(dx,dy).toFixed(1)}px;transform:rotate(${(Math.atan2(dy,dx)*180/Math.PI).toFixed(1)}deg)`;
  guide.classList.toggle('fast',joy.level>0);
}
function setHintOpacity(zone,value){
  const hint=zone.querySelector('.cursor-hint');if(!hint)return;
  // 锁定态的短线亮度由 CSS 决定（.locked），别用内联值把它压回拖动时的透明度。
  if(zone.classList.contains('locked'))hint.style.removeProperty('opacity');
  else hint.style.opacity=String(value);
}
function syncCursorFeedback() {
  syncJoyGuide();
  const zone=feedbackZone();if(!zone||!cursorFeedback.visible)return;
  const bounds=zone.getBoundingClientRect(),x=cursorFeedbackPosition(bounds.width,cursorFeedback.clientX-bounds.left);
  zone.classList.toggle('selecting',cursorFeedback.selecting);zone.classList.toggle('dragging',!cursorFeedback.selecting);
  const line=zone.querySelector('.cursor-contact'),focus=zone.querySelector('.cursor-focus');
  line.style.transform=`translateX(${x-18}px) scaleX(${cursorFeedback.selecting?1.12:1})`;focus.style.transform=`translateX(${x-33}px)`;
  line.style.opacity='.88';focus.style.opacity='.7';setHintOpacity(zone,0);
}
function fadeCursorFeedback() {
  syncCursorFeedback();cursorFeedback.visible=false;
  const zone=feedbackZone();if(!zone)return;
  const duration=matchMedia('(prefers-reduced-motion: reduce)').matches?0:125;
  for(const node of zone.querySelectorAll('.cursor-contact,.cursor-focus'))node.animate([{opacity:node.style.opacity||0},{opacity:0}],{duration,easing:'cubic-bezier(.23,1,.32,1)',fill:'forwards'});
  zone.classList.remove('dragging','selecting');setHintOpacity(zone,.23);
}
// 「来自剪贴板」：官方在候选栏展示刚复制的内容。标签（验证码时换成防转发提示）悬在键盘上沿之上、
// 不占键盘高度：真机是原生 popup 窗口（clipHint 桥接，与官方 window 一致），网页原型用同样位置的浮层。
function clipCandidateLabel(){
  return /^\d{4,6}$/.test(state.clipCandidate.trim())?'请勿转发验证码，避免财产损失':'来自剪贴板';
}
function clipCandidateBar(){
  const text=state.clipCandidate;
  if(!text)return '';
  return `<div class="clip-preview"><span class="clip-preview-source">${esc(clipCandidateLabel())}</span><div class="candidate-bar clip-candidate">${button(text,'clip-candidate-insert',text,'clip-preview-text',esc(text))}${button('关闭剪贴板预览','clip-candidate-dismiss','','clip-preview-close','<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="m9 9 6 6m0-6-6 6"/></svg>')}</div></div>`;
}
function toolbarItemButton(key){
  if(String(key).startsWith('custom:')){
    const row=settings.customToolbar.find(item=>item.id===key);
    if(!row)return '';
    const body=state.toolbarSort
      ? `<span class="tb-label">${esc(row.label)}</span><span class="tb-remove" role="button" aria-label="从工具栏移除" data-action="toolbar-custom-remove" data-value="${row.id}">×</span>`
      : esc(row.label);
    return button(row.label,'toolbar-custom',row.id,'toolbar-custom-btn'+(state.toolbarSort?' sortable':''),body);
  }
  const entry=toolbarItems.find(([name])=>name===key);
  if(!entry)return '';
  return button(entry[1],key,'','toolbar-key'+(state.toolbarSort?' sortable':''),icon(key));
}
function toolbar() {
  if (state.comp) return `<div class="candidate-bar">${candidateStrip(currentCandidates())}${button('更多候选', 'more-candidates', '', 'candidate-expand', icon(state.page==='candidates'?'hide':'forward'))}</div>`;
  if(state.language==='zh'&&associatedWords.length&&(!privateField||localInput))return `<div class="candidate-bar">${candidateStrip(associatedWords,'association-candidate')}${button('更多候选','more-candidates','','candidate-expand',icon(state.page==='candidates'?'hide':'forward'))}</div>`;
  if(englishPrefix&&!privateField&&state.language==='en')return `<div class="candidate-bar">${candidateStrip(englishCandidates(englishPrefix),'english-candidate')}</div>`;
  if(state.toolbarSort)return `<div class="candidate-bar clip-candidate"><span class="clip-source">拖动图标排序</span>${button('完成','toolbar-sort-done','','toolbar-done')}<span class="clip-hint">${settings.toolbar.some(key=>String(key).startsWith('custom:'))?'点自定义项上的 × 可移除':'长按自定义项无法移除'}</span></div><div class="toolbar toolbar-sort">${settings.toolbar.map(toolbarItemButton).join('')}</div>`;
  const clipBar=state.clipCandidate&&settings.clipboardCandidate?clipCandidateBar():'';
  const bar=`<div class="toolbar">${settings.toolbar.map(toolbarItemButton).join('')}</div>`;
  return clipBar||bar;
}
function candidateStrip(words,action='candidate') {
  return `<div class="candidate-visible" tabindex="0" aria-label="候选字，左右滑动浏览" data-query="${esc(state.comp?.raw||words.join(','))}" style="--candidate-count:${settings.candidateCount}">${words.map(word=>button('候选：'+word,action,word,'candidate-hold',esc(word))).join('')}</div>`;
}
function currentCandidates() {
  if(!state.comp)return associatedWords;
  if(state.language==='en')return englishCandidates(state.comp.raw);
  const useNative=nativePinyinActive(),limit=state.page==='candidates'?2000:240;
  let words=useNative?JSON.parse(nativeIme.pinyinCandidates(state.comp.raw,limit)).words||[]:(state.layout==='9'?nineCandidates:pinyinCandidates)(state.comp.raw,limit);
  if(useNative)words=[...new Set([...phraseCandidates(state.comp.raw),...words])];
  if(settings.rememberInput&&!useNative)words=rankLearned(words,learning,(state.layout==='9'?'9:':'p:')+state.comp.raw.toLowerCase());
  if(context&&!useNative)words=[...words.filter(word=>associatedWords.includes(word)),...words.filter(word=>!associatedWords.includes(word))];
  if(state.layout==='26'&&(!privateField||localInput)&&hasEnglishWord(state.comp.raw)){
    const english=englishCandidates(state.comp.raw);
    words=hasPinyinMatch(state.comp.raw)?[...new Set([...words,...english])]:english;
  }
  return words.length?words:[state.comp.raw];
}
// 展开候选面板：按讯飞小米版 K60 真机实测的三栏结构还原
// （左侧相近拼音列 / 中间 4 列候选网格 / 右侧操作列），不再在面板里重复顶部候选栏。
const NEIGHBOR_KEYS={q:'was',w:'qeasd',e:'wrsdf',r:'etdfg',t:'ryfgh',y:'tughj',u:'yihjk',i:'uojkl',o:'ipkl',p:'ol',a:'qwszx',s:'awedzxc',d:'serfxcv',f:'drtgcvb',g:'ftyhvbn',h:'gyujbnm',j:'huiknm',k:'jiulm',l:'kop',z:'asx',x:'zsdc',c:'xsdfv',v:'cdfgb',b:'vfghn',n:'bghjm',m:'nhjk'};
const PINYIN_FINALS=['iang','iong','uang','ueng','uai','uan','iao','ian','ing','ong','ang','eng','ai','ei','ui','ao','ou','iu','ie','ue','ve','er','an','en','in','un','ia','ua','uo','a','o','e','i','u','v'];
function pinyinFinal(syllable){for(const final of PINYIN_FINALS)if(syllable.endsWith(final))return final;return '';}
function firstSyllable(raw){const clean=(raw||'').split("'")[0].toLowerCase();for(let size=Math.min(6,clean.length);size>=1;size--){const part=clean.slice(0,size);if(isSyllable(part))return part;}return '';}
// 官方左列实测：ni→bi/ji/mi、hao→bao/gao，即首个音节的声母换成键盘相邻键后的合法拼音，末尾补自身。
function nearbyPinyin(raw){
  const first=firstSyllable(raw);
  if(!first)return [];
  const final=pinyinFinal(first),head=first.slice(0,first.length-final.length);
  if(head.length!==1||!final)return [];
  const picks=[];
  for(const letter of NEIGHBOR_KEYS[head]||''){const candidate=letter+final;if(isSyllable(candidate)&&!picks.includes(candidate))picks.push(candidate);}
  picks.sort();
  if(raw.length>first.length){
    // shortcut: 仅复用实测的 n/u 尾音和邻键变体，获得完整易混拼音表后替换。
    const variants=['n','u'].map(tail=>first+tail).filter(isSyllable);
    return [...new Set([...variants,...picks])].slice(0,4);
  }
  return [...picks.slice(0,3),first];
}
function candidatePanel(){
  const picks=nearbyPinyin(state.comp?.raw||''),words=currentCandidates();
  const characters=words.filter(word=>/^[\u3400-\u9fff]$/u.test(word)),chars=candidateMode==='characters';
  const mode=button(chars?'切换词候选':'切换字候选','candidate-mode','','panel-key key function',`<span class="candidate-mode-label"><span${chars?'':' class="selected"'}>词</span><span class="mode-divider">/</span><span${chars?' class="selected"':''}>字</span></span>`).replace('<button ',`<button aria-pressed="${chars}"${!state.comp||!characters.length?' disabled':''} `);
  candidateGridWords=chars?characters:words;candidateGridShown=Math.min(96,candidateGridWords.length);
  return `<div class="candidate-panel"><div class="candidate-card"><div class="panel-pinyin" aria-label="相近拼音">${picks.map(syllable=>button(syllable,'candidate-pinyin',syllable,'pinyin-item',esc(syllable))).join('')}</div><div class="candidate-grid">${candidateCells(candidateGridWords.slice(0,candidateGridShown))}</div></div><div class="panel-actions">${mode}${button('删除','delete','','panel-key key function',icon('delete'))}${button('上一页','candidate-page','up','panel-key key function',icon('hide','flip'))}${button('下一页','candidate-page','down','panel-key key function',icon('hide'))}${button('返回键盘','more-candidates','','panel-key key function','返回')}</div></div>${footer()}`;
}
function candidateCells(words){return words.map(word=>button('候选：'+word,state.comp?'candidate':'association-candidate',word,'candidate-hold',`<span class="candidate-word">${esc(word)}</span>`)).join('');}
function appendCandidates(){
  const grid=keyboard.querySelector('.candidate-grid');if(!grid||candidateGridShown>=candidateGridWords.length)return false;
  const start=candidateGridShown;candidateGridShown=Math.min(start+96,candidateGridWords.length);
  grid.insertAdjacentHTML('beforeend',candidateCells(candidateGridWords.slice(start,candidateGridShown)));
  layoutCandidateGrid([...grid.children].slice(start));return true;
}
function layoutCandidateGrid(newItems){
  const grid=keyboard.querySelector('.candidate-grid');if(!grid)return;
  const width=grid.clientWidth,items=newItems||[...grid.children];
  const widths=items.map(item=>item.firstElementChild.getBoundingClientRect().width+16);
  items.forEach((item,index)=>{item.style.gridColumn=`span ${Math.min(4,Math.ceil(widths[index]/(width/4)))}`;item.classList.toggle('wrap-word',widths[index]>width);});
}
function updateCandidatePages(){
  const grid=keyboard.querySelector('.candidate-grid');if(!grid)return;
  keyboard.querySelector('[data-action="candidate-page"][data-value="up"]').disabled=grid.scrollTop<=1;
  keyboard.querySelector('[data-action="candidate-page"][data-value="down"]').disabled=candidateGridShown>=candidateGridWords.length&&grid.scrollTop+grid.clientHeight>=grid.scrollHeight-1;
}
const upperHints = ['1','2','3','4','5','6','7','8','9','0'];
const middleHints = ['~','!','@','/','%','“','”','*','?'];
const bottomHints = ['(',')','-','_',':',';','`'];
function letters() {
  const upper = state.language === 'zh' || state.shift > 0;
  const row = (letters, hints) => [...letters].map((letter, i) => key(upper ? letter : letter.toLowerCase(), 'letter', upper ? letter : letter.toLowerCase(), '', hints[i])).join('');
  const left = state.language === 'zh' ? func('分词', 'split', '', 'side') : `<button type="button" data-action="shift" data-value="" aria-label="英文大小写" aria-pressed="${Boolean(state.shift)}" class="key function side shift-key">${icon(state.shift?'caps':'shift')}</button>`;
  const comma=state.language==='en' && settings.comma==='，'?',':settings.comma;
  const period=state.language==='en' && settings.period==='。'?'.':settings.period;
  const middle=state.language==='en'?['-','!','@','#','%',"'",'&','"','?']:middleHints;
  const bottom=state.language==='en'?['(',')','-','_',':',';','/']:bottomHints;
  return `${toolbar()}<div class="key-rows"><div class="key-row">${row('QWERTYUIOP',upperHints)}</div><div class="key-row row-middle">${row('ASDFGHJKL',middle)}</div><div class="key-row row-third">${left}${row('ZXCVBNM',bottom)}${func('删除', 'delete', 'delete', 'side')}</div><div class="key-row">${func('符','symbols')}${func('123','numbers')}${key(comma)}${spaceKey()}${key(period)}${languageKey()}${enterKey('side')}</div></div>${footer()}`;
}
function nine() {
  const groups=['分词','ABC','DEF','GHI','JKL','MNO','PQRS','TUV','WXYZ'];
  const cells=groups.map((label,i)=>button(label,i===0?'nine-split':'nine',String(i+1),'key t9-letter',`<span class="hint">${i+1}</span><span>${esc(label)}</span>`));
  const clear=button('清除拼音','clear-pinyin','','key function',esc('清除')).replace('<button ',state.comp?'<button ':'<button disabled ');
  return `${toolbar()}<div class="t9-grid"><div class="t9-symbols">${['，','。','？','！','@','…','～','、','：','；'].map(s=>button(s,'insert',s)).join('')}</div>${cells[0]}${cells[1]}${cells[2]}${func('删除','delete','delete')}${cells[3]}${cells[4]}${cells[5]}${clear}${cells[6]}${cells[7]}${cells[8]}${key('0','insert','0','function')}${func('符','symbols')}${func('123','numbers')}${spaceKey()}${languageKey()}${enterKey()}</div>${footer()}`;
}
function numbers() {
  return `${toolbar()}<div class="number-body"><div class="number-column"><div class="number-strip">${['.','/','+','-','%','：','@','¥','='].map(s=>button(s,'insert',s)).join('')}</div><div class="key-row">${func('符','symbols')}</div></div><div class="number-main">${[[1,2,3],[4,5,6],[7,8,9]].map(row=>`<div class="key-row">${row.map(n=>key(n)).join('')}</div>`).join('')}<div class="key-row">${func('返回','letters')}${key('0')}${button('空格','space','','key function',icon('space'))}</div></div><div class="number-column"><div class="key-row">${func('删除','delete','delete')}</div><div class="key-row">${key('*','insert','*','function')}</div><div class="key-row">${key('#','insert','#','function')}</div><div class="key-row">${enterKey()}</div></div></div>${footer()}`;
}
const symbols = {
  '常用': ['、','-','.','+',':','!','#','=','_','()','/','*','（）','~','×','%','°','……','<>'],
  '中文': ['，','。','？','！','：','；','……','～','“”','“','”','、','（）','（','）','——','‘’','‘','’','·','@','＆','＊','＃','《》','《','》','￥','〈〉','〈','〉','＄','［］','［','］','￡','｛｝','｛','｝','￠','【】','【','】','％','〖〗','〖','〗','／','〔〕','〔','〕','＼','『』','『','』','＾','「」','「','」','｜','﹁','﹂','—','｀','．'],
  '英文': ['@qq.com','《','》','&','¥','@',',','“”','?',',',';','$','^',':-)','^0^','www.','.com'],
  '网址': ['.',':','/','@','-','_','www.','wap.','.com','.cn','.com.cn','.net','baidu.com','google.com.hk','taobao.com','mop.com','sina.com.cn','bbs.','news.','.org','.edu.cn','blog.','http://','https://','ftp://','3g.','tieba.com','sohu.com','tmall.com','sports.','weibo.com','tianya.cn'],
};
const symbolPairs=new Set(['()','<>','{}','[]','（）','“”','‘’','《》','〈〉','［］','｛｝','【】','〖〗','〔〕','『』','「」','""']);
function symbolPanel() {
  return `<div class="symbol-body"><div class="symbol-nav"><div class="symbol-categories">${Object.keys(symbols).map(name => button(name, 'symbol-category', name, state.symbol === name ? 'selected' : '')).join('')}</div>${state.locked?func('删除','delete','delete'):button('锁定符号页','symbol-lock','','',icon('lock'))}${button('返回','symbol-back')}</div><div class="symbol-grid ${state.symbol==='网址'?'url':''}">${symbols[state.symbol].map(s=>button(s,'symbol',s,s==='baidu.com'&&state.symbol==='网址'?'triple':s.length>5||(state.symbol==='常用'&&s==='<>')?'wide':'')).join('')}</div></div>${footer()}`;
}
// 常用语分组：细窄 chip 一行，长按可重命名或删除；末尾一个圆形＋表示新建分组。
function phraseGroupPills(){
  return `<div class="group-pills" aria-label="常用语分组，长按可重命名或删除">${phraseGroups.map((group,i)=>button(group.name,'clip-group',String(i),'group-pill'+(i===state.clipGroup?' selected':'')+(String(state.clipGroupMenu?.index)===String(i)?' pressed':''))).join('')}${phraseGroups.length<phraseGroupLimit?button('新建分组','clip-group-add','','group-pill group-add',icon('add')):''}</div>`;
}
function groupMenuPanel(){
  const item=state.clipGroupMenu,group=phraseGroups[item.index];
  if(!group)return '';
  return sheet(`${group.name} · ${group.items.length} 条`,[['group-rename','重命名分组'],['group-delete','删除分组','danger']]);
}
function openGroupMenu(index){state.clipGroupMenu={index};clipMenu=null;state.clipConfirm=null;render();keyboard.querySelector('.clip-menu-sheet button')?.focus({preventScroll:true});}
function closeGroupMenu(){state.clipGroupMenu=null;render();}
function groupNameError(name,selfIndex){
  if(!name)throw Error('请填写分组名称');
  if(name.length>8)throw Error('分组名称最多 8 个字');
  if(phraseGroups.some((group,i)=>i!==selfIndex&&group.name===name))throw Error('已有同名分组');
}
function createPhraseGroup(rawName){
  const name=String(rawName||'').trim();
  groupNameError(name,-1);
  if(phraseGroups.length>=phraseGroupLimit)throw Error('分组数目已达上限（'+phraseGroupLimit+' 个）');
  const previous=phraseGroups;
  phraseGroups=[...phraseGroups,{name,items:[]}];
  if(!saveSnippets()){phraseGroups=previous;throw Error('未能保存，分组名称已保留，请重试');}
  state.clipGroup=phraseGroups.length-1;state.clipTab='phrases';render();
  toast('已新建分组「'+name+'」');
  return name;
}
function renamePhraseGroup(index,rawName){
  const name=String(rawName||'').trim();
  groupNameError(name,index);
  const group=phraseGroups[index],previous=group.name;
  group.name=name;
  if(!saveSnippets()){group.name=previous;throw Error('未能保存，分组名称已保留，请重试');}
  render();toast('分组已改名为「'+name+'」');
}
function deletePhraseGroup(index){
  const group=phraseGroups[index];
  if(!group)return;
  if(phraseGroups.length<=1)throw Error('至少要保留一个常用语分组');
  const previous=phraseGroups;
  phraseGroups=phraseGroups.filter((_,i)=>i!==index);
  if(!saveSnippets()){phraseGroups=previous;throw Error('未能保存，分组没有删除，请重试');}
  groupUndo={group,index};
  state.clipGroup=Math.max(0,Math.min(state.clipGroup>index?state.clipGroup-1:state.clipGroup,phraseGroups.length-1));
  state.clipGroupMenu=null;snippetUndo=null;
  render();toast('已删除分组「'+group.name+'」，可在管理菜单里撤销。');
}
function undoGroupDelete(){
  if(!groupUndo)return;
  if(phraseGroups.length>=phraseGroupLimit){toast('分组已达上限，请先腾出位置再撤销');return;}
  if(phraseGroups.some(group=>group.name===groupUndo.group.name)){toast('已有同名分组，请先重命名后再撤销');return;}
  const previous=phraseGroups,index=Math.min(groupUndo.index,phraseGroups.length);
  phraseGroups=[...phraseGroups.slice(0,index),groupUndo.group,...phraseGroups.slice(index)];
  if(!saveSnippets()){phraseGroups=previous;return;}
  state.clipGroup=index;groupUndo=null;toast('已恢复分组');
}
// 新建 / 重命名分组共用键盘内编辑框。
function openPhraseGroupEditor(index=-1){
  if(localInput){toast('请先保存或取消当前编辑');return;}
  if(state.comp)commit(state.comp.raw);
  const renaming=index>=0,group=renaming?phraseGroups[index]:null;
  if(renaming&&!group)return;
  const input=document.createElement('input');input.type='text';input.placeholder='分组名称';input.setAttribute('aria-label','常用语分组名称');input.setAttribute('maxlength','8');
  if(renaming)input.value=group.name;
  const done=document.createElement('button');done.textContent=renaming?'保存':'新建';
  done.onclick=()=>{try{if(renaming)renamePhraseGroup(index,input.value);else createPhraseGroup(input.value);closeLocalEditor();state.page='clipboard';render();}catch(error){toast(error.message);}};
  const cancel=document.createElement('button');cancel.textContent='返回';cancel.onclick=()=>{closeLocalEditor();state.page='clipboard';render();};
  localInput=input;editor=input;localPanel.replaceChildren(input,done,cancel);localPanel.hidden=false;state.page='letters';render();input.focus();
}
function clipRow(text,i,managing){
  const picked=managing&&state.clipSelected.includes(text);
  const cls=managing?(picked?'clip-text clip-pick picked':'clip-text clip-pick'):'clip-text';
  const action=managing?'clip-pick':'paste';
  const body=managing?`<span class="pick-box" aria-hidden="true">${picked?'✓':''}</span><span class="pick-text">${esc(text)}</span>`:esc(text);
  return button(text,action,text,cls,body).replace(`data-action="${action}"`,`data-action="${action}" data-index="${i}"`);
}
function clipConfirmPanel(){ return sheet(state.clipConfirm.title,[['clip-confirm-ok','确定','danger']]); }
// 新建常用语分组：官方上限 20 个分组。
// 删除若干条剪贴板/常用语记录，并保留一次撤销。
function removeSnippets(predicate){
  const list=currentList(),next=list.filter((text,i)=>!predicate(text,i));
  if(next.length===list.length)return false;
  const undo={tab:state.clipTab,group:currentGroup(),list:[...list],removed:list.filter(text=>!next.includes(text))};
  if(!setSnippetList(next))return false;
  snippetUndo=undo;return true;
}
function undoSnippetDelete(){
  const prior=snippetUndo;if(!prior)return;
  if(prior.tab==='phrases'&&!phraseGroups.includes(prior.group)){toast('原分组已变化，不能撤销到其它分组');return;}
  const list=prior.tab==='clipboard'?clips:prior.group.items,missing=prior.removed.filter(text=>!list.includes(text));
  if(list.length+missing.length>(prior.tab==='clipboard'?snippetLimit:phraseItemLimit)){toast('列表已达上限，请先腾出位置再撤销');return;}
  const restored=[...list];
  for(const text of missing){const next=prior.list.slice(prior.list.indexOf(text)+1).find(item=>list.includes(item));restored.splice(next===undefined?restored.length:restored.indexOf(next),0,text);}
  if(setSnippetList(restored,prior.tab,prior.group))snippetUndo=null;
}
// R2-18：把剪贴板/常用语里的内容固定成工具栏按钮（官方「添加到自定义工具栏」）。
function addCustomToolbar(text){
  const value=String(text).trim();
  if(!value)throw Error('这条内容不能加到工具栏');
  if(settings.customToolbar.some(row=>row.text===value))throw Error('这条内容已经在自定义工具栏里了');
  if(settings.customToolbar.length>=12)throw Error('自定义工具栏最多 12 个');
  const id='custom:'+Math.random().toString(36).slice(2,8);
  settings.customToolbar=[...settings.customToolbar,{id,label:(value.replace(/\s+/g,'').slice(0,4)||'短语'),text:value}];
  settings.toolbar=[...settings.toolbar,id];
  persist();render();toast('已添加到自定义工具栏，长按工具栏图标可排序。');
}
function removeCustomToolbar(id){
  const row=settings.customToolbar.find(item=>item.id===id);
  settings.customToolbar=settings.customToolbar.filter(item=>item.id!==id);
  settings.toolbar=settings.toolbar.filter(key=>key!==id);
  persist();render();if(row)toast('已移除「'+row.label+'」');
}
function clipboard() {
  const list = currentList();
  const managing = state.clipManage && state.clipTab==='clipboard';
  return `${clipHeader()}${state.clipTab==='phrases'?phraseGroupPills():''}<div class="clip-list" aria-label="长按内容或空白处管理">${list.length?list.map((text,i)=>clipRow(text,i,managing)).join(''):`<div class="empty-state">${state.clipTab==='clipboard'?'还没有复制内容':'这个分组还是空的'}</div>`}</div>${footer()}${clipMenu?clipContextMenu():''}${state.clipGroupMenu?groupMenuPanel():''}${state.clipConfirm?clipConfirmPanel():''}`;
}
function clipContextMenu(){
  const item=clipMenu.index>=0;
  const rows=item
    ?[['context-edit','编辑'],['context-delete','删除'],['toolbar-add','添加到自定义工具栏'],...(state.clipTab==='clipboard'?[['context-favorite','设为常用语']]:[]),['context-copy','复制']]
    :state.clipTab==='clipboard'
      ?[['snippet-add','添加内容'],['system-paste','从系统剪贴板导入'],['clip-manage','批量管理'],['clear-snippets','清空列表','danger']]
      :[['snippet-add','添加常用语'],['clip-group-add','新建分组'],['clear-snippets','清空这个分组的常用语','danger']];
  if(snippetUndo&&snippetUndo.tab===state.clipTab&&(state.clipTab==='clipboard'||snippetUndo.group===currentGroup()))rows.push(['undo-snippet','撤销删除']);
  if(groupUndo)rows.push(['group-undo','撤销删除分组']);
  return sheet(item?clipMenu.text:'管理内容',rows);
}
function openClipMenu(index=-1){const list=currentList();clipMenu={index,text:index<0?'':list[index]};render();keyboard.querySelector('.clip-menu-sheet button')?.focus({preventScroll:true});}
function closeClipMenu(){clipMenu=null;state.clipConfirm=null;state.clipGroupMenu=null;render();}
function saveSnippets(){try{localStorage.setItem('voxfix-snippets-v1',JSON.stringify({clips,phrases:phraseGroups,clipSignature:/^[a-f0-9]{64}$/.test(nativeClipSignature)?nativeClipSignature:''}));updatePhraseOptions();return true;}catch{const message='未能保存，请检查可用空间后重试';if(localInput===$('snippet-content'))$('snippet-error').textContent=message;else toast(message);return false;}}
function setSnippetList(list,tab=state.clipTab,group=currentGroup()){const previous=tab==='clipboard'?clips:group.items;if(tab==='clipboard')clips=list;else group.items=list;if(saveSnippets())return true;if(tab==='clipboard')clips=previous;else group.items=previous;return false;}
function openSnippet(index=-1){
  if(localInput){state.page='letters';render();localInput.focus();toast('请先保存或取消当前编辑');return;}
  const original=index<0?'':currentList()[index];
  snippetEdit=snippetDraft?.edit||{tab:state.clipTab,index,original,group:state.clipGroup};
  $('snippet-title').textContent=(snippetEdit.index<0?'添加':'编辑')+(snippetEdit.tab==='clipboard'?'剪贴板内容':`常用语 · ${currentGroup().name}`);
  $('snippet-content').value=snippetDraft?.text??original;snippetDraft=null;$('snippet-error').textContent='';
  if(nativeIme){if(state.comp)commit(state.comp.raw);localInput=$('snippet-content');editor=localInput;localPanel.append(snippetForm);localPanel.hidden=false;state.page='letters';}
  else snippetDialog.showModal();$('snippet-content').focus();
}
function openEmojiSearch(){
  if(localInput){toast('请先保存或取消当前编辑');return;}
  if(state.comp)commit(state.comp.raw);
  const input=document.createElement('input');input.type='text';input.placeholder='搜索表情';input.setAttribute('aria-label','搜索表情');input.value=emojiQuery;
  const done=document.createElement('button');done.textContent='搜索';done.onclick=()=>{if(state.comp)commit(currentCandidates()[0]||state.comp.raw);emojiQuery=input.value;closeLocalEditor();state.emojiTab='Emoji';state.page='emoji';render();};
  const cancel=document.createElement('button');cancel.textContent='返回';cancel.onclick=()=>{closeLocalEditor();state.page='emoji';render();};
  localInput=input;editor=input;localPanel.replaceChildren(input,done,cancel);localPanel.hidden=false;state.page='letters';render();input.focus();
}
const layouts = [['9','9键拼音','拼'],['26','26键拼音','拼'],['en26','26键英文','EN'],['fullhand','全屏手写','手']].filter(([id])=>id!=='fullhand'||!nativeIme||nativeIme.handReady());
function layoutPanel() {
  return `<div class="panel-title"><span>键盘选择</span>${button('返回键盘','return','','',icon('back'))}</div><div class="layout-grid">${layouts.map(([id,label,glyph])=>button(label,'layout',id,(id==='en26'?state.layout==='26'&&state.language==='en':id==='26'?state.layout==='26'&&state.language==='zh':state.layout===id)?'selected':'',`<span class="layout-icon">${glyph}</span><span>${label}</span>`)).join('')}</div>${footer()}`;
}
const emojiGroups = {'常用':['😊','🍀','😂','⭐','🌸','🙄','😭','🌝'],'精选':['👍','❤️','🎉','🙏','💪','👌','🥳','👏'],'花样':['🌸','🌷','🌼','🌹','💐','🌻','🍃','🍀'],'黄脸':['😀','😁','😂','🥲','😊','😍','🤔','😭'],'动物':['🐱','🐶','🐰','🐼','🦊','🐸','🐻','🐵']};
const emojiNames={'😊':'微笑 开心 smile','🍀':'幸运 四叶草 clover','😂':'笑 哭 laugh','⭐':'星星 star','🌸':'花 flower','🙄':'白眼 无语','😭':'哭 伤心 cry','🌝':'月亮 moon','👍':'赞 支持 good','❤️':'爱 心 love','🎉':'庆祝 party','🙏':'谢谢 祈祷','💪':'加油 力量','👌':'好 ok','🥳':'庆祝 party','👏':'鼓掌','🐱':'猫 cat','🐶':'狗 dog','🐰':'兔 rabbit','🐼':'熊猫 panda','🦊':'狐狸 fox','🐸':'青蛙 frog','🐻':'熊 bear','🐵':'猴 monkey'};
Object.assign(emojiNames,{'🌷':'花 郁金香 tulip','🌼':'花 菊花 blossom','🌹':'花 玫瑰 rose','💐':'花 花束 bouquet','🌻':'花 向日葵 sunflower','🍃':'叶 植物 leaf','😀':'笑 开心 smile','😁':'笑 开心 grin','🥲':'笑 哭 tear','😍':'爱 喜欢 love','🤔':'思考 疑问 think'});
let recentEmoji=[];
try{const saved=JSON.parse(localStorage.getItem('voxfix-emoji-v1'));if(Array.isArray(saved))recentEmoji=saved.filter(word=>Object.values(emojiGroups).some(group=>group.includes(word))).slice(0,16);}catch{}
function emojiPanel() {
  const tabs = ['Emoji','颜文字'];
  let body = '';
  if (state.emojiTab === 'Emoji'){const words=emojiQuery?[...new Set(Object.values(emojiGroups).flat())].filter(s=>(s+' '+(emojiNames[s]||'')).toLowerCase().includes(emojiQuery.toLowerCase())):state.emojiCategory==='常用'?[...new Set([...recentEmoji,...emojiGroups.常用])].slice(0,16):emojiGroups[state.emojiCategory];body=`${emojiSearchOpen&&!nativeIme?`<input class="emoji-search-input" aria-label="搜索表情" value="${esc(emojiQuery)}" placeholder="输入笑、猫、爱等关键词">`:''}<div class="emoji-categories">${Object.keys(emojiGroups).map(s=>button(s,'emoji-category',s,state.emojiCategory===s?'selected':'')).join('')}</div><div class="emoji-grid">${words.map(s=>button(s,'insert',s,'emoji-hold')).join('')}${button('删除','delete','','key',icon('delete'))}</div>${words.length?'':'<p class="emoji-placeholder">没有匹配的表情</p>'}`;}
  else if (state.emojiTab==='颜文字') body=`<div class="emoji-grid" style="grid-template-columns:repeat(3,1fr)">${['(＾▽＾)','(╥﹏╥)','(•‿•)','(ง •̀_•́)ง','(￣▽￣)','♡'].map(s=>button(s,'insert',s,'',esc(s))).join('')}</div>`;
  return `<div class="emoji-top">${button('搜索表情','emoji-search','','',icon('search'))}${tabs.map(s=>button(s,'emoji-tab',s,state.emojiTab===s?'selected':'')).join('')}${button('返回键盘','return','','',icon('back'))}</div><div class="emoji-body">${body}</div>${footer()}`;
}
// 功能菜单里符号/数字/编辑/设置没有专属图标，用方形字标代替，避免四个一模一样的方块。
const menuGlyphs={symbols:'符',numbers:'123',edit:'编',custom:'设'};
function menuPanel() { return `<div class="panel-title"><span>${nativeIme?'功能菜单':'功能菜单 · 未展开页面的补充入口'}</span>${button('返回键盘','return','','',icon('back'))}</div><div class="menu-grid">${[...toolbarItems.filter(([id])=>!['menu','hide'].includes(id)),['symbols','符号'],['numbers','数字'],['edit','编辑文字'],['custom','键盘设置']].map(([id,label])=>button(label,id,'','',`<span class="menu-glyph">${menuGlyphs[id]||icon(id)}</span><span>${label}</span>`)).join('')}</div>${footer()}`; }
function editPanel() { return `<div class="panel-title"><span>${nativeIme?'编辑文字':'编辑文字 · 补充试用'}</span>${button('返回键盘','return','','',icon('back'))}</div><div class="menu-grid">${[['select-all','全选'],['copy-text','复制'],['cut-text','剪切'],['paste-last','粘贴'],['cursor-left','左移'],['cursor-right','右移'],['delete','删除'],['enter',localInput?'换行':enterLabel]].map(([id,label])=>button(label,id,'',id==='delete'?'key':'',icon(id==='delete'?'delete':'copy')+`<span>${label}</span>`)).join('')}</div>${footer()}`; }
function handPanel(){return `${inkWords.length?`<div class="candidate-bar">${candidateStrip(inkWords,'ink-candidate')}</div>`:toolbar()}<div class="hand-controls key-row">${func('符','symbols')}${func('123','numbers')}${spaceKey()}${func('拼','last-pinyin')}${func('删除','delete','delete')}${enterKey()}</div>${footer()}`;}
const VOICE_PHASES=['loading','listening','paused','cancel','recognizing'];
const VOICE_TITLES={loading:'正在开启麦克风',listening:'倾听中',paused:'已暂停',cancel:'松手取消',recognizing:'识别中'};
const VOICE_HINTS={loading:'稍等片刻',listening:'松手输入 · 上滑取消',paused:'点继续，接着说',cancel:'下滑继续说',recognizing:'轻点取消'};
const VOICE_ARM=55;               // 上滑到这个距离就进入取消区
let voicePanelFresh=false;        // 本次 render 是否播进场；一次会话只播一次
const voiceReduced=matchMedia('(prefers-reduced-motion: reduce)');
let voiceRenderer=null;
function voiceHint(phase){return state.voice?.long&&phase==='listening'?'说完后点结束':VOICE_HINTS[phase]||'';}
function voiceStage(phase) {
  return `<div class="voice-stage ${phase}" aria-hidden="true"><div class="voice-follow"><div class="voice-orb"><span class="voice-red"></span><canvas class="voice-texture" hidden></canvas></div></div></div>`;
}
function voicePanel() {
  const phase=state.voice.phase;
  const entering=voicePanelFresh; voicePanelFresh=false;
  return `<div class="voice-panel ${phase}${entering?' entering':''}" data-action="cancel-recognition" role="status" aria-live="polite"><span class="voice-title">${VOICE_TITLES[phase]||''}</span>${voiceStage(phase)}<span class="voice-hint">${voiceHint(phase)}</span><button type="button" class="voice-cancel" data-action="cancel-recognition">取消识别</button></div><div class="voice-filler"></div>${footer()}`;
}
// 只改类名与文案，不重建 DOM。拖拽 / 声幅期间必须走这条。
function paintVoice() {
  const panel=keyboard.querySelector('.voice-panel'), phase=state.voice?.phase;
  if(!panel||!phase) return;
  for(const node of [panel,panel.querySelector('.voice-stage')]) if(node) for(const name of VOICE_PHASES) node.classList.toggle(name,name===phase);
  const title=panel.querySelector('.voice-title'), text=VOICE_TITLES[phase]||'';
  if(title&&title.textContent!==text) title.textContent=text;
  const hint=panel.querySelector('.voice-hint');
  if(hint)hint.textContent=voiceHint(phase);
}
let voiceRaf=null, voiceLast=0;
let voiceLevel=0, voiceLevelTarget=0, voiceLevelAt=0, voiceFlow=0, voiceElapsed=0;
let ballSize=1, ballVel=0;
let voiceDragT=0, voiceDragShift=0;     // 上滑跟手
function setVoiceLevel(level){
  voiceLevelTarget=Number.isFinite(level)?Math.max(0,Math.min(1,level)):0;
  voiceLevelAt=performance.now();
}
function voiceFrame(now){
  if(!state.voice){stopVoiceLoop();return;}
  const dt=Math.min(.05,(now-voiceLast)/1000);voiceLast=now;voiceElapsed+=dt;
  const ph=state.voice.phase, listening=ph==='listening'||ph==='cancel';
  // A missed/silent meter callback must release the last loud reading.
  let target=listening&&now-voiceLevelAt<260?voiceLevelTarget:0;
  if(state.voice.demo&&listening)target=Math.pow(Math.max(0,Math.sin(voiceElapsed*3.8)),2)*.65;
  voiceLevel+=(target-voiceLevel)*(1-Math.exp(-dt/(target>voiceLevel ? .055 : .18)));
  const rest=ph==='recognizing' ? .86 : ph==='loading' ? .94 : ph==='paused' ? .96 : 1;
  if(!listening){const release=Math.exp(-dt/.08);voiceDragT*=release;voiceDragShift*=release;}
  const size=rest+voiceLevel*.10;
  // Small fixed steps keep the same spring at 60/120Hz and after a delayed frame.
  for(let remaining=dt;remaining>0;){const step=Math.min(remaining,1/120);ballVel+=((size-ballSize)*100-ballVel*10)*step;ballSize+=ballVel*step;remaining-=step;}
  const s=voiceReduced.matches?1:ballSize*(1-voiceDragT*.24);
  const follow=keyboard.querySelector('.voice-follow');
  if(follow)follow.style.transform=`translateY(${voiceReduced.matches?0:voiceDragShift.toFixed(1)}px) scale(${s.toFixed(4)})`;
  const orb=keyboard.querySelector('.voice-orb');
  if(orb){orb.style.setProperty('--red',voiceDragT.toFixed(3));orb.style.setProperty('--mic',voiceLevel.toFixed(3));}
  if(!voiceReduced.matches&&ph!=='paused')voiceFlow+=dt*(listening ? .38+voiceLevel*1.5 : .22);
  voiceRenderer?.draw(voiceReduced.matches?0:voiceFlow,voiceLevel,voiceDragT,ph==='paused'||ph==='loading'?1:0);
  voiceRaf=requestAnimationFrame(voiceFrame);
}
function startVoiceLoop(){ if(!voiceRaf){ voiceLast=performance.now(); voiceRaf=requestAnimationFrame(voiceFrame); } }
function stopVoiceLoop(){ cancelAnimationFrame(voiceRaf); voiceRaf=null; }
// 新会话从"小气球"开始长；上一次会话的拖拽/红色也要清掉
function beginVoiceBall(){keyboard.querySelector('.voice-ghost')?.remove();ballSize=.96;ballVel=0;voiceLevel=voiceLevelTarget=voiceDragT=voiceDragShift=voiceFlow=voiceElapsed=0;}
// 上滑取消：整颗球近乎 1:1 跟手，同步缩小并交叉淡入红调。越过阈值只切类名，不 render。
// 位移上限随缩小量放宽，保证球顶不会顶出键盘区被 #phone 裁掉。
function syncVoiceDrag(g,clientY) {
  if(!state.voice) return;
  const stage=keyboard.querySelector('.voice-stage');
  if(!stage) return;
  const dy=Math.min(0,clientY-g.y);
  if(dy<-2) stage.classList.add('dragging');
  if(!stage.classList.contains('dragging')) return;
  const t=Math.min(1,Math.abs(dy)/VOICE_ARM);
  voiceDragT=t;
  voiceDragShift=Math.max(-(34+18*t),dy*.9);
  const cancelled=dy<=-VOICE_ARM;
  if(cancelled!==g.cancel){ g.cancel=cancelled; state.voice.phase=cancelled?'cancel':'listening'; paintVoice(); }
}
// 退场：状态立刻清空（不影响任何逻辑与测试），把面板摘下来当"幽灵"原位重放一次内收动画。
function exitVoice() {
  stopVoiceLoop();
  const renderer=voiceRenderer;voiceRenderer=null;
  const live=keyboard.querySelector('.voice-panel');
  const kb=keyboard.getBoundingClientRect();
  const ghost=live?{node:live,rect:live.getBoundingClientRect(),cls:[...live.classList].filter(name=>name!=='voice-panel').join(' ')}:null;
  state.voice=null; render();
  if(!ghost){renderer?.dispose();return;}
  const {node,rect,cls}=ghost;
  node.className=('voice-ghost '+cls+' leaving').trim();
  node.style.cssText=`position:absolute;left:${rect.left-kb.left}px;top:${rect.top-kb.top}px;width:${rect.width}px;height:${rect.height}px;margin:0;z-index:5;pointer-events:none`;
  keyboard.append(node);
  setTimeout(()=>{node.remove();renderer?.dispose();},220);
}
function popup() {
  if (state.popup==='switcher') return `<div class="popup">${button('讯飞输入法小米版','dismiss-popup','','selected','讯飞输入法小米版　✓')}${button('键盘设置','custom')}</div>`;
  if (state.popup?.type==='key') return `<div class="popup key-popup" style="left:${state.popup.x}px;top:${state.popup.y}px">${state.popup.options.map((s,i)=>button(s,'long-insert',s,i===(gesture?.choice||0)?'selected':'')).join('')}</div>`;
  return '';
}
function pageLabel() {
  if(state.voice) return `${state.voice.long?'长语音':'长按空格语音'} · ${state.voice.phase==='loading'?'开启麦克风':state.voice.phase==='paused'?'已暂停':state.voice.phase==='recognizing'?'识别中':state.voice.phase==='cancel'?'上滑取消':'倾听中'}${state.voice.demo?'（演示）':''}`;
  return {letters:state.layout==='fullhand'?'全屏手写':state.layout==='9'?'9键中文':state.language==='zh'?'26键中文':'26键英文',candidates:'全部候选',numbers:'数字键盘',symbols:`${state.symbol}符号`,clipboard:state.clipTab==='clipboard'?'剪贴板':'常用语',layouts:'键盘选择',emoji:'表情',menu:'功能菜单 · 补充试用',edit:'编辑文字 · 补充试用'}[state.page] || '键盘';
}
const referenceMap = {letters:'keyboard-001.jpg',en:'keyboard-014.jpg',numbers:'keyboard-022.jpg','常用':'keyboard-026.jpg','中文':'keyboard-028.jpg','英文':'keyboard-027.jpg','网址':'keyboard-029.jpg',clipboard:'clipboard.jpg',phrases:'phrases.jpg',layouts:'keyboard-042.jpg',emoji:'keyboard-050.jpg',switcher:'keyboard-055.jpg',voice:'voice.jpg',cancel:'voice-cancel.jpg',recognizing:'voice-recognizing.jpg'};
function render() {
  for(const node of keyboard.querySelectorAll('[data-scroll-key]'))scrollPositions.set(node.dataset.scrollKey,node.scrollTop);
  const previousCandidates=keyboard.querySelector('.candidate-visible');
  const candidateScroll=previousCandidates?.scrollLeft||0;
  // 语音期间不把可见编辑框置为只读：只读的 textarea/input 不再绘制插入光标（实测确认）。
  // 输入守卫已由 keydown / pointerdown 分支（state.voice 直接返回）负责。
  $('send').disabled=Boolean(state.voice);
  snippetForm.querySelector('button[type=submit]').disabled=Boolean(state.voice);
  $('long-voice').disabled=Boolean(state.voice)||!speechReady;
  $('pause-voice').disabled=!state.voice?.long||state.voice.demo||!['listening','paused'].includes(state.voice.phase);
  $('pause-voice').textContent=state.voice?.phase==='paused'?'继续':'暂停';
  $('finish-voice').disabled=!state.voice?.long||!['listening','paused'].includes(state.voice.phase);
  $('cancel-voice').disabled=!state.voice;
  $('polish-text').disabled=!polishReady||polishBusy||Boolean(state.voice);
  const handVisible=state.layout==='fullhand' && state.page==='letters' && !state.voice && !state.hidden;
  $('handwriting-surface').hidden=!handVisible;
  keyboard.className=`keyboard${state.night?' night':''}${localInput?' local-edit':''}`;
  const localHeight=localInput?(localInput===$('snippet-content')?Math.min(180,550-settings.height):44):0;
  localPanel?.classList.toggle('snippet-edit',localInput===$('snippet-content'));
  $('phone').style.setProperty('--local-editor-height',localHeight+'px');
  for(const [key,value] of Object.entries({'--keyboard-height':(settings.height+localHeight)+'px','--key-font':settings.fontSize+'px','--key-gap':settings.gap+'px','--key-radius':settings.radius+'px','--kb-bg':settings.background})) keyboard.style.setProperty(key,value);
  if(handVisible&&!nativeIme)keyboard.style.setProperty('--keyboard-height','155px');
  keyboard.hidden=state.hidden; $('reopen').hidden=!state.hidden;
  const pages={letters:()=>state.layout==='fullhand'?handPanel():state.layout==='9'?nine():letters(),candidates:candidatePanel,numbers,symbols:symbolPanel,clipboard,layouts:layoutPanel,emoji:emojiPanel,menu:menuPanel,edit:editPanel};
  // 退场幽灵要在重绘后活下来（它只活 260ms），否则一次多余的 render 就会把动画吞掉。
  const voiceGhost=keyboard.querySelector(':scope > .voice-ghost');
  if(state.voice&&keyboard.querySelector(':scope > .voice-panel'))paintVoice();
  else{
    voiceRenderer?.dispose();voiceRenderer=null;
    keyboard.innerHTML=(state.voice?voicePanel():(pages[state.page]||letters)())+popup()+selectionEditor();
    if(state.voice)voiceRenderer=createVoiceOrb(keyboard.querySelector('.voice-texture'));
    if(voiceGhost)keyboard.append(voiceGhost);
  }
  if(gesture?.target.classList.contains('key')){
    const held=[...keyboard.querySelectorAll('.key')].find(key=>key.dataset.action===gesture.action&&key.dataset.value===(gesture.value||''));
    if(held){held.classList.add('pressed');gesture.target=held;}
  }
  const candidates=keyboard.querySelector('.candidate-visible');
  if(candidates && previousCandidates?.dataset.query===candidates.dataset.query && previousCandidates.textContent===candidates.textContent)candidates.scrollLeft=candidateScroll;
  layoutCandidateGrid();
  keyboard.classList.toggle('text-selecting',Boolean(state.cursorDrag?.selecting));syncSelectionFeedback();
  for(const [selector,id] of [['.number-strip','numbers'],['.symbol-grid','symbols:'+state.symbol],['.clip-list','clipboard:'+state.clipTab],['.t9-symbols','nine'],['.candidate-grid','candidates:'+candidateMode+':'+(state.comp?.raw||associatedWords.join(','))],['.emoji-body','emoji:'+state.emojiTab+':'+state.emojiCategory]]){
    const node=keyboard.querySelector(selector);if(node){node.dataset.scrollKey=id;const top=scrollPositions.get(id)||0;if(selector==='.candidate-grid')while(top+node.clientHeight>node.scrollHeight&&appendCandidates()){}node.scrollTop=top;}
  }
  updateCandidatePages();
  $('page-label').textContent=state.hidden?'键盘已收起':pageLabel();
  $('send').innerHTML=icon(editor.value?'send':'voice');
  // 「来自剪贴板」标签悬在键盘上沿之外（真机由原生 popup 画），键盘高度与「有没有预览」完全无关：
  // 这里绝不能再拿 clip-preview 的实测高度反推增量——45.14 这种亚像素值经 Math.ceil 后会让整块键盘
  // 在有预览／没预览之间抖 1 CSS px，正是用户抱怨的「顶出来」。高度仍由上面的基础路径统一决定。
  const clipPreview=keyboard.querySelector('.clip-preview');
  if(nativeIme)nativeIme.clipHint?.(clipPreview?clipCandidateLabel():'');
  if(nativeIme)nativeIme.resize(Math.ceil(settings.height+localHeight));
  syncCursorFeedback();
  if(handVisible){$('handwriting-surface').style.bottom=nativeIme?'108px':($('phone').getBoundingClientRect().bottom-hostEditor.closest('.composer').getBoundingClientRect().top+5)+'px';if(nativeIme)$('handwriting-surface').style.top=(localHeight+44)+'px';$('ink-guide').hidden=Boolean(inkPad?.hasInk);inkPad?.draw();}
  nightShade.hidden=!state.night||state.hidden;
  nightShade.style.height=($('phone').getBoundingClientRect().bottom-(handVisible?$('handwriting-surface'):keyboard).getBoundingClientRect().top)+'px';
  nativeSettings?.classList.toggle('night',state.night);
  document.querySelectorAll('#quick-pages button').forEach(b=>b.setAttribute('aria-pressed', b.dataset.value===state.page));
  let ref=state.voice?(state.voice.phase==='cancel'?'cancel':state.voice.phase==='recognizing'?'recognizing':'voice'):state.popup==='switcher'?'switcher':state.page==='symbols'?state.symbol:state.page==='clipboard'?state.clipTab:state.page==='letters'&&state.language==='en'?'en':state.page;
  $('reference-image').src='reference/'+(referenceMap[ref]||referenceMap.letters);
  $('reference-label').textContent=['menu','edit','candidates'].includes(state.page)?'此页面未在录屏中展开；当前展示全键盘参考。':state.layout==='9'&&state.page==='letters'?'录屏只展示了九宫格入口，未展示九宫格画面。':'截自你提供的录屏；个人剪贴板和常用语内容已隐藏。';
}
function feedback(message) { $('feedback').textContent=message; }
function toast(message) { clearTimeout(toastTimer); $('toast').textContent=message; $('toast').hidden=false; toastTimer=setTimeout(()=>$('toast').hidden=true,2600); feedback(message); }
function persist() { updatePhraseOptions();try { localStorage.setItem('voxfix-layout-v1',JSON.stringify(settings)); } catch { feedback('当前浏览器不能保存设置，可以导出文件留存。'); } }
function applyText(result, end=result.cursor) { editor.value=result.text; editor.setSelectionRange(result.cursor,end); editor.scrollTop=editor.scrollHeight; $('send').innerHTML=icon(editor.value?'send':'voice'); if(nativeIme&&!localInput&&state.comp)nativeIme.compose(state.comp.raw); }
function insert(value) { state.clipCandidate='';englishPrefix='';if(nativeIme&&state.comp){commit(currentCandidates()[0]||state.comp.raw);if(state.comp)commit(state.comp.raw);}state.comp=null;rememberContext(value);if(nativeIme&&!localInput){nativeIme.commit(value);applyText({text:'',cursor:0});return;} applyText(replaceRange(editor.value,editor.selectionStart,editor.selectionEnd,value)); }
function commit(word) {
  if(!state.comp)return;
  if(nativePinyinActive()&&word!==state.comp.raw&&word&&(JSON.parse(nativeIme.pinyinCandidates(state.comp.raw,state.page==='candidates'?2000:240)).words||[]).includes(word)&&/[\u3400-\u9fff]/.test(word)){
    const selected=JSON.parse(nativeIme.pinyinChoose(state.comp.raw,word,settings.rememberInput&&!privateField));
    if(!selected.committed){feedback('当前选词未提交，请重新选择。');return;}
    rememberContext(word);state.comp=selected.remaining?{start:0,raw:selected.remaining}:null;
    applyText({text:selected.remaining,cursor:selected.remaining.length});
    if(state.page==='candidates')state.page='letters';return;
  }
  if((!nativeIme||localInput)&&editor.value.slice(state.comp.start,state.comp.start+state.comp.raw.length)!==state.comp.raw){state.comp=null;render();return;}
  const previous=state.comp,consumed=state.language==='zh'&&state.layout!=='9'?pinyinConsumed(previous.raw,word):previous.raw.length,remaining=previous.raw.slice(consumed);
  const checked=nativeIme&&!localInput&&typeof nativeIme.commitAccepted==='function';
  if(checked&&!JSON.parse(nativeIme.commitAccepted(word)).committed){feedback('文字未提交，请重试。');return;}
  if(settings.rememberInput&&!privateField&&state.language==='zh'&&word&&word.length<=60&&previous.raw.length<=60){learning=learnWord(learning,(state.layout==='9'?'9:':'p:')+previous.raw.slice(0,consumed).toLowerCase(),word);try{localStorage.setItem('voxfix-learning-v1',JSON.stringify(learning));}catch{feedback('选词记忆只保留到本页关闭。');}updateLearningStatus();}
  rememberContext(word);
  if(nativeIme&&!localInput){state.comp=remaining?{start:0,raw:remaining}:null;if(!checked)nativeIme.commit(word);applyText({text:remaining,cursor:remaining.length});}
  else{applyText(replaceRange(editor.value,previous.start,previous.start+previous.raw.length,word+remaining));state.comp=remaining?{start:previous.start+word.length,raw:remaining}:null;}
  if(state.page==='candidates')state.page='letters';
}
function inputLetter(value) {
    state.clipCandidate='';
    if(nativeIme&&!localInput&&state.language==='en'){const prior=englishPrefix,char=state.shift?value.toUpperCase():value.toLowerCase();insert(char);englishPrefix=privateField?'':(prior+char).slice(-60);return;}
    const start=editor.selectionStart;
    if(!state.comp || start!==state.comp.start+state.comp.raw.length || start!==editor.selectionEnd) state.comp={start,raw:''};
    const letter=state.language==='zh'?value.toLowerCase():value;
    const result=replaceRange(editor.value,start,editor.selectionEnd,letter);
    state.comp.raw+=letter; applyText(result);
}
function deleteText() {
  const hadSelectionActions=state.selectionActions;clearSelectionLock();
  // 先记录删除前候选栏是否可见：清空文本后必须把联想/英文候选一起刷掉，否则会留下“文字删完了候选还在”。
  const hadCandidateBar=Boolean(englishPrefix)||(state.language==='zh'&&associatedWords.length>0)||state.page==='candidates';
  context='';associatedWords=[];
  if(state.layout==='fullhand' && inkPad?.hasInk){inkPad.clear();render();return;}
  if(nativeIme&&!localInput&&!state.comp){
    // 连删期间不再每个 tick 重建整键盘：只有候选栏内容确实会变化时才刷新。
    nativeIme.delete();englishPrefix=englishPrefix.slice(0,-1);
    if(hadCandidateBar||englishPrefix||hadSelectionActions)render();
    return;
  }
  const result=erase(editor.value,editor.selectionStart,editor.selectionEnd);
  if(state.comp && editor.selectionStart===state.comp.start+state.comp.raw.length) { state.comp.raw=state.comp.raw.slice(0,-1); if(!state.comp.raw) state.comp=null; } else state.comp=null;
  applyText(result); render();
  if(nativeIme&&!localInput&&!state.comp)nativeIme.compose('');
  if(!state.comp && !associatedWords.length && state.page==='candidates'){state.page='letters';render();}
}
function showPage(page) { if(state.voice) cancelVoice();clipMenu=null;state.clipGroupMenu=null;state.clipConfirm=null;clearTimeout(clipHold?.timer);clipHold=null;if(['letters','numbers','symbols'].includes(state.page))state.returnPage=state.page;if(page==='symbols'){const origin=['letters','numbers'].includes(state.page)?state.page:state.returnPage;if(origin!=='symbols')state.symbolReturn=origin;}state.popup=null;state.page=page;state.hidden=false;render(); }
function showTab(name) { if(nativeIme&&name==='custom'){$('custom-panel').hidden=false;nativeSettings.showModal();return;} $('try-panel').hidden=name!=='try'; $('custom-panel').hidden=name!=='custom'; document.querySelectorAll('[data-tab]').forEach(b=>b.setAttribute('aria-selected',b.dataset.tab===name)); }
function startVoice(long=false) {
  if(state.voice)return;
  englishPrefix='';
  if(nativeIme){if(!nativeIme.voiceReady()){toast('语音正在准备，请稍后再试。');return;}if(state.comp)commit(state.comp.raw);state.popup=null;state.voice={phase:'listening',long:false,local:localInput,snapshot:localInput?{text:editor.value,start:editor.selectionStart,end:editor.selectionEnd}:null};beginVoiceBall();voicePanelFresh=true;render();startVoiceLoop();if(localInput)nativeIme.startLocalVoice();else nativeIme.startVoice();return;}
  const demo=$('demo-voice').checked;
  if(!demo && !speechReady){toast('离线语音尚未就绪，请先检查左侧的语音状态。');return;}
  state.comp=null; state.popup=null;
  const snapshot={text:editor.value,start:editor.selectionStart,end:editor.selectionEnd};
  beginVoiceBall();state.voice={phase:demo?'listening':'loading',snapshot,demo,long}; voicePanelFresh=true; render(); startVoiceLoop();
  if(!demo) {
    const session=state.voice;
    session.controller=new LocalVoice(text=>{
      if(state.voice!==session)return;
      session.text=text;
      if(text && session.phase!=='cancel')applyText(voiceResult(snapshot,text));
    },error=>{
      if(state.voice!==session)return;
      preserveVoice(session);
      toast(error.name==='NotAllowedError'?'麦克风未获授权，请点击“启用麦克风”后允许录音。':(error.message||'录音失败')+'；已保留收到的识别文字。');
    },hotwords,level=>{if(state.voice===session)setVoiceLevel(level);});
    session.controller.start().then(()=>{
      if(state.voice!==session)return;
      session.phase=gesture?.cancel?'cancel':'listening';render();feedback(session.long?'正在本机离线识别。点击暂停或结束，结束后补全标点。':'正在本机离线识别。上滑取消，松手后补全标点。');
    }).catch(()=>{});
    return;
  }
  feedback('语音交互演示：没有录音，松手后输入你设置的演示文字。');
  const session=state.voice, units=[...new Intl.Segmenter('zh',{granularity:'grapheme'}).segment(settings.voiceText)].map(s=>s.segment);
  let count=0;
  state.voiceTimer=setInterval(()=>{ if(state.voice!==session) return; if(session.phase==='listening') { count=Math.min(units.length,count+1); applyText(voiceResult(snapshot,units.slice(0,count).join(''))); } },550);
}
function cancelVoice() { if(!state.voice) return; if(nativeIme){nativeIme.cancelVoice();if(state.voice.local===editor)applyText(voiceResult(state.voice.snapshot,'',true),state.voice.snapshot.end);exitVoice();return;} clearInterval(state.voiceTimer);state.voice.controller?.cancel(); applyText(voiceResult(state.voice.snapshot,'',true),state.voice.snapshot.end); exitVoice(); }
function preserveVoice(session) {
  if(nativeIme){nativeIme.stopVoice();exitVoice();return;}
  clearInterval(state.voiceTimer);session.controller?.cancel();
  if(session.text)applyText(voiceResult(session.snapshot,session.text));
  else applyText(voiceResult(session.snapshot,'',true),session.snapshot.end);
  exitVoice();
}
function finishVoice(cancelled) {
  if(!state.voice) return;
  if(nativeIme){if(cancelled){cancelVoice();return;}state.voice.phase='recognizing';render();nativeIme.stopVoice();return;}
  clearInterval(state.voiceTimer);
  if(cancelled) { cancelVoice(); return; }
  const session=state.voice; session.phase='recognizing'; render();
  if(session.controller){
    session.controller.finish().then(text=>{
      if(state.voice!==session)return;
      if(text)applyText(voiceResult(session.snapshot,text));else applyText(voiceResult(session.snapshot,'',true),session.snapshot.end);
      exitVoice();feedback(text?'离线识别完成，已做整句识别和标点恢复。':'没有识别到语音，原文字保持不变。');
    }).catch(error=>{if(state.voice!==session)return;preserveVoice(session);toast((error.message||'本地识别失败')+'；已保留收到的识别文字。');});
    return;
  }
  setTimeout(()=>{ if(state.voice!==session) return; applyText(voiceResult(session.snapshot,settings.voiceText)); exitVoice(); feedback('语音演示结束；本页未使用真实语音识别。'); },900);
}
function activate(action,value='') {
  if(action!=='clip-tab'||value!=='clipboard')clipTabTap=null;
  // 关闭面板只收起面板：正文、光标、选区与起点锁定都按原样保留。
  if(action==='selection-close'){state.selectionActions=false;render();return;}
  // 除「保留现有选区」的几个操作外，其它按键都会改正文 —— 旧起点随之失效，锁定一并清掉。
  if(!['select-all','copy-text','cut-text','paste-text','paste-last','selection-paragraph'].includes(action))clearSelectionLock();
  else if(['cut-text','paste-text','paste-last'].includes(action))clearSelectionLock();
  if(action==='selection-paragraph'){
    if(!lockedNow())return;   // 未锁定时禁用选段：不允许暗中造出一个起点
    if(nativeIme&&!localInput){nativeIme.selectionParagraph?.(Number(value));return;}
    const start=editor.selectionStart,end=editor.selectionEnd;
    if(!localSelection||localSelection.text!==editor.value)localSelection={editor,text:editor.value,anchor:lockedSelection?.editor===editor&&lockedSelection.text===editor.value?lockedSelection.anchor:start,head:end};
    const head=localSelection.head;
    if(Number(value)>0){const from=editor.value[head]==='\n'?head+1:head;localSelection.head=editor.value.indexOf('\n',from);if(localSelection.head<0)localSelection.head=editor.value.length;}
    else localSelection.head=editor.value.lastIndexOf('\n',Math.max(-1,head-2))+1;
    editor.setSelectionRange(Math.min(localSelection.anchor,localSelection.head),Math.max(localSelection.anchor,localSelection.head),localSelection.head<localSelection.anchor?'backward':'forward');
    selectedCount=characterCount(editor.value.slice(editor.selectionStart,editor.selectionEnd));render();return;
  }
  if(hasNativePinyin&&!fullPinyinLoaded&&action==='layout'&&value==='9')loadFullPinyin().catch(()=>feedback('拼音词库未能加载。'));
  if(nativeIme){
    if(action==='hide'){nativeIme.hide();return;}
    if(action==='switcher'){nativeIme.switcher();return;}
    if(action==='clipboard')nativeIme.refreshClipboard();
    if(action==='system-paste'){clipMenu=null;render();nativeIme.importClipboard();return;}
    if(action==='enter'&&!localInput){englishPrefix='';if(state.comp)commit(state.comp.raw);else nativeIme.enter();render();return;}
    if(['language','twentysix','layout','last-pinyin'].includes(action)&&state.comp)commit(state.comp.raw);
    if(!localInput&&['select-all','copy-text','cut-text','paste-text','cursor-left','cursor-right'].includes(action)){if(state.comp)commit(state.comp.raw);nativeIme.edit(action);render();return;}
    if(action==='layout'&&value==='fullhand')handReady=nativeIme.handReady();
  }
  switch(action) {
    case 'english-candidate': nativeIme.completeEnglish(englishPrefix,value);englishPrefix='';break;
    case 'letter': inputLetter(value); break;
    case 'insert': case 'long-insert': insert(value); state.popup=null;if(Object.values(emojiGroups).some(group=>group.includes(value))){recentEmoji=[value,...recentEmoji.filter(word=>word!==value)].slice(0,16);try{localStorage.setItem('voxfix-emoji-v1',JSON.stringify(recentEmoji));}catch{}} break;
    case 'candidate': commit(value); break;
    case 'association-candidate': insert(value);break;
    case 'more-candidates': candidateMode='words';state.page=state.page==='candidates'?'letters':'candidates';break;
    case 'candidate-mode': candidateMode=candidateMode==='words'?'characters':'words';break;
    case 'candidate-pinyin': {
      if(!state.comp)break;
      const head=firstSyllable(state.comp.raw);
      if(!head||head===value)break;
      const raw=value+state.comp.raw.slice(head.length);
      if(nativeIme&&!localInput){state.comp.raw=raw;nativeIme.compose(raw);applyText({text:raw,cursor:raw.length});}
      else{applyText(replaceRange(editor.value,state.comp.start,state.comp.start+state.comp.raw.length,raw));state.comp.raw=raw;}
      break;
    }
    case 'candidate-page': {
      const grid=keyboard.querySelector('.candidate-grid');
      if(grid){const step=Math.max(1,Math.round(grid.clientHeight/57.1))*57.1;if(value==='down'&&grid.scrollTop+grid.clientHeight+step>=grid.scrollHeight)appendCandidates();grid.scrollTop+=(value==='up'?-1:1)*step;updateCandidatePages();}
      return;
    }
    case 'delete': deleteText(); return;
    case 'space': if(state.comp){commit(currentCandidates()[0]);if(state.language==='en')insert(' ');}else insert(' ');break;
    case 'enter': if(state.comp) commit(state.comp.raw); else insert('\n'); break;
    case 'language': state.comp=null;englishPrefix='';context='';associatedWords=[];state.language=state.language==='zh'?'en':'zh'; state.layout='26'; break;
    case 'shift': state.shift=state.shift?0:1; break;
    case 'split': if(state.comp&&!state.comp.raw.endsWith("'"))inputLetter("'"); break;
    case 'nine': inputLetter(value);break;
    case 'nine-split': if(state.comp&&!state.comp.raw.endsWith("'"))inputLetter("'");break;
    case 'clear-pinyin': if(state.comp)commit('');break;
    case 'ink-candidate': insert(value);inkPad.clear();break;
    case 'last-pinyin': state.layout=lastPinyin;state.language='zh';state.page='letters';inkPad.clear();break;
    case 'twentysix': state.layout='26';state.comp=null;state.page='letters';break;
    case 'letters': state.page='letters'; break;
    case 'numbers': case 'symbols': case 'layouts': case 'emoji': case 'menu': case 'edit': showPage(action); return;
    case 'clipboard': state.clipTab='clipboard'; showPage('clipboard'); return;
    case 'night': state.night=!state.night; break;
    case 'hide': state.hidden=true; break;
    case 'return': state.popup=null; state.page=state.returnPage; break;
    case 'dismiss-popup': state.popup=null;break;
    case 'symbol-back': state.page=state.symbolReturn; state.locked=false; break;
    case 'symbol-category': state.symbol=value; break;
    case 'symbol-lock': state.locked=!state.locked; break;
    case 'symbol': insert(value); if(symbolPairs.has(value)){if(nativeIme&&!localInput)nativeIme.moveCursor(-1);else editor.setSelectionRange(editor.selectionEnd-1,editor.selectionEnd-1);} if(!state.locked) state.page=state.symbolReturn; break;
    case 'clip-tab': {
      // 首次点击会重建按钮，按两次点击计时兼容触屏，不能依赖 DOM dblclick。
      if(value==='clipboard'){
        const now=performance.now();
        if(clipTabTap!==null&&now-clipTabTap<=350){state.clipLocked=!state.clipLocked;clipTabTap=null;}
        else clipTabTap=now;
      }
      state.clipTab=value;clipMenu=null;state.clipConfirm=null;state.clipGroupMenu=null;state.clipManage=false;state.clipSelected=[];break;
    }
    case 'paste': insert(value); clipMenu=null; if(!state.clipManage&&!(state.clipTab==='clipboard'&&state.clipLocked))state.page=state.returnPage; break;
    case 'clip-candidate-insert': state.clipCandidate='';insert(value);break;
    case 'clip-candidate-dismiss': state.clipCandidate='';break;
    case 'toolbar-custom': { if(state.toolbarSort)break; const row=settings.customToolbar.find(item=>item.id===value); if(row)insert(row.text); break; }
    case 'toolbar-custom-remove': removeCustomToolbar(value);return;
    case 'toolbar-sort-done': state.toolbarSort=false;toolbarDrag=null;render();break;
    case 'clip-pick': state.clipSelected=state.clipSelected.includes(value)?state.clipSelected.filter(item=>item!==value):[...state.clipSelected,value]; break;
    case 'clip-select-all': state.clipSelected=[...currentList()];break;
    case 'clip-manage': state.clipManage=!state.clipManage;state.clipSelected=[];state.clipConfirm=null;clipMenu=null;break;
    case 'clip-manage-menu': openClipMenu();return;
    case 'clip-manage-delete': if(!state.clipSelected.length){toast('先勾选要删除的内容。');return;} state.clipConfirm={action:'clip-manage-delete',title:`删除所选 ${state.clipSelected.length} 条记录？`};break;
    case 'clip-group': state.clipGroup=Math.max(0,Math.min(phraseGroups.length-1,Number(value)||0));clipMenu=null;state.clipGroupMenu=null;break;
    case 'clip-group-add': clipMenu=null;if(localInput){toast('请先保存或取消当前编辑');render();return;} if(nativeIme){openPhraseGroupEditor();return;} { let name='';try{name=window.prompt('新建常用语分组名称（最多 8 个字）')||'';}catch{} if(name)try{createPhraseGroup(name);}catch(error){toast(error.message);} } return;
    case 'group-menu-close': closeGroupMenu();return;
    case 'group-rename': { const index=state.clipGroupMenu?.index;state.clipGroupMenu=null;if(index===undefined)break; if(nativeIme){openPhraseGroupEditor(index);return;} { let name='';try{name=window.prompt('重命名分组（最多 8 个字）',phraseGroups[index]?.name||'')||'';}catch{} if(name)try{renamePhraseGroup(index,name);}catch(error){toast(error.message);} } break; }
    case 'group-delete': { const index=state.clipGroupMenu?.index;state.clipGroupMenu=null;const group=phraseGroups[index];if(!group)break;
      if(phraseGroups.length<=1){toast('至少要保留一个常用语分组');break;}
      state.clipConfirm={action:'group-delete',value:String(index),title:`删除分组「${group.name}」及其中 ${group.items.length} 条常用语？`};break; }
    case 'group-undo': clipMenu=null;undoGroupDelete();break;
    case 'toolbar-add': { const text=clipMenu?.text;clipMenu=null;if(text)try{addCustomToolbar(text);}catch(error){toast(error.message);} return; }
    case 'clip-confirm-cancel': state.clipConfirm=null;break;
    case 'clip-confirm-ok': { const pending=state.clipConfirm;state.clipConfirm=null;if(!pending)break;
      if(pending.action==='clip-manage-delete'){removeSnippets(text=>state.clipSelected.includes(text));state.clipSelected=[];}
      else if(pending.action==='snippet-delete'){
        if(pending.tab!==state.clipTab||pending.tab==='phrases'&&pending.group!==currentGroup()||!currentList().includes(pending.text)){toast('内容已更新，请重新长按。');break;}
        removeSnippets(text=>text===pending.text);
      }
      else if(pending.action==='clear-snippets')removeSnippets(()=>true);
      else if(pending.action==='group-delete'){try{deletePhraseGroup(Number(pending.value));}catch(error){toast(error.message);}}
      break; }
    case 'clip-close': closeClipMenu();return;
    case 'context-edit': case 'context-delete': case 'context-favorite': case 'context-copy':{
      const text=clipMenu?.text,list=currentList(),index=list.indexOf(text);clipMenu=null;
      if(index<0){toast('内容已更新，请重新长按。');render();return;}
      if(action==='context-edit'){openSnippet(index);render();return;}
      if(action==='context-delete'){state.clipConfirm={action:'snippet-delete',text,tab:state.clipTab,group:currentGroup(),title:'是否确定删除该条记录？'};render();return;}
      if(action==='context-favorite'){try{if(addPhraseItem(phraseGroups[0],text)){snippetUndo=null;saveSnippets();toast('已添加到常用语。');}}catch(error){toast(error.message);}break;}
      if(nativeIme){nativeIme.copy(text);}else if(!navigator.clipboard?.writeText)toast('系统复制不可用，可短按内容插入输入框后复制。');else navigator.clipboard.writeText(text).catch(()=>toast('系统复制未获授权，可短按内容插入输入框后复制。'));break;
    }
    case 'snippet-add': clipMenu=null;openSnippet();render();return;
    case 'snippet-edit': openSnippet(Number(value));return;
    case 'snippet-delete': clipMenu=null;removeSnippets((text,i)=>i===Number(value));break;
    case 'clear-snippets': {clipMenu=null;state.clipConfirm={action:'clear-snippets',title:state.clipTab==='clipboard'?'确定清空剪贴板的所有内容？':'确定清空这个分组的常用语？'};break;}
    case 'undo-snippet': clipMenu=null;undoSnippetDelete();break;
    case 'system-paste': clipMenu=null;render();if(!navigator.clipboard?.readText){openSnippet();toast('请粘贴系统剪贴板内容。');return;}navigator.clipboard.readText().then(text=>{if(!text.trim())throw Error('系统剪贴板没有文字');clips=addSnippet(clips,text);snippetUndo=null;saveSnippets();render();}).catch(()=>{openSnippet();toast('可在这里粘贴系统剪贴板内容。');});return;
    case 'layout': if(['9','26','en26','fullhand'].includes(value)){if(value==='fullhand'&&!handReady){toast('手写模型还未就绪，请稍后再试。');return;}if(state.layout!=='fullhand')lastPinyin=state.layout;state.layout=value==='en26'?'26':value;state.language=value==='en26'?'en':'zh';state.comp=null;state.page='letters';inkPad.clear();}break;
    case 'emoji-tab': state.emojiTab=value; break;
    case 'emoji-category': state.emojiCategory=value;emojiQuery=''; break;
    case 'emoji-search': if(nativeIme){openEmojiSearch();return;}state.emojiTab='Emoji';emojiSearchOpen=!emojiSearchOpen;if(!emojiSearchOpen)emojiQuery='';render();if(emojiSearchOpen)keyboard.querySelector('.emoji-search-input').focus();return;
    case 'switcher': state.popup=state.popup==='switcher'?null:'switcher'; break;
    case 'custom': showTab('custom'); state.popup=null;$('custom-panel').scrollIntoView({block:'nearest'}); feedback('可调整尺寸、颜色和保留入口。'); break;
    case 'cancel-recognition': if(state.voice?.phase==='recognizing') { cancelVoice(); } return;
    case 'select-all': editor.setSelectionRange(0,editor.value.length);localSelection=null;selectedCount=characterCount(editor.value); state.comp=null; break;
    case 'copy-text': case 'cut-text': { const text=editor.value.slice(editor.selectionStart,editor.selectionEnd); if(selectedCount===0||!text)break; {try{clips=addSnippet(clips,text);snippetUndo=null;saveSnippets();}catch(error){toast(error.message);return;} if(settings.clipboardCandidate)state.clipCandidate=text; if(action==='cut-text') { if(editor.selectionStart===editor.selectionEnd) applyText({text:'',cursor:0}); else insert(''); } if(nativeIme)nativeIme.copy(text);else navigator.clipboard?.writeText(text).catch(()=>toast('已存入本页剪贴板，系统复制未获授权。')); } break; }
    case 'paste-last': if(clips[0]) insert(clips[0]); break;
    case 'cursor-left': case 'cursor-right': { state.comp=null; const segments=[...new Intl.Segmenter('zh',{granularity:'grapheme'}).segment(editor.value)].map(s=>s.index); segments.push(editor.value.length); const pos=action==='cursor-left'?segments.filter(p=>p<editor.selectionStart).at(-1)??0:segments.find(p=>p>editor.selectionEnd)??editor.value.length; editor.setSelectionRange(pos,pos); break; }
    default: return;
  }
  if(['language','twentysix','layout','last-pinyin'].includes(action))fieldPreference=null;
  if(['language','twentysix','layout','last-pinyin','night'].includes(action))saveKeyboard();
  render();
}

// 按键按压态只由 JS 的 .pressed 驱动：官方（讯飞小米版白皮）按下就是换一张
// rgba(202,208,220,.451) 的 9-patch，直接叠在键盘底上，无过渡、无缩放、无位移。
// CSS 里**不能**再给 .key 写 :active —— Android WebView 对 :active 的清除不可靠
// （首次触摸还会被当成 hover 起点），会出现"松手了颜色还在"，这正是颜色退不走的根因。
function clearKeyPress(){keyboard.querySelectorAll('.key.pressed').forEach(node=>node.classList.remove('pressed'));}
keyboard.addEventListener('pointerdown',e=>{
  if(e.button!==0 || gesture) return;
  consumedClick=null;
  const row=e.target.closest('.candidate-visible');
  if(row && e.pointerType==='mouse') {
    e.preventDefault();keyboard.setPointerCapture(e.pointerId);
    gesture={id:e.pointerId,action:'candidate-scroll',x:e.clientX,scroll:row.scrollLeft,row,target:row,dragged:false};
    return;
  }
  const target=e.target.closest('[data-action]'); if(!target) return;
  if(target.dataset.action==='cursor-gesture' && !state.voice) {
    e.preventDefault(); keyboard.setPointerCapture(e.pointerId);
    // 滑轨上开始移动前先收尾未提交的拼音组合，和原来长按进入时的处理一致。
    if(nativeIme&&state.comp)commit(state.comp.raw);
    englishPrefix='';state.comp=null;state.popup=null;
    // 摇杆半径在按下这一瞬间定死：原点始终是触控点，贴边按下时靠收缩半径保住向外行程，
    // 否则在滑轨最左端按下会向左永远推不到高档、向右推一半就饱和。
    const zoneRect=target.getBoundingClientRect(),kbRect=keyboard.getBoundingClientRect();
    const side=Math.min(e.clientX-zoneRect.left,zoneRect.right-e.clientX);
    const g={id:e.pointerId,action:'cursor-gesture',x:e.clientX,y:e.clientY,lastX:e.clientX,lastY:e.clientY,long:false,selecting:false,steps:0,moved:false,selectionMoved:false,extraX:0,extraY:0,target,joy:{...joystickRadii(side,e.clientY-kbRect.top,kbRect.bottom-e.clientY),originX:e.clientX,originY:e.clientY,level:0,rate:0,acc:0,steps:0,raf:0,ts:0}};
    gesture=g;
    state.cursorDrag={origin:nativeIme&&!localInput?nativeCursor.position:editor.selectionEnd,selecting:false};
    g.mini=target.dataset.track==='mini';
    // 两条滑轨只有尺寸和位置不同：行为完全由「当前是否已锁定」决定。
    const locked=lockedNow();
    cursorFeedback={clientX:e.clientX,visible:true,selecting:locked,mini:g.mini};
    if(nativeIme&&!localInput){nativeIme.cursorPosition?.();nativeIme.gestureStart?.(locked);}
    else editor.focus({preventScroll:true});
    if(locked){
      // 已锁定：拖动只调整终点；面板被收起过就顺手再呼出，状态与收起前一致。
      state.selectionActions=true;
      beginSelectionAdjust(g);
    }
    // 未锁定：手指一碰滑轨就进入移动模式（不再要求先长按），按住不动约 350ms 才标定起点。
    g.timer=setTimeout(()=>{
      if(gesture!==g||g.moved)return;
      g.long=true;g.x=g.lastX;g.y=g.lastY;
      if(selectionLocked)unlockSelection(g);
      else{rejoy(g,g.lastX,g.lastY);lockSelection(g);}
      // 锁定切换的这一瞬间给一次轻震：官方也是用触感确认"滑轨已切换成选择"。
      if(nativeIme&&!localInput){nativeIme.haptic?.();}
      else navigator.vibrate?.(15);
      render();syncCursorFeedback();
    },cursorHoldDelay);
    render();
    return;
  }
  if(target.tagName!=='BUTTON'||target.disabled) return;
  // Scrollable lists use native scrolling and click; only keys need hold/slide capture.
  if(!target.classList.contains('key')) return;
  e.preventDefault();
  if(state.voice) return;
  keyboard.setPointerCapture(e.pointerId);
  const rect=target.getBoundingClientRect(), kbRect=keyboard.getBoundingClientRect();
  const g={id:e.pointerId,action:target.dataset.action,value:target.dataset.value,y:e.clientY,long:false,cancel:false,hint:target.querySelector('.hint')?.textContent,target}; gesture=g;
  clearKeyPress();
  target.classList.add('pressed');
  if(g.action==='delete'){g.immediate=true;g.inkErase=state.layout==='fullhand'&&inkPad?.hasInk;deleteText();}
  g.timer=setTimeout(()=>{
    if(gesture!==g) return;
    if(g.action==='space') { g.long=true; startVoice(); }
    else if(g.action==='delete') { g.long=true;if(!g.inkErase)scheduleRepeat(g,0); }
    else if(g.action==='emoji') { g.long=true;g.repeatTimer=setTimeout(()=>emitEmojiLongPress(g),320); }
    else if(g.action==='letter') { g.long=true;g.choice=0; state.popup={type:'key',x:Math.max(5,Math.min(kbRect.width-145,rect.left-kbRect.left-35)),y:Math.max(0,rect.top-kbRect.top-52),options:[...new Set([g.hint,g.value.toUpperCase(),g.value.toLowerCase()].filter(Boolean))]}; render(); }
    else if(g.action==='nine'||g.action==='nine-split'){g.long=true;insert(g.value);render();}
  },g.action==='space'?300:420);
});
keyboard.addEventListener('pointermove',e=>{
  if(!gesture || gesture.id!==e.pointerId) return;
  if(gesture.long&&gesture.action==='letter'&&state.popup?.type==='key'){
    const options=[...keyboard.querySelectorAll('.key-popup button')];
    gesture.choice=Math.max(0,options.findLastIndex(option=>e.clientX>=option.getBoundingClientRect().left));
    options.forEach((option,i)=>option.classList.toggle('selected',i===gesture.choice));return;
  }
  if(gesture.action==='candidate-scroll') {
    const distance=e.clientX-gesture.x;
    if(Math.abs(distance)>6){gesture.dragged=true;clearItemHold();}
    if(gesture.dragged)gesture.row.scrollLeft=gesture.scroll-distance;
    return;
  }
  if(gesture.action==='cursor-gesture' && state.cursorDrag) {
    const delta=e.clientX-gesture.x;
    const previousX=gesture.lastX,previousY=gesture.lastY;
    gesture.lastX=e.clientX;gesture.lastY=e.clientY;
    cursorFeedback.clientX=e.clientX;syncCursorFeedback();
    // 解除锁定的那一次长按到此为止：后续拖动不再移动任何东西，抬手后恢复自由移动。
    if(gesture.ceased)return;
    if(gesture.mini&&!gesture.long&&Math.hypot(delta,e.clientY-gesture.y)>=cursorStep){gesture.moved=true;clearTimeout(gesture.timer);}
    if(!gesture.selecting&&!gesture.moved&&Math.hypot(delta,e.clientY-gesture.y)>=cursorStep){gesture.moved=true;clearTimeout(gesture.timer);}
    if(gesture.selecting){
      if(!gesture.selectionReady){
        if(gesture.lifting){
          if(e.clientY>previousY+2||Math.abs(e.clientX-gesture.liftX)>=cursorStep)selectionReady(gesture,previousX,previousY);
          else {clearTimeout(gesture.liftTimer);gesture.liftTimer=setTimeout(()=>selectionReady(gesture),180);syncSelectionFeedback();return;}
        }else if(e.clientY-gesture.y<-5&&Math.abs(delta)<cursorStep){
          gesture.lifting=true;gesture.liftX=e.clientX;gesture.liftTimer=setTimeout(()=>selectionReady(gesture),180);syncSelectionFeedback();return;
        }else gesture.selectionReady=true;
      }
      const dx=e.clientX-gesture.x,dy=e.clientY-gesture.y;
      if(!gesture.selectionMoved&&Math.hypot(dx,dy)<5)return;
      gesture.selectionMoved=true;gesture.steps=Math.trunc(dx/cursorStep);
      // 框选与移动光标共用同一套三档：ρ 决定快慢、方向取单位向量，360° 都能推。
      moveSelection(dx+gesture.extraX,dy+gesture.extraY);syncSelectionFeedback();joystickSync(gesture,e.clientX,e.clientY);return;
    }
    // 360° 全向：ρ 用二维位移算（斜推一样能进高速档），但方向只取水平分量 ——
    // 文本光标本来只能左右走，垂直推力只用来「更快」，不用来决定往哪走。
    const joy=gesture.joy,dx=e.clientX-joy.originX,dy=e.clientY-joy.originY;
    joy.level=joystickLevel(joystickRho(dx,dy,joy),joy.level);
    const rate=dx===0?0:Math.sign(dx)*joystick.rates[joy.level];
    if(rate!==joy.rate){joy.rate=rate;joy.ts=0;}
    moveCursorGesture(gesture);jogCursor(gesture);
    return;
  }
  // 上滑取消：改成"跟手"——只改类名与 CSS 变量，走 render 会把动画打断。
  if(gesture.long && gesture.action==='space' && state.voice) { syncVoiceDrag(gesture,e.clientY); }
});
// 长按连删：对齐官方手感 —— 按住后先删一个，随后连续删除并逐渐加快。
// 首个间隔约 110ms，每个 tick 缩短 8ms，最快 35ms 封底。
function deleteRepeatDelay(step){return Math.max(35,110-step*8);}
function scheduleRepeat(g,step){
  g.repeatTimer=setTimeout(()=>{
    if(gesture!==g)return;
    deleteText();
    scheduleRepeat(g,step+1);
  },deleteRepeatDelay(step));
}
function endGesture(e,cancelled=false) {
  const g=gesture; if(!g || g.id!==e.pointerId) return;
  gesture=null; clearTimeout(g.timer);clearTimeout(g.liftTimer); clearTimeout(g.repeatTimer); clearKeyPress();
  if(g.joy?.raf){cancelAnimationFrame(g.joy.raf);g.joy.raf=0;g.joy.rate=0;}
  consumedClick={id:g.id,until:Date.now()+500};
  if(keyboard.hasPointerCapture(e.pointerId)) keyboard.releasePointerCapture(e.pointerId);
  if(g.action==='candidate-scroll') {
    if(itemHold?.fired)return;
    if(g.dragged || cancelled)candidateSuppressUntil=Date.now()+350;
    else {const target=document.elementFromPoint(e.clientX,e.clientY)?.closest('.candidate-visible button');if(target && g.row.contains(target))activate(target.dataset.action,target.dataset.value);}
    return;
  }
  if(g.action==='cursor-gesture') {
    if(nativeIme&&!localInput)nativeIme.gestureEnd?.();
    state.cursorDrag=null;
    // 不改 state.selectionActions：面板只由「点击编辑框外」或改正文收起来。
    render();fadeCursorFeedback();return;
  }
  if(!cancelled&&!(g.long&&g.action==='space')&&Number.isFinite(e.clientX)&&Number.isFinite(e.clientY)){
    const inside=element=>{if(!element)return false;const r=element.getBoundingClientRect();return e.clientX>=r.left&&e.clientX<=r.right&&e.clientY>=r.top&&e.clientY<=r.bottom;};
    if(!inside(keyboard)&&!inside(keyboard.querySelector('.key-popup')))cancelled=true;
  }
  if(cancelled) { if(state.voice) cancelVoice(); state.popup=null; render(); return; }
  if(g.long && g.action==='space') finishVoice(g.cancel);
  else if(g.long && g.action==='letter' && state.popup?.type==='key')activate('long-insert',state.popup.options[g.choice||0]);
  else if(!g.long && !g.immediate) activate(g.hint && g.y-e.clientY>35?'insert':g.action,g.hint && g.y-e.clientY>35?g.hint:g.value);
}
keyboard.addEventListener('pointerup',e=>endGesture(e));
keyboard.addEventListener('pointercancel',e=>endGesture(e,true));
// 兜底自愈：WebView 失焦、页面转后台或指针捕获被抢走时，pointerup 可能永远不来。
// 残留的按压态与被卡住的 gesture 都要在这一层清掉，否则那一下的颜色会留在屏幕上、
// 并且之后所有按键都按不动。
function dropGesture(){if(!gesture)return;if(gesture.action==='cursor-gesture'){endGesture({pointerId:gesture.id},true);return;}consumedClick={id:gesture.id,until:Date.now()+500};clearTimeout(gesture.timer);clearTimeout(gesture.repeatTimer);gesture=null;clearKeyPress();if(state.popup?.type==='key'){state.popup=null;render();}}
keyboard.addEventListener('lostpointercapture',e=>{if(gesture&&gesture.id===e.pointerId)dropGesture();});
// 失焦/转后台只清视觉，不动正在进行的语音或光标手势（它们靠自己的 pointerup 收尾）。
window.addEventListener('blur',()=>{clearKeyPress();if(gesture&&!(gesture.long&&gesture.action==='space'))dropGesture();});
keyboard.addEventListener('lostpointercapture',e=>{if(gesture?.action==='cursor-gesture')endGesture(e,true);});
document.addEventListener('visibilitychange',()=>{if(document.hidden)clearKeyPress();});
keyboard.addEventListener('click',e=>{
  const pointerClick=e.detail!==0||Boolean(e.pointerType);
  if(pointerClick&&consumedClick&&Date.now()<consumedClick.until&&(e.pointerId===undefined||e.pointerId===consumedClick.id)){consumedClick=null;e.preventDefault();return;}
  const target=e.target.closest('[data-action]'); if(!target) return;
  if(target.closest('.candidate-visible') && pointerClick && (e.pointerType==='mouse'||Date.now()<candidateSuppressUntil))return;
  if(target.dataset.action==='paste'&&Date.now()<clipSuppressUntil)return;
  if(state.toolbarSort&&!['toolbar-sort-done','toolbar-custom-remove'].includes(target.dataset.action)){e.preventDefault();return;}
  if(target.classList.contains('key') && pointerClick) return;
  activate(target.dataset.action,target.dataset.value);
});
keyboard.addEventListener('contextmenu',e=>e.preventDefault());
keyboard.addEventListener('scroll',e=>{if(e.target.matches('.candidate-grid')){const grid=e.target;if(grid.scrollTop+grid.clientHeight>=grid.scrollHeight-grid.clientHeight)appendCandidates();updateCandidatePages();}},true);
// 表情长按连发 / 长按候选词加入常用语：不抢指针，只按住计时，滑动即取消（保留列表原生滚动）。
function emojiEmit(value){
  insert(value);
  if(Object.values(emojiGroups).some(group=>group.includes(value)))recentEmoji=[value,...recentEmoji.filter(word=>word!==value)].slice(0,16);
  render();
}
function addPhraseFromCandidate(word){
  try{
    if(addPhraseItem(phraseGroups[0],word)){saveSnippets();toast('已把「'+word+'」添加到常用语。');}
    else toast('「'+word+'」已经在常用语里了。');
  }catch(error){toast(error.message);}
}
function clearItemHold(){if(!itemHold)return;clearTimeout(itemHold.timer);clearInterval(itemHold.repeatTimer);itemHold=null;}
keyboard.addEventListener('pointerdown',e=>{
  if(e.button!==0)return;
  // 鼠标在候选栏上会先起一个拖动滚动的手势，按住不动时仍然允许长按候选词。
  if(gesture&&gesture.action!=='candidate-scroll')return;
  const target=e.target.closest('.emoji-hold,.candidate-hold');if(!target)return;
  clearItemHold();
  const hold={id:e.pointerId,x:e.clientX,y:e.clientY,target,kind:target.classList.contains('emoji-hold')?'emoji':'candidate',value:target.dataset.value};
  itemHold=hold;
  hold.timer=setTimeout(()=>{
    if(itemHold!==hold)return;
    hold.fired=true;
    consumedClick={id:hold.id,until:Date.now()+700};
    if(hold.kind==='emoji'){
      emojiEmit(hold.value);
      hold.repeatTimer=setInterval(()=>{if(itemHold!==hold||state.voice){clearInterval(hold.repeatTimer);return;}emojiEmit(hold.value);},220);
    }else addPhraseFromCandidate(hold.value);
  },340);
});
keyboard.addEventListener('pointermove',e=>{if(itemHold&&e.pointerId===itemHold.id&&Math.hypot(e.clientX-itemHold.x,e.clientY-itemHold.y)>10)clearItemHold();});
for(const event of ['pointerup','pointercancel'])keyboard.addEventListener(event,e=>{if(itemHold&&e.pointerId===itemHold.id)clearItemHold();});
// 工具栏：长按进入排序模式，拖动调整顺序，自定义项可移除。
function startToolbarSort(){if(state.toolbarSort||!settings.toolbar.length)return;state.toolbarSort=true;toolbarDrag=null;render();toast('拖动图标调整顺序；自定义项点 × 可移除，完成点「完成」。');}
function moveToolbarItem(from,to){
  const list=[...settings.toolbar];
  if(from<0||to<0||from>=list.length||to>=list.length||from===to)return;
  const [item]=list.splice(from,1);list.splice(to,0,item);
  settings.toolbar=list;persist();render();
}
function toolbarSortNodes(){return [...keyboard.querySelectorAll('.toolbar [data-action]')].filter(node=>!node.classList.contains('tb-remove'));}
const toolbarKeyOf=node=>node.dataset.action==='toolbar-custom'?node.dataset.value:node.dataset.action;
keyboard.addEventListener('pointerdown',e=>{
  if(e.button!==0||gesture||e.target.closest('.candidate-bar'))return;
  const target=e.target.closest('.toolbar [data-action]');if(!target)return;
  if(state.toolbarSort){
    if(!['toolbar-custom','menu','layouts','night','clipboard','emoji','hide'].includes(target.dataset.action))return;
    e.preventDefault();
    toolbarDrag={id:e.pointerId,key:toolbarKeyOf(target),moved:false,startX:e.clientX,startY:e.clientY,over:null};
    keyboard.setPointerCapture(e.pointerId);
    return;
  }
  clearTimeout(toolbarHold?.timer);
  const hold={id:e.pointerId,x:e.clientX,y:e.clientY};toolbarHold=hold;
  hold.timer=setTimeout(()=>{if(toolbarHold!==hold)return;toolbarHold=null;startToolbarSort();},450);
});
keyboard.addEventListener('pointermove',e=>{
  if(toolbarHold&&e.pointerId===toolbarHold.id&&Math.hypot(e.clientX-toolbarHold.x,e.clientY-toolbarHold.y)>10){clearTimeout(toolbarHold.timer);toolbarHold=null;}
  if(!toolbarDrag||toolbarDrag.id!==e.pointerId)return;
  if(Math.hypot(e.clientX-toolbarDrag.startX,e.clientY-toolbarDrag.startY)>6)toolbarDrag.moved=true;
  const nodes=toolbarSortNodes();
  const over=nodes.find(node=>{const r=node.getBoundingClientRect();return e.clientX>=r.left&&e.clientX<=r.right&&e.clientY>=r.top&&e.clientY<=r.bottom;});
  nodes.forEach(node=>node.classList.toggle('drop-target',node===over));
  toolbarDrag.over=over?nodes.indexOf(over):null;
});
for(const event of ['pointerup','pointercancel'])keyboard.addEventListener(event,e=>{
  if(toolbarHold&&toolbarHold.id===e.pointerId){clearTimeout(toolbarHold.timer);toolbarHold=null;}
  if(!toolbarDrag||toolbarDrag.id!==e.pointerId)return;
  const drag=toolbarDrag;toolbarDrag=null;
  toolbarSortNodes().forEach(node=>node.classList.remove('drop-target'));
  if(!drag.moved||drag.over===null)return;
  const keys=toolbarSortNodes().map(toolbarKeyOf);
  moveToolbarItem(keys.indexOf(drag.key),drag.over);
});
keyboard.addEventListener('pointerdown',e=>{
  if(e.button!==0||!e.target.closest('.clip-list'))return;
  clearTimeout(clipHold?.timer);
  const target=e.target.closest('.clip-text'),hold={id:e.pointerId,x:e.clientX,y:e.clientY,tab:state.clipTab,text:target?.dataset.value};clipHold=hold;
  hold.timer=setTimeout(()=>{if(clipHold!==hold||state.clipTab!==hold.tab)return;const index=hold.text===undefined?-1:currentList().indexOf(hold.text);if(hold.text!==undefined&&index<0)return;hold.fired=true;consumedClick={id:hold.id,until:Date.now()+700};clipSuppressUntil=Date.now()+700;openClipMenu(index);},500);
});
keyboard.addEventListener('pointermove',e=>{if(clipHold&&e.pointerId===clipHold.id&&Math.hypot(e.clientX-clipHold.x,e.clientY-clipHold.y)>10){clearTimeout(clipHold.timer);clipHold=null;}});
// 常用语分组：长按 pill 打开“重命名 / 删除分组”，轻点仍然是切换分组。
keyboard.addEventListener('pointerdown',e=>{
  if(e.button!==0)return;
  const target=e.target.closest('.group-pill');if(!target)return;
  clearTimeout(groupHold?.timer);
  const hold={id:e.pointerId,x:e.clientX,y:e.clientY,index:Number(target.dataset.value)};groupHold=hold;
  hold.timer=setTimeout(()=>{if(groupHold!==hold)return;groupHold=null;consumedClick={id:hold.id,until:Date.now()+700};openGroupMenu(hold.index);},500);
});
keyboard.addEventListener('pointermove',e=>{if(groupHold&&e.pointerId===groupHold.id&&Math.hypot(e.clientX-groupHold.x,e.clientY-groupHold.y)>10){clearTimeout(groupHold.timer);groupHold=null;}});
for(const event of ['pointerup','pointercancel'])keyboard.addEventListener(event,e=>{if(groupHold&&e.pointerId===groupHold.id){clearTimeout(groupHold.timer);groupHold=null;}});
for(const event of ['pointerup','pointercancel'])keyboard.addEventListener(event,e=>{if(clipHold&&e.pointerId===clipHold.id){if(clipHold.fired)consumedClick={id:clipHold.id,until:Date.now()+500};clearTimeout(clipHold.timer);clipHold=null;}});
keyboard.addEventListener('contextmenu',e=>{if(!e.target.closest('.clip-list'))return;const item=e.target.closest('.clip-text');openClipMenu(item?Number(item.dataset.index):-1);});
keyboard.addEventListener('keydown',e=>{if(e.target.matches('.clip-text')&&(e.key==='ContextMenu'||(e.shiftKey&&e.key==='F10'))){e.preventDefault();openClipMenu(Number(e.target.dataset.index));}});
keyboard.addEventListener('keydown',e=>{if(clipMenu&&e.key==='Escape'){e.preventDefault();closeClipMenu();}});
function searchEmoji(input){emojiQuery=input.value;const cursor=input.selectionStart;render();const next=keyboard.querySelector('.emoji-search-input');next.focus();next.setSelectionRange(cursor,cursor);}
keyboard.addEventListener('input',e=>{if(e.target.matches('.emoji-search-input')&&!e.isComposing)searchEmoji(e.target);});
keyboard.addEventListener('compositionend',e=>{if(e.target.matches('.emoji-search-input'))searchEmoji(e.target);});
keyboard.addEventListener('keydown',e=>{
  if(!e.target.closest('.cursor-zone') || !['ArrowLeft','ArrowRight','Home','End'].includes(e.key))return;
  e.preventDefault();if(state.comp)commit(state.comp.raw);englishPrefix='';
  if(nativeIme&&!localInput){nativeIme.moveCursor(e.key==='Home'?-100:e.key==='End'?100:e.key==='ArrowLeft'?-1:1);return;}
  const position=e.key==='Home'?0:e.key==='End'?editor.value.length:moveCursor(editor.value,editor.selectionEnd,e.key==='ArrowLeft'?-1:1);
  editor.setSelectionRange(position,position);render();keyboard.querySelector('.cursor-zone').focus({preventScroll:true});
});
keyboard.addEventListener('pointerdown',e=>{ if(e.target.closest('button') && !e.target.closest('.candidate-visible,.candidate-grid,.clip-list,.symbol-grid,.number-strip,.layout-grid,.emoji-body')) e.preventDefault(); });
let editorHold=null;
document.addEventListener('pointerdown',e=>{
  if(e.target!==editor||nativeIme&&!localInput||e.button!==0)return;
  clearTimeout(editorHold?.timer);const hold={id:e.pointerId,x:e.clientX,y:e.clientY};editorHold=hold;
  hold.timer=setTimeout(()=>{if(editorHold!==hold)return;const head=editor.selectionDirection==='backward'?editor.selectionStart:editor.selectionEnd;selectionLocked=true;lockedSelection={editor,text:editor.value,anchor:head};localSelection={editor,text:editor.value,anchor:head,head};editor.setSelectionRange(head,head);selectedCount=0;state.selectionActions=true;render();},cursorHoldDelay);
});
for(const event of ['pointerup','pointercancel','pointermove'])document.addEventListener(event,e=>{if(editorHold&&e.pointerId===editorHold.id&&(event!=='pointermove'||Math.hypot(e.clientX-editorHold.x,e.clientY-editorHold.y)>8)){clearTimeout(editorHold.timer);editorHold=null;}});
document.addEventListener('visibilitychange',()=>{ if(document.hidden) {clearTimeout(editorHold?.timer);editorHold=null;if(state.voice)preserveVoice(state.voice);if(gesture)endGesture({pointerId:gesture.id},true); } });
document.addEventListener('compositionstart',e=>{if(e.target!==editor)return;nativeComposition=true;state.comp=null;render();});
document.addEventListener('compositionend',e=>{if(e.target!==editor)return;nativeComposition=false;state.comp=null;render();});
document.addEventListener('input',e=>{
  if(e.target!==editor)return;
  state.comp=null;context='';associatedWords=[];
  if(!localInput&&!nativeComposition && state.language==='zh' && e.inputType!=='insertFromPaste'){
    const raw=editor.value.slice(0,editor.selectionStart).match(/[a-z']+$/i)?.[0];
    if(raw)state.comp={start:editor.selectionStart-raw.length,raw:raw.toLowerCase()};
  }
  render();
});
document.addEventListener('keydown',e=>{
  if(e.target!==editor)return;
  if(e.isComposing || nativeComposition || e.ctrlKey || e.metaKey || e.altKey || state.voice)return;
  if(/^[a-z]$/i.test(e.key)){e.preventDefault();inputLetter(state.language==='en'&&(state.shift||e.shiftKey)?e.key.toUpperCase():e.key.toLowerCase());render();}
  else if(state.comp && e.key==='Backspace'){e.preventDefault();deleteText();}
  else if(state.comp && e.key===' '){e.preventDefault();activate('space');}
  else if(state.comp && e.key==='Enter'){e.preventDefault();commit(state.language==='zh'?currentCandidates()[0]:state.comp.raw);render();}
  else if(state.language==='zh' && state.layout==='9' && /^[2-9]$/.test(e.key)){e.preventDefault();inputLetter(e.key);render();}
  else if(state.comp && state.language==='zh' && /^[1-9]$/.test(e.key)){const word=currentCandidates().slice(0,settings.candidateCount)[Number(e.key)-1];if(word){e.preventDefault();commit(word);render();}}
  else if(state.comp && e.key==='ArrowDown'){e.preventDefault();state.page='candidates';render();}
  else if(state.comp && e.key==='Escape'){e.preventDefault();commit('');render();}
  else if(['ArrowLeft','ArrowRight','Home','End'].includes(e.key)){state.comp=null;if(state.page==='candidates')state.page='letters';render();}
});
document.addEventListener('pointerdown',e=>{if(e.target!==editor)return;if(localInput&&state.voice)preserveVoice(state.voice);state.comp=null;englishPrefix='';context='';associatedWords=[];render();});
$('host-mic').innerHTML=icon('mic'); $('send').innerHTML=icon('voice');
$('host-plus').onclick=()=>toast('这是输入框周边的参考界面，可直接试用下方键盘。');
$('host-mic').onclick=()=>toast('这是聊天应用的语音按钮。输入法语音请长按下方空格。');
$('send').onclick=()=>{ if(!editor.value.trim()) { toast('先输入一段试用文字。'); return; } if(state.voice) cancelVoice(); const message=document.createElement('div');message.className='message';message.textContent=editor.value;$('sent-messages').append(message);state.comp=null;applyText({text:'',cursor:0});render(); };
$('reopen').onclick=()=>{state.hidden=false;render();};
$('clear-input').onclick=()=>{ if(state.voice) cancelVoice();state.comp=null;applyText({text:'',cursor:0});$('sent-messages').replaceChildren();render(); };
document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>showTab(b.dataset.tab));
const quick=[['letters','全键盘'],['numbers','数字'],['symbols','符号'],['clipboard','剪贴板'],['layouts','键盘选择'],['emoji','表情']];
$('quick-pages').innerHTML=quick.map(([id,label])=>button(label,'quick',id)).join('');
$('quick-pages').onclick=e=>{const b=e.target.closest('button');if(b){if(b.dataset.value==='letters'){state.layout='26';state.language='zh';inkPad.clear();}showPage(b.dataset.value);}};
$('compare-toggle').onclick=()=>{ const show=$('comparison').hidden; $('comparison').hidden=!show;$('compare-toggle').setAttribute('aria-pressed',show); };
function syncControls() {
  $('feature-toggles').innerHTML=toolbarItems.map(([id,label])=>`<label><input type="checkbox" data-feature="${id}" ${settings.toolbar.includes(id)?'checked':''}>${label}</label>`).join('')+[['footerSwitcher','底部输入法菜单'],['footerClipboard','底部剪贴板']].map(([id,label])=>`<label><input type="checkbox" data-setting="${id}" ${settings[id]?'checked':''}>${label}</label>`).join('');
  $('clipboard-toggles').innerHTML=[['clipboardHistory','剪贴板历史记录'],['clipboardCandidate','候选栏展示复制内容']].map(([id,label])=>`<label><input type="checkbox" data-setting="${id}" ${settings[id]?'checked':''}>${label}</label>`).join('');
  for(const id of ['comma','period']) $(id).value=settings[id];
  $('voice-text').value=settings.voiceText;
  if(nativeIme){const menu=$('feature-toggles').querySelector('[data-feature="menu"]');menu.disabled=true;menu.title='用于返回键盘设置';}
  $('remember-input').checked=settings.rememberInput;
  updateLearningStatus();
}
function updateLearningStatus(){$('learning-status').textContent=hasNativePinyin?'常选词由本地拼音引擎记忆，已有旧记录保留。':`已记忆 ${learning.length} 条选词偏好。常选两次后提前，只保存在当前浏览器。`;}
$('remember-input').onchange=e=>{settings.rememberInput=e.target.checked;persist();render();};
let learningClearUntil=0;
$('clear-learning').onclick=()=>{
  if(hasNativePinyin){
    if(Date.now()>learningClearUntil){learningClearUntil=Date.now()+5000;toast('再次点击确认清空选词记忆。');return;}
    learningClearUntil=0;
    if(!JSON.parse(nativeIme.pinyinClearLearning()).cleared){toast('选词记忆未清空，请重试。');return;}
  }else if(!nativeIme&&!confirm('清空当前浏览器的选词记忆？此操作不能撤销。'))return;
  learning=[];localStorage.removeItem('voxfix-learning-v1');updateLearningStatus();render();toast('已清空选词记忆。');
};
$('feature-toggles').onchange=e=>applyToggleChange(e.target);
$('clipboard-toggles').onchange=e=>applyToggleChange(e.target);
function applyToggleChange(input){
  if(nativeIme&&input.dataset.feature==='menu'&&!input.checked){input.checked=true;return;}
  if(input.dataset.feature){
    // 保留自定义工具栏条目，不要因为切换内置入口把它们清掉。
    const key=input.dataset.feature;
    settings.toolbar=settings.toolbar.filter(item=>String(item).startsWith('custom:')||item!==key||input.checked);
    if(input.checked&&!settings.toolbar.includes(key))settings.toolbar.push(key);
  }else if(input.dataset.setting)settings[input.dataset.setting]=input.checked;
  persist();render();feedback('功能设置已保存，键盘尺寸保持固定。');
}
for(const id of ['comma','period','voice-text']) $(id).oninput=e=>{const key=id==='voice-text'?'voiceText':id;if(!e.target.value)return;settings[key]=e.target.value;persist();render();};
$('export').onclick=()=>{ const file=new Blob([JSON.stringify({schema:1,...settings},null,2)],{type:'application/json'});const url=URL.createObjectURL(file);const a=document.createElement('a');a.href=url;a.download='voxfix-settings.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);feedback('已导出功能设置。'); };
$('import').onclick=()=>$('config-file').click();
$('config-file').onchange=async e=>{ const file=e.target.files[0];if(!file)return;try{if(file.size>50000)throw Error('配置文件过大');const value=JSON.parse(await file.text());if(value.schema!==1)throw Error('不支持的配置版本');const next=settingsFrom(value);if(state.voice)cancelVoice();settings=next;syncControls();persist();render();}catch(error){toast(error.message||'无法读取布局配置');}finally{e.target.value='';} };
$('reset').onclick=()=>{ if(state.voice)cancelVoice();settings=structuredClone(defaults);state.night=false;state.hidden=false;syncControls();persist();render();feedback('已恢复默认功能设置，键盘尺寸保持固定，试用文字保留。'); };
$('snippet-cancel').onclick=()=>{if(nativeIme){closeLocalEditor();state.page='clipboard';render();}else snippetDialog.close();};
$('snippet-form').onsubmit=e=>{
  e.preventDefault();
  try{
    if(localInput&&state.comp){commit(currentCandidates()[0]||state.comp.raw);if(state.comp)commit(state.comp.raw);}
    const value=$('snippet-content').value;
    if(snippetEdit.tab==='clipboard'){
      const list=clips,index=snippetEdit.index<0?-1:list.indexOf(snippetEdit.original);
      if(snippetEdit.index>=0&&index<0)throw Error('原内容已更改，请重新打开后编辑');
      const next=addSnippet(list,value,index),before={clips,phrases:phraseGroups,undo:snippetUndo};
      clips=next;snippetUndo=null;
      if(!saveSnippets()){clips=before.clips;phraseGroups=before.phrases;snippetUndo=before.undo;updatePhraseOptions();throw Error('未能保存，编辑内容已保留，请检查可用空间后重试');}
    }else{
      // 常用语：官方回到上限是提示失败，不会静默丢旧条目。
      const group=phraseGroups[Math.min(snippetEdit.group??state.clipGroup,phraseGroups.length-1)];
      const beforeItems=[...group.items],beforeUndo=snippetUndo;
      if(snippetEdit.index<0){if(group.items.includes(value))throw Error('这条内容已经在常用语里了');addPhraseItem(group,value);}
      else{
        const index=group.items.indexOf(snippetEdit.original);
        if(index<0)throw Error('原内容已更改，请重新打开后编辑');
        if(group.items.some((item,i)=>i!==index&&item===value))throw Error('这条内容已经存在');
        if(value.length>phraseLengthLimit)throw Error('单条常用语最多 '+phraseLengthLimit+' 字');
        group.items=group.items.map((item,i)=>i===index?value:item);
      }
      snippetUndo=null;
      if(!saveSnippets()){group.items=beforeItems;snippetUndo=beforeUndo;updatePhraseOptions();throw Error('未能保存，编辑内容已保留，请检查可用空间后重试');}
    }
    if(nativeIme){closeLocalEditor();state.page='clipboard';}else snippetDialog.close();render();
  }catch(error){$('snippet-error').textContent=error.message;}
};
window.addEventListener('storage',e=>{if(e.key==='voxfix-learning-v1'){try{learning=e.newValue?validateLearning(JSON.parse(e.newValue)):[];updateLearningStatus();if(state.comp)render();}catch{}return;}if(e.key!=='voxfix-snippets-v1'||!e.newValue)return;try{const saved=JSON.parse(e.newValue);clips=validateSnippets(saved.clips);phraseGroups=migratePhraseGroups(saved.phrases);snippetUndo=null;state.clipGroup=Math.min(state.clipGroup,phraseGroups.length-1);updatePhraseOptions();if(state.page==='clipboard')render();}catch{/* Invalid cross-tab data is ignored. */}});
inkPad=new InkPad($('ink-canvas'),words=>{inkWords=words;render();},message=>toast(message));
$('ink-clear').onclick=()=>{inkPad.clear();render();};
async function updateHandStatus(){if(nativeIme){handReady=nativeIme.handReady();return;}try{const response=await fetch('/api/handwriting/status');const data=await response.json();handReady=Boolean(data.ready);}catch{handReady=false;}if(location.protocol!=='file:')setTimeout(updateHandStatus,handReady?15000:5000);}
updateHandStatus();
syncControls();render();
loadPinyin(hasNativePinyin).then(()=>{updatePhraseOptions();if(state.comp)render();$('pinyin-status').textContent='全拼、简拼和混输已就绪：wzdl 找“我知道了”、mtj 找“明天见”。';}).catch(()=>$('pinyin-status').textContent='拼音词库未能加载，请刷新页面或重新生成词库。');
loadEnglish().then(async()=>{if(hasNativePinyin)await loadPersonalEnglish();if(englishPrefix||state.comp)render();}).catch(()=>{});
async function updateVoiceStatus(){
  if(nativeIme){speechReady=true;return;}
  const status=await voiceStatus();speechReady=Boolean(status.ready);
  $('speech-status').textContent=speechReady?'已就绪 · 电脑本地离线':status.error||'正在加载离线模型…';
  $('enable-mic').disabled=!speechReady;
  $('long-voice').disabled=!speechReady||Boolean(state.voice);
  if(location.protocol!=='file:')setTimeout(updateVoiceStatus,speechReady?15000:5000);
}
$('enable-mic').onclick=async()=>{try{await enableMicrophone();}catch(error){toast(error.name==='NotAllowedError'?'请在浏览器中允许这个本机页面使用麦克风。':error.message);}};
$('demo-voice').onchange=()=>{if(state.voice)cancelVoice();$('demo-fields').hidden=!$('demo-voice').checked;feedback($('demo-voice').checked?'已切换为交互演示，不录音。':'已切换为真实本地离线语音。');};
updateVoiceStatus();
function updatePhraseOptions(){setPhraseOptions(allPhrases(),settings.phrasePacks);for(const input of $('phrase-options').querySelectorAll('input'))input.checked=settings.phrasePacks.includes(input.value);const query=$('phrase-search').value.trim();$('phrase-results').innerHTML=query?phraseSearch(query).slice(0,20).map(text=>button(text,'phrase-result',text)).join(''):'';}
$('phrase-options').innerHTML=[['quotes','古典名句'],['technology','科技词语'],['running','跑步词语']].map(([name,label])=>`<label><input type="checkbox" value="${name}" ${settings.phrasePacks.includes(name)?'checked':''}>${label}</label>`).join('');
$('phrase-options').onchange=()=>{settings.phrasePacks=[...$('phrase-options').querySelectorAll('input:checked')].map(input=>input.value);updatePhraseOptions();persist();if(state.comp)render();};
$('phrase-search').oninput=updatePhraseOptions;
$('phrase-results').onclick=event=>{const target=event.target.closest('button');if(target&&!state.voice){insert(target.dataset.value);render();}};
$('long-voice').onclick=()=>startVoice(true);
$('finish-voice').onclick=()=>finishVoice(false);
$('cancel-voice').onclick=()=>finishVoice(true);
$('pause-voice').onclick=async()=>{const session=state.voice;if(!session?.controller)return;$('pause-voice').disabled=true;try{const resume=session.phase==='paused';await session.controller[resume?'resume':'pause']();if(state.voice===session){session.phase=resume?'listening':'paused';render();}}catch(error){if(state.voice===session){preserveVoice(session);toast(error.message);}}};
async function updatePolishStatus(){
  if(nativeIme)return;
  try{const response=await fetch('/api/polish/status');if(!response.ok)throw Error();const status=await response.json();polishReady=Boolean(status.installed);$('polish-status').textContent=polishReady?'Qwen3.5-2B Q4_K_M · 本地离线 · 按需加载':'离线润色模型尚未安装。';}
  catch{polishReady=false;$('polish-status').textContent='离线润色需打开本机服务版试用页。';}
  $('polish-text').disabled=!polishReady||polishBusy||Boolean(state.voice);
  if(location.protocol!=='file:')setTimeout(updatePolishStatus,15000);
}
$('polish-text').onclick=async()=>{
  if(state.voice||polishBusy||!editor.value.trim())return;
  if(state.comp){toast('请先选词或结束拼音输入。');return;}
  const draft={original:editor.value,text:''};polishDraft=draft;polishBusy=true;
  $('polish-original').value=draft.original;$('polish-preview').value='';$('polish-apply').disabled=true;$('polish-message').textContent='正在本机加载并整理，原文保留。';$('polish-dialog').showModal();render();
  try{
    const response=await fetch('/api/polish',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({text:draft.original}),signal:AbortSignal.timeout(300000)});
    const result=await response.json();if(!response.ok)throw Error(result.error||'离线润色失败');
    if(polishDraft!==draft)return;draft.text=result.text;$('polish-preview').value=result.text;$('polish-apply').disabled=false;
    $('polish-message').textContent=`整理完成，用时 ${result.seconds} 秒。${result.rejectedSegments?'部分段落触发保护，保留原文。':''}请核对人名、数字和原意。`;
  }catch(error){if(polishDraft===draft)$('polish-message').textContent=(error.message||'离线润色失败')+'；原文未修改。';}
  finally{polishBusy=false;render();}
};
$('polish-close').onclick=()=>{$('polish-dialog').close();};
$('polish-apply').onclick=()=>{
  const draft=polishDraft;if(!draft?.text)return;
  if(editor.value!==draft.original||state.voice||state.comp){$('polish-message').textContent='输入文字已改变，请重新整理，避免覆盖新内容。';return;}
  polishUndo={original:draft.original,text:draft.text};applyText({text:draft.text,cursor:draft.text.length});$('undo-polish').disabled=false;$('polish-dialog').close();render();
};
$('undo-polish').onclick=()=>{
  if(!polishUndo)return;
  if(editor.value!==polishUndo.text||state.voice||state.comp){toast('文字已继续编辑，不能覆盖新内容；原文仍可在润色预览中查看。');return;}
  applyText({text:polishUndo.original,cursor:polishUndo.original.length});polishUndo=null;$('undo-polish').disabled=true;render();
};
updatePolishStatus();
if(nativeIme){
  globalThis.voxfixPinyinReady=(failed=false)=>{if(failed)loadFullPinyin().catch(()=>feedback('拼音词库未能加载。'));else if(state.comp)render();};
  globalThis.voxfixCursor=(position,total)=>{nativeCursor={position,total};const zone=keyboard.querySelector('.cursor-zone');if(zone){zone.setAttribute('aria-valuemax',total);zone.setAttribute('aria-valuenow',position);}};
  globalThis.voxfixSelection=(start,end,count=-1)=>{if(localInput)return;selectedCount=start===end?0:count;syncSelectionFeedback();if((start<0||end<0)&&(state.selectionActions||selectionLocked)){clearSelectionLock();render();}};
  // 宿主自己长按选中的文字：那里本来就有固定起点，按「已锁定」处理。
  globalThis.voxfixSelectionOrigin=()=>{if(localInput||state.voice||gesture)return;selectionLocked=true;state.selectionActions=true;render();};
  globalThis.voxfixSelectionUnavailable=()=>toast('当前输入框暂不提供段落文本，请使用滑轨框选。');
  globalThis.voxfixSelectionBlocked=()=>{if(gesture?.selecting)haltJoystick(gesture);};
  globalThis.voxfixReset=()=>{if(gesture)haltJoystick(gesture);releaseSelectionLock();state.selectionActions=false;state.cursorDrag=null;cursorFeedback.visible=false;closeLocalEditor(true);stopVoiceLoop();englishPrefix='';context='';associatedWords=[];clearTimeout(gesture?.timer);clearTimeout(gesture?.liftTimer);clearTimeout(gesture?.repeatTimer);gesture=null;itemHold=null;state.voice=null;state.comp=null;state.popup=null;state.locked=false;state.hidden=false;state.page='letters';state.clipManage=false;state.clipSelected=[];state.clipConfirm=null;state.clipCandidate='';state.toolbarSort=false;state.clipGroupMenu=null;inkPad?.clear();applyText({text:'',cursor:0});render();};
  globalThis.voxfixClearComposition=(keepEnglish=false)=>{if(localInput||keepEnglish)return;const changed=Boolean(englishPrefix||state.comp||associatedWords.length||state.page==='candidates');englishPrefix='';context='';associatedWords=[];state.comp=null;if(state.page==='candidates')state.page='letters';applyText({text:'',cursor:0});if(changed)render();};
  globalThis.voxfixInputType=type=>{
    // 换了输入框，原来那份起点已经不指向同一个地方，锁定安全清理。
    clearSelectionLock();
    const kind=type&15,variation=type&4080;
    privateField=kind===1&&[128,144,224].includes(variation)||kind===2&&variation===16;
    if(kind===2||kind===3)state.page='numbers';
    else if(kind===1&&[16,32,128,144,224].includes(variation)){fieldPreference??={language:state.language,layout:state.layout};state.language='en';state.layout='26';}
    else if(fieldPreference!==null){Object.assign(state,fieldPreference);fieldPreference=null;}
    render();
  };
  let inputType=null;
  globalThis.voxfixRedraw=()=>render();
  globalThis.voxfixStartInput=(type,restarting,options=0)=>{const previous=enterLabel;enterLabel=options&0x40000000?'换行':({2:'前往',3:'搜索',4:'发送',5:'下一项',6:'完成',7:'上一项'}[options&255]||'换行');if(!restarting){globalThis.voxfixReset();globalThis.voxfixInputType(type);}else if(inputType!==null&&inputType!==type){globalThis.voxfixClearComposition();englishPrefix='';state.page='letters';globalThis.voxfixInputType(type);}else if(previous!==enterLabel)render();inputType=type;};
  globalThis.voxfixVoiceDone=message=>{exitVoice();if(message)toast(message);};
  globalThis.voxfixVoiceText=text=>{const session=state.voice;if(!session?.local||session.local!==editor)return;session.text=text;if(session.phase!=='cancel')applyText(voiceResult(session.snapshot,text));};
  globalThis.voxfixVoiceLevel=level=>setVoiceLevel(level/12);
  globalThis.voxfixClipboard=(text,signature=text,explicit=false)=>{
    if(privateField||!explicit&&signature===nativeClipSignature)return;
    const previous=clips,previousSignature=nativeClipSignature;
    nativeClipSignature=signature;
    if(text.trim()){
      if((settings.clipboardHistory||explicit)&&!clips.includes(text))clips=addSnippet(clips,text);
      if(settings.clipboardCandidate)state.clipCandidate=text;
    }
    if((settings.clipboardHistory||explicit)&&!saveSnippets()){clips=previous;nativeClipSignature=previousSignature;}
    if(state.page==='clipboard'||state.clipCandidate)render();
  };
  nativeIme.ready();
}
$('voice-hotwords').value=hotwords.join('\n');
$('voice-hotwords').onchange=()=>{const words=[...new Set($('voice-hotwords').value.split('\n').map(word=>word.trim()).filter(Boolean))];if(words.length>100||words.some(word=>!/^[\u3400-\u9fff]{1,30}$/.test(word))){toast('热词最多 100 条，每条 1–30 个汉字；请修改后再保存。');return;}hotwords=words;try{localStorage.setItem('voxfix-hotwords-v1',JSON.stringify(words));feedback('热词已保存，下次录音生效。');}catch{toast('热词仅在当前页面保留。');}};
