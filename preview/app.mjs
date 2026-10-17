import {createPersonalLexicon,lexiconMaxBytes} from './lexicon.mjs';
import { repeatDelay, joystickHoldRate, defaults, keyboardGeometry, cursorFeedbackPosition, joystick, joystickLevel, joystickRadii, joystickRho, toolbarItems, settingsFrom, replaceRange, erase, moveCursor, voiceResult, validateSnippets, addSnippet, validateLearning, learnWord, learnSelection, rankLearned, snippetLimit, phraseItemLimit, phraseLengthLimit, phraseGroupLimit, flattenPhrases, migratePhraseGroups } from './core.mjs';
import { LocalVoice, voiceStatus, enableMicrophone } from './voice.mjs';
import { createVoiceOrb } from './voice-orb.mjs';
import { loadPinyin, pinyinCandidates, nineCandidates, setPhraseOptions, phraseCandidates, phraseSearch, associationCandidates, pinyinConsumed, hasPinyinMatch, isSyllable, selectionCode, learnedCandidates, learnedAssociations, clipboardMatches } from './pinyin.mjs';
import { loadEnglish,loadPersonalEnglish,englishCandidates,hasEnglishWord } from './english.mjs';
import { loadClipboardSplitter } from './split.mjs';

const $ = id => document.getElementById(id);
const hostEditor=$('editor'), keyboard=$('keyboard');
let editor=hostEditor,localInput=null,consumedClick=null,enterLabel='换行',privateField=false,noPersonalizedLearning=false,englishPrefix='',fieldPreference=null;
let clipSearch=null;
const clipSearchInput=document.createElement('input');
clipSearchInput.className='clip-search-input';clipSearchInput.type='text';clipSearchInput.inputMode='none';clipSearchInput.autocomplete='off';clipSearchInput.spellcheck=false;clipSearchInput.maxLength=100;
clipSearchInput.setAttribute('aria-label','搜索剪贴板');clipSearchInput.placeholder='搜索剪贴板';
let nativeCursor={position:0,total:0};
const nativeIme = globalThis.VoxFixIme;
const hasNativePinyin=typeof nativeIme?.pinyinCandidates==='function';
const nativePinyinActive=()=>hasNativePinyin&&(!localInput||clipSearch)&&state.language==='zh'&&state.layout==='26'&&nativeIme.pinyinReady();
let fullPinyinLoaded=!hasNativePinyin,fullPinyinLoading=null;
function loadFullPinyin(){return fullPinyinLoading??=loadPinyin().then(()=>{fullPinyinLoaded=true;updatePhraseOptions();render();});}
if(nativeIme){document.documentElement.classList.add('native-ime');document.body.append($('phone'));}
const localPanel=document.createElement('div');
if(localPanel){localPanel.id='ime-local-editor';localPanel.hidden=true;$('phone').append(localPanel);}
const snippetForm=$('snippet-form'),snippetDialog=$('snippet-dialog');
let snippetDraft=null;
let clipboardSplit=null,splitRequest=0;
function endClipboardSplit(restoreEditor=false){
  if(gesture?.action==='split-drag')endGesture({pointerId:gesture.id},true);
  splitRequest++;
  const session=clipboardSplit;
  if(session&&nativeIme&&!session.fromEditor)try{nativeIme.splitFinish(session.nativeId);}catch{}
  clipboardSplit=null;
  if(state.page==='clipboard-split')state.page=session?.fromEditor?session.returnPage:'clipboard';
  if(session?.fromEditor){
    if(session.last===null)session.target.setSelectionRange(...session.selection);
    if(restoreEditor&&session.dialogOpen&&!snippetDialog.open)snippetDialog.showModal();
  }
}
async function openClipboardSplit(text,fromEditor=false){
  if(privateField||!fromEditor&&localInput||state.voice||!text.trim())return;
  const target=fromEditor?$('snippet-content'):editor;
  const request=++splitRequest;clipMenu=null;render();
  try{
    const split=await loadClipboardSplitter();
    if(request!==splitRequest)return;
    if(fromEditor){if(target.value!==text||localInput!==target&&!snippetDialog.open)return;}
    else if(state.page!=='clipboard'||state.clipTab!=='clipboard'||!clips.includes(text))return;
    if(!fromEditor&&state.comp){commit(currentCandidates()[0]||state.comp.raw);if(state.comp)commit(state.comp.raw);}
    let nativeId;
    if(nativeIme&&!fromEditor){nativeId=JSON.parse(nativeIme.splitBegin()).session;if(!Number.isInteger(nativeId))throw Error('当前输入框暂不支持拆词上屏');}
    clearSelectionLock();state.selectionActions=false;state.comp=null;englishPrefix='';context='';learningChain=[];associatedWords=[];
    clipboardSplit={words:split(text),selected:[],nativeId,fromEditor,target,returnPage:state.page,dialogOpen:snippetDialog.open,selection:[target.selectionStart,target.selectionEnd,target.selectionDirection],start:fromEditor?0:target.selectionStart,original:fromEditor?text:target.value.slice(target.selectionStart,target.selectionEnd),last:null};
    if(fromEditor){target.setSelectionRange(0,text.length);if(snippetDialog.open)snippetDialog.close();}
    scrollPositions.set('clipboard-split',0);state.page='clipboard-split';render();
  }catch(error){toast(error.message||'拆词暂不可用，请重试');}
}
function chooseClipboardWords(selected){
  const session=clipboardSplit;if(!session)return;
  const text=selected.map(i=>session.words[i]).join('');
  try{
    if(nativeIme&&!session.fromEditor){if(!JSON.parse(nativeIme.splitCompose(session.nativeId,text)).updated)throw Error('输入位置已变化，本次拆词已结束');}
    else{
      const target=session.target;
      const previous=session.last??session.original,end=session.start+previous.length;
      const expected=session.last===null?[session.start,end]:[end,end];
      if(target.value.slice(session.start,end)!==previous||target.selectionStart!==expected[0]||target.selectionEnd!==expected[1])throw Error('输入位置已变化，本次拆词已结束');
      const replacement=text||session.original;
      const result=replaceRange(target.value,session.start,end,replacement);
      if(target===editor)applyText(result);else{target.value=result.text;target.setSelectionRange(result.cursor,result.cursor);}
      if(!text)target.setSelectionRange(session.start,session.start+session.original.length);
    }
    session.selected=selected;session.last=text||null;render();
  }catch(error){endClipboardSplit(true);render();toast(error.message);}
}
function moveSplitDrag(g,time=performance.now(),repeat=true){
  if(gesture!==g)return;
  if(clipboardSplit!==g.session){endGesture({pointerId:g.id},true);return;}
  const grid=keyboard.querySelector('.clip-split-grid'),bounds=grid.getBoundingClientRect();
  const elapsed=Math.min(32,time-(g.time??time));g.time=time;
  const edge=g.lastY<bounds.top+24?-1:g.lastY>bounds.bottom-24?1:0;
  grid.scrollTop+=edge*elapsed*.3;
  const chip=document.elementFromPoint(Math.max(bounds.left+1,Math.min(bounds.right-1,g.lastX)),Math.max(bounds.top+18,Math.min(bounds.bottom-18,g.lastY)))?.closest('.clip-split-chip');
  if(chip){
    const end=Number(chip.dataset.value);
    if(end!==g.end){
      g.end=end;
      const lo=Math.min(g.start,end),hi=Math.max(g.start,end),range=Array.from({length:hi-lo+1},(_,i)=>lo+i),base=new Set(g.base);
      chooseClipboardWords(g.removing?g.base.filter(i=>i<lo||i>hi):[...g.base,...range.filter(i=>!base.has(i))]);
    }
  }
  if(repeat&&gesture===g)g.raf=requestAnimationFrame(time=>moveSplitDrag(g,time));
}
async function copyClipboardSplit(){
  const session=clipboardSplit;if(!session)return;
  const text=(session.selected.length?session.selected.map(i=>session.words[i]):session.words).join('');
  if(!text.trim()){toast('没有可复制的文字');return;}
  try{
    if(nativeIme)nativeIme.copy(text);
    else{if(!navigator.clipboard?.writeText)throw Error('系统复制不可用');await navigator.clipboard.writeText(text);}
  }catch{toast('复制失败，请重试或检查剪贴板权限');return;}
  if(setSnippetList(addSnippet(clips,text),'clipboard')){snippetUndo=null;toast('已复制');}
  render();
}
function clipboardSplitPanel(){
  const session=clipboardSplit,all=session.words.length>0&&session.selected.length===session.words.length;
  const selected=new Set(session.selected);
  const backLabel=session.fromEditor?'返回编辑框，保留拆词草稿':'返回剪贴板，保留已选文字';
  return `<div class="clip-split-title"><span>拆词</span>${button(backLabel,'split-return','','clip-split-return',icon('split-back'))}</div><section class="clip-split-card"><div class="clip-split-grid" role="group" aria-label="${session.fromEditor?'拆词词块，点选修改草稿，保存后提交':'拆词词块，点选上屏，再点撤回'}"><div class="clip-split-flow">${session.words.map((word,i)=>button(word.trim()?'词块：'+word:'空白词块','split-word',String(i),'clip-split-chip',esc(word.trim()?word:word.includes('\n')?'↵':'␣'),`aria-pressed="${selected.has(i)}"`)).join('')}</div></div><div class="clip-split-actions">${button('全选','split-all','','clip-split-all','<span class="clip-split-dot" aria-hidden="true">✓</span>全选',`aria-pressed="${all}"`)}${button(session.selected.length?'复制所选词块':'复制原文','split-copy','','clip-split-return',icon('split-cards'))}</div></section><div class="clip-split-space"></div>${footer()}`;
}
function closeLocalEditor(keepDraft=false){if(!localInput&&!clipSearch)return;if(clipSearch){nativeIme?.searchFocus?.(false);const host=clipSearch.target==='host'?state:clipSearch.inputs.host;Object.assign(state,{language:host.language,layout:host.layout});clipSearch=null;}if(state.voice){if(keepDraft)preserveVoice(state.voice);else cancelVoice();}if(keepDraft&&!quoteEditor&&localInput===$('snippet-content'))snippetDraft={edit:snippetEdit,text:localInput.value};state.comp=null;context='';learningChain=[];associatedWords=[];editor=hostEditor;localInput=null;snippetDialog.append(snippetForm);localPanel.replaceChildren();localPanel.hidden=true;if(quoteEditor){snippetForm.querySelector('button[type=submit]').textContent='保存';snippetForm.querySelector('.note').hidden=false;if(snippetDialog.open)snippetDialog.close();}}
const nativeSettings=nativeIme?document.createElement('dialog'):null;
if(nativeSettings){nativeSettings.className='snippet-dialog settings-dialog';nativeSettings.setAttribute('aria-label','键盘设置');const close=document.createElement('button');close.className='settings-back';close.textContent='‹ 返回键盘';close.onclick=()=>nativeSettings.close();nativeSettings.append(close,$('custom-panel'));document.body.append(nativeSettings);}
let settings = structuredClone(defaults);
try { const stored = localStorage.getItem('voxfix-layout-v1'); if (stored) {const saved=JSON.parse(stored);settings=settingsFrom(saved);localStorage.setItem('voxfix-layout-v1',JSON.stringify({...saved,...keyboardGeometry,rememberInput:true}));} } catch { /* Keep malformed or unwritable saved data untouched. */ }
if(nativeIme&&!settings.toolbar.includes('menu'))settings.toolbar.unshift('menu');
const state = { page: 'letters', returnPage:'letters', symbolReturn:'letters', language: 'zh', shift: 0, symbol: '常用', locked: false, night: false, clipTab: 'clipboard', clipLocked: false, popup: null, comp: null, voice: null, hidden: false, emojiTab: 'Emoji', emojiCategory: '常用', layout: '26', voiceTimer: null, cursorDrag: null, selectionActions: false, clipGroup: 0, clipManage: false, clipSelected: [], clipConfirm: null, clipCandidate: '', toolbarSort: false, clipGroupMenu: null };
let clipTabTap=null;
let latestClipboard='',clipReference='',referenceWanted='',referenceTimer=0,candidateContact=null,candidateRenderPending=false,quoteEditor=null;
let referenceMatches=[],referencePopup=null,referenceCache=null;
function syncClipboardReference(){
  const eligible=settings.clipboardMatch&&!clipSearch&&!privateField&&!noPersonalizedLearning&&!localInput&&!state.voice&&!state.hidden&&!document.hidden&&state.page==='letters'&&state.language==='zh'&&state.layout==='26'&&state.comp;
  if(!eligible){referencePopup=null;referenceMatches=[];clipReference='';referenceWanted='';clearTimeout(referenceTimer);paintClipboardReference();return;}
  if(referencePopup&&referencePopup.query!==state.comp.raw)referencePopup=null;
  if(candidateContact)return;
  let matches=[];
  if(eligible){
    if(referencePopup)matches=referencePopup.matches;
    else{
      if(!referenceCache||referenceCache.query!==state.comp.raw||referenceCache.clips!==clips||referenceCache.latest!==latestClipboard||referenceCache.full!==fullPinyinLoaded)referenceCache={query:state.comp.raw,clips,latest:latestClipboard,full:fullPinyinLoaded,matches:clipboardMatches(state.comp.raw,clips.includes(latestClipboard)?clips:[latestClipboard,...clips])};
      matches=referenceCache.matches;
    }
  }
  const wanted=JSON.stringify(matches.map(({text,start,end,weak})=>[text,start,end,weak]));
  if(wanted!==referenceWanted){clearTimeout(referenceTimer);referenceWanted=wanted;
    if(!matches.length){referenceMatches=[];clipReference='';paintClipboardReference();}
    else if(clipReference){referenceMatches=matches;clipReference=matches[0].text;}
    else referenceTimer=setTimeout(()=>{if(candidateContact){referenceWanted='';return;}referenceMatches=matches;clipReference=matches[0].text;paintClipboardReference();},150);
  }
  paintClipboardReference();
}
function referenceMarkup(hit,compact=false){
  const start=compact?hit.start:Math.max(0,hit.start-6),end=Math.min(hit.text.length,Math.max(hit.end,start+64));
  return (!compact&&start?'…':'')+esc(hit.text.slice(start,hit.start))+`<mark>${esc(hit.text.slice(hit.start,hit.end))}</mark>`+esc(hit.text.slice(hit.end,end))+(!compact&&end<hit.text.length?'…':'');
}
function paintReferenceCount(count,expanded){
  count.innerHTML=`<span>${referenceMatches.length}</span><span class="clipboard-count-arrow" aria-hidden="true">${expanded?'▴':'▾'}</span>`;
  const entry=count.closest('.clipboard-reference');entry.setAttribute('aria-expanded',String(expanded));entry.setAttribute('aria-label',referenceMatches.length+'条剪贴板匹配。点按'+(expanded?'收起':'展开')+'匹配内容');
}
function closeReferencePopup(){
  referencePopup=null;
  const surface=keyboard.querySelector('.clipboard-surface');
  if(surface){surface.classList.add('closing');setTimeout(()=>surface.remove(),180);}
  keyboard.querySelector('.clipboard-slot')?.classList.remove('reference-open');
  const count=keyboard.querySelector('.clipboard-count');if(count)paintReferenceCount(count,false);
}
function paintReferencePopup(){
  const slot=keyboard.querySelector('.clipboard-slot.has-reference');
  if(!referencePopup||!slot){const surface=keyboard.querySelector('.clipboard-surface:not(.closing)');if(slot)surface?.remove();else if(surface)setTimeout(()=>{if(!surface.closest('.has-reference'))surface.remove();},160);return;}
  keyboard.querySelector('.clipboard-surface.closing')?.remove();
  let list=keyboard.querySelector('.clipboard-results');const created=!list;
  if(!list){
    const surface=document.createElement('div');surface.className='clipboard-surface';
    const tab=document.createElement('div');tab.className='clipboard-surface-tab';tab.setAttribute('aria-hidden','true');surface.append(tab);
    list=document.createElement('div');list.className='clipboard-results';list.setAttribute('role','region');list.setAttribute('aria-label',referencePopup.matches.length+'条剪贴板匹配，上下滑动，点按引用，长按编辑');
    list.innerHTML=referencePopup.matches.map((hit,i)=>button('第'+(i+1)+'条，'+hit.text+'。点按引用，长按编辑','clipboard-result',hit.text,'clipboard-result',`<span class="clipboard-result-index" aria-hidden="true">${i+1}</span><span class="clipboard-result-text">${referenceMarkup(hit)}</span>`)).join('');
    const session=referencePopup;surface.append(list);slot.append(surface);list.addEventListener('scroll',()=>{if(referencePopup===session&&list.isConnected)session.scroll=list.scrollTop;});
    if(!session.shown){surface.classList.add('opening');requestAnimationFrame(()=>{if(surface.isConnected)surface.classList.remove('opening');});session.shown=true;}
  }
  const anchor=slot.getBoundingClientRect(),bar=slot.closest('.candidate-bar').getBoundingClientRect();
  const row=slot.parentElement.querySelector('.candidate-visible').getBoundingClientRect(),keys=keyboard.querySelector('.key-rows'),last=keys?.lastElementChild?.getBoundingClientRect();
  list.style.width=Math.min(210,anchor.right-row.left)+'px';list.style.right='0';list.style.top=(bar.bottom-anchor.top-1)+'px';
  const tab=list.parentElement.firstElementChild;Object.assign(tab.style,{left:'0',top:'4px',width:anchor.width+'px',height:(bar.bottom-anchor.top-3)+'px'});
  list.style.maxHeight=Math.max(96,Math.min(144,(last?.top??bar.bottom+148)-bar.bottom-4))+'px';
  if(created)list.scrollTop=referencePopup.scroll;
}
function paintClipboardReference(){
  const slot=keyboard.querySelector('.clipboard-slot');if(!slot)return;
  const active=Boolean(clipReference),preview=slot.querySelector('.clipboard-reference');
  if(active){preview.dataset.value=clipReference;preview.querySelector('.clipboard-preview').textContent=referenceMatches[0]?.text.slice(referenceMatches[0].start,referenceMatches[0].start+2)||'';}
  preview.disabled=!active;preview.setAttribute('aria-hidden',String(!active));slot.inert=!active;slot.setAttribute('aria-hidden',String(!active));slot.classList.toggle('has-reference',active);
  slot.classList.toggle('reference-open',active&&Boolean(referencePopup));
  paintReferenceCount(slot.querySelector('.clipboard-count'),Boolean(referencePopup));
  paintReferencePopup();
}
function insertClipboardReference(text){
  if(privateField||!text)return;
  if(nativeIme&&(!localInput||quoteEditor)){
    if(typeof nativeIme.commitAccepted==='function'&&!JSON.parse(nativeIme.commitAccepted(text)).committed){toast('未能引用，请重试');return;}
    if(typeof nativeIme.commitAccepted!=='function')nativeIme.commit(text);
    if(quoteEditor){closeLocalEditor();quoteEditor=null;}
    state.comp=null;applyText({text:'',cursor:0});
  }else{if(quoteEditor){const prior=quoteEditor;closeLocalEditor();quoteEditor=null;state.comp=prior.comp;}const start=state.comp?.start??editor.selectionStart,end=state.comp?start+state.comp.raw.length:editor.selectionEnd;applyText(replaceRange(editor.value,start,end,text));}
  referencePopup=null;referenceMatches=[];state.comp=null;state.clipCandidate='';englishPrefix='';context='';learningChain=[];associatedWords=[];clipReference='';referenceWanted='';clearTimeout(referenceTimer);render();
}
function openClipboardEditor(text){
  if(localInput||quoteEditor||privateField)return;
  splitRequest++;
  quoteEditor={comp:state.comp,context,associatedWords,referencePopup,query:state.comp?.raw||'',hit:referenceMatches.find(hit=>hit.text===text)};referencePopup=null;state.comp=null;context='';learningChain=[];associatedWords=[];
  $('snippet-title').textContent='编辑本次引用';$('snippet-content').value=text;$('snippet-error').textContent='';snippetForm.querySelector('.note').hidden=true;snippetForm.querySelector('button[type=submit]').textContent='保存';
  localInput=$('snippet-content');editor=localInput;
  if(nativeIme)localPanel.append(snippetForm);else snippetDialog.showModal();
  render();localInput.focus({preventScroll:true});const start=quoteEditor.hit?.start||0;localInput.setSelectionRange(start,start);paintReferenceHighlight(true);
}
function paintReferenceHighlight(locate=false){
  const input=$('snippet-content'),field=input.parentElement;
  let layer=field.querySelector('.snippet-highlight');
  if(!quoteEditor||localInput!==input){layer?.remove();return;}
  const hit=clipboardMatches(quoteEditor.query,[input.value],{manual:true})[0];
  if(!hit?.ranges.length){layer?.remove();return;}
  if(!layer){layer=document.createElement('div');layer.className='snippet-highlight';layer.setAttribute('aria-hidden','true');field.insertBefore(layer,input);}
  const style=getComputedStyle(input);
  for(const property of ['font','lineHeight','letterSpacing','padding','borderWidth','borderStyle','boxSizing','wordBreak','overflowWrap','textAlign','textIndent','tabSize'])layer.style[property]=style[property];
  Object.assign(layer.style,{left:input.offsetLeft+'px',top:input.offsetTop+'px',width:(input.clientWidth+parseFloat(style.borderLeftWidth)+parseFloat(style.borderRightWidth))+'px',height:input.offsetHeight+'px',borderColor:'transparent'});
  const ranges=hit.ranges.slice().sort((a,b)=>a[0]-b[0]),merged=[];
  for(const [start,end] of ranges){const last=merged.at(-1);if(last&&start<=last[1])last[1]=Math.max(last[1],end);else merged.push([start,end]);}
  let at=0,html='';for(const [start,end] of merged){html+=esc(input.value.slice(at,start))+`<mark>${esc(input.value.slice(start,end))}</mark>`;at=end;}
  layer.innerHTML=html+esc(input.value.slice(at))+'\u200b';layer.scrollTop=input.scrollTop;layer.scrollLeft=input.scrollLeft;
  if(locate){const mark=layer.querySelector('mark');input.scrollTop+=mark.getBoundingClientRect().top-input.getBoundingClientRect().top-input.clientHeight/3;layer.scrollTop=input.scrollTop;}
}
function finishClipboardEditor(apply){
  if(apply){if(state.comp){commit(currentCandidates()[0]||state.comp.raw);if(state.comp)commit(state.comp.raw);}if(editor.value.trim())insertClipboardReference(editor.value);return;}
  const prior=quoteEditor;closeLocalEditor();quoteEditor=null;state.comp=prior.comp;context=prior.context;associatedWords=prior.associatedWords;referencePopup=prior.referencePopup;render();
}
try{const saved=JSON.parse(localStorage.getItem('voxfix-keyboard-v1'));if(saved){if(['9','26'].includes(saved.layout))state.layout=saved.layout;if(['zh','en'].includes(saved.language))state.language=saved.language;if(typeof saved.night==='boolean')state.night=saved.night;}}catch{}
function saveKeyboard(){const preference=clipSearch?(clipSearch.target==='host'?state:clipSearch.inputs.host):fieldPreference||state;try{localStorage.setItem('voxfix-keyboard-v1',JSON.stringify({layout:preference.layout,language:preference.language,night:state.night}));return true;}catch{return false;}}
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
let clipMenu=null,clipHold=null,clipSuppressUntil=0,learningChain=[];
const personalLexicon=createPersonalLexicon(nativeIme,error=>toast('个人词库保存失败：'+error.message));
personalLexicon.initialized.then(()=>{refreshAssociations();render();}).catch(error=>toast('个人词库加载失败，已有记录保留：'+error.message));
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
function resetDragDelta(g){g.dragX=0;g.dragY=0;g.totalX=0;g.totalY=0;g.localX=0;g.localY=0;g.turnX=0;g.turnY=0;g.direction=null;}
function markSelectionOrigin(){
  selectionLocked=true;state.selectionActions=true;selectedCount=0;
  if(nativeIme&&!localInput){nativeIme.gestureLock?.();return;}
  const head=localSelection?.editor===editor&&localSelection.text===editor.value?localSelection.head:editor.selectionDirection==='backward'?editor.selectionStart:editor.selectionEnd;
  lockedSelection={editor,text:editor.value,anchor:head};localSelection={editor,text:editor.value,anchor:head,head};
  editor.setSelectionRange(head,head);
}
// 二维拖动要按字符索引落点，先量一遍每个字符的坐标。
function primeSelectionGeometry(g,head){
  g.points=selectionPoints();
}
// 锁定起点：把「当前光标」钉成固定起点（有选区时取活动的那一端）。
function lockSelection(g){
  if(selectionLocked)return;
  selectionLocked=true;resetDragDelta(g);g.selecting=true;
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
  resetDragDelta(g);g.selecting=true;
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
  if(g){g.selecting=false;g.ceased=true;haltJoystick(g);}
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
  const g=gesture;if(!g)return;
  const dx=x-(g.dragX||0),dy=y-(g.dragY||0);g.dragX=x;g.dragY=y;
  if(nativeIme&&!localInput){
    if(typeof nativeIme.gestureMove2D==='function')nativeIme.gestureMove2D(dx,dy);
    else {const steps=Math.trunc(x/cursorStep);if(steps!==g.fallbackSteps){g.fallbackSteps=steps;nativeIme.gestureMove?.(steps);}}
    return;
  }
  if(g.selecting&&(!localSelection||localSelection.editor!==editor||localSelection.text!==editor.value))return;
  g.localX=(g.localX||0)+dx;g.localY=(g.localY||0)+dy;
  const columns=Math.trunc(g.localX/cursorStep),lines=Math.trunc(g.localY/selectionRowStep);
  if(!columns&&!lines)return;
  g.localX-=columns*cursorStep;g.localY-=lines*selectionRowStep;
  const currentHead=g.selecting?localSelection.head:g.head;
  const base=g.points.find(p=>p.index===currentHead)||g.points.at(-1),rows=[...new Set(g.points.map(p=>p.y))];
  const targetRow=Math.max(0,Math.min(rows.length-1,rows.indexOf(base.y)+lines));
  const row=g.points.filter(p=>p.y===rows[targetRow]);
  const nearest=row.reduce((a,b)=>Math.abs(a.x-base.x)<=Math.abs(b.x-base.x)?a:b);
  const index=moveCursor(editor.value,lines?nearest.index:currentHead,columns);
  const point=g.points.find(p=>p.index===index)||g.points.at(-1);
  if(!g.selecting){editor.setSelectionRange(point.index,point.index);g.head=point.index;return;}
  localSelection.head=point.index;
  const {anchor,head}=localSelection;editor.setSelectionRange(Math.min(anchor,head),Math.max(anchor,head),head<anchor?'backward':'forward');
  const height=editor.clientHeight;editor.scrollTop=Math.max(0,Math.min(editor.scrollTop,point.y-12));if(point.y>editor.scrollTop+height-12)editor.scrollTop=point.y-height+24;
  selectedCount=characterCount(editor.value.slice(Math.min(anchor,head),Math.max(anchor,head)));syncSelectionFeedback();
}
function syncSelectionFeedback(){
  const count=keyboard.querySelector('.selection-count');if(count)count.textContent=selectedCount<0?'已选文字':`已选 ${selectedCount} 字`;
  for(const button of keyboard.querySelectorAll('.selection-editor [data-action="copy-text"],.selection-editor [data-action="cut-text"]'))button.disabled=selectedCount===0;
}
// 持续移动沿最近确认的八方向输出，上下按行限速。
function jogCursor(g){
  if(g.joy.raf)return;
  const frame=ts=>{
    g.joy.raf=0;
    if(gesture!==g||!g.joy.rate)return;
    g.joy.started??=ts;g.joy.rate=joystickHoldRate(ts-g.joy.started);
    const dt=Math.min(.05,(ts-(g.joy.ts||ts))/1000);g.joy.ts=ts;
    const [sx,sy]=g.direction||[0,0];
    g.totalX=(g.totalX||0)+sx*g.joy.rate*cursorStep*dt;
    g.totalY=(g.totalY||0)+sy*Math.min(4,g.joy.rate/6)*selectionRowStep*dt;
    moveSelection(g.totalX,g.totalY);
    g.joy.raf=requestAnimationFrame(frame);
  };
  g.joy.raf=requestAnimationFrame(frame);
}
const directions=[[-1,-1,'↖','上一行开头'],[0,-1,'↑','上一行'],[1,-1,'↗','上一行末尾'],[-1,0,'←','左移一个字'],[1,0,'→','右移一个字'],[-1,1,'↙','下一行开头'],[0,1,'↓','下一行'],[1,1,'↘','下一行末尾']];
function dragDirection(g,dx,dy){
  if(!dx&&!dy)return g.direction||[0,0];
  const angle=Math.atan2(dy,dx),old=g.direction;
  if(old&&Math.abs(Math.atan2(Math.sin(angle-Math.atan2(old[1],old[0])),Math.cos(angle-Math.atan2(old[1],old[0]))))<Math.PI/6)return old;
  const snapped=Math.round(angle/(Math.PI/4))*Math.PI/4;
  return [Math.round(Math.cos(snapped)),Math.round(Math.sin(snapped))];
}
function dragSelection(g,dx,dy){
  // 主轨只有 footer 那么高，纵向行程短、又夹在工具栏与字母键之间，极易被误判成横向而"跨段"跳行；
  // 上下跨行/跨段只在编辑框的八方向盘里做，主轨一律丢弃纵向分量，只输出左右。
  if(!g.mini)dy=0;
  const pad=padDirection(g,g.lastX,g.lastY);
  let direction=pad?.some(Boolean)?pad:dragDirection(g,dx,dy);
  if(!g.padCenter&&g.direction&&(direction[0]!==g.direction[0]||direction[1]!==g.direction[1])){
    g.turnX=(g.turnX||0)+dx;g.turnY=(g.turnY||0)+dy;
    direction=Math.hypot(g.turnX,g.turnY)<8?g.direction:dragDirection(g,g.turnX,g.turnY);
  }else {g.turnX=0;g.turnY=0;}
  if(g.direction&&(direction[0]!==g.direction[0]||direction[1]!==g.direction[1])){
    // 清旧轴余量，保留推距；真实转向后仍可持续推移，轻微手抖不换向。
    g.localX=0;g.localY=0;g.turnX=0;g.turnY=0;g.joy.started=null;
    if(!direction[0])g.totalX=g.dragX||0;
    if(!direction[1])g.totalY=g.dragY||0;
    if(nativeIme&&!localInput)nativeIme.gestureClearSteps?.();
    else if(g.points){const head=g.selecting?localSelection.head:g.head;primeSelectionGeometry(g,head);g.totalX=0;g.totalY=0;g.dragX=0;g.dragY=0;}
  }
  g.direction=direction;
  if(g.blockedDirection){
    if(direction[0]===g.blockedDirection[0]&&direction[1]===g.blockedDirection[1]){joystickSync(g,g.lastX,g.lastY);return;}
    g.blockedDirection=null;
  }
  g.totalX=(g.totalX||0)+(direction[0]?dx:0);
  g.totalY=(g.totalY||0)+(direction[1]?dy:0);
  moveSelection(g.totalX,g.totalY);joystickSync(g,g.lastX,g.lastY);
}
function padDirection(g,x,y){
  const c=g.padCenter;if(!c)return null;
  return [x<c.left?-1:x>c.right?1:0,y<c.top?-1:y>c.bottom?1:0];
}
function selectionStep(sx,sy){
  learningChain=[];
  const locked=lockedNow();
  if(nativeIme&&!localInput){nativeIme.selectionStep?.(sx,sy,locked);return;}
  const start=editor.selectionStart,end=editor.selectionEnd;
  const head=locked&&localSelection?localSelection.head:editor.selectionDirection==='backward'?start:end;
  let next=head;
  if(!sy)next=moveCursor(editor.value,head,sx);
  else{
    const points=selectionPoints(),base=points.find(p=>p.index===head)||points.at(-1);
    const rows=[...new Set(points.map(p=>p.y))],row=rows.indexOf(base.y),targetRow=row+sy;
    if(targetRow<0||targetRow>=rows.length)return;
    const targets=points.filter(p=>p.y===rows[targetRow]);
    next=sx<0?targets[0].index:sx>0?targets.at(-1).index:targets.reduce((a,b)=>Math.abs(a.x-base.x)<=Math.abs(b.x-base.x)?a:b).index;
  }
  if(locked){localSelection={editor,text:editor.value,anchor:lockedSelection.anchor,head:next};editor.setSelectionRange(Math.min(localSelection.anchor,next),Math.max(localSelection.anchor,next),next<localSelection.anchor?'backward':'forward');}
  else editor.setSelectionRange(next,next);
  selectedCount=characterCount(editor.value.slice(editor.selectionStart,editor.selectionEnd));syncSelectionFeedback();
}
// 推距决定是否续移，最近的八方向决定往哪移；回到按下点停止续移。
function joystickSync(g,x,y){
  const joy=g.joy;
  joy.dx=x-joy.originX;joy.dy=y-joy.originY;
  const pad=padDirection(g,x,y),center=pad?!pad.some(Boolean):Math.hypot(x-g.x,y-g.y)<cursorStep;
  if(center)g.blockedDirection=null;
  joy.level=pad?(center?0:1):joystickLevel(joystickRho(joy.dx,joy.dy,joy),joy.level);
  const rate=center||g.blockedDirection?0:joystick.rates[joy.level];
  if(Boolean(rate)!==Boolean(joy.rate)){joy.rate=rate?joystickHoldRate(0):0;joy.ts=0;joy.started=null;}
  jogCursor(g);
}
// 长按锁定时标定推距；转向只清旧轴余量，保留按下点以持续推移。
function rejoy(g,x,y){
  const kb=keyboard.getBoundingClientRect();
  g.joy={...g.joy,...joystickRadii(Math.min(x-kb.left,kb.right-x),y-kb.top,kb.bottom-y),originX:x,originY:y,dx:0,dy:0,level:0,rate:0,ts:0};
}
// 停掉正在跑的摇杆速率（原生的「选区被阻止」和重置钩子都要用）。
function haltJoystick(g=gesture){
  if(!g?.joy)return;
  g.joy.rate=0;g.joy.level=0;g.joy.started=null;
  if(g.joy.raf){cancelAnimationFrame(g.joy.raf);g.joy.raf=0;}
}
// 表情长按连发、候选词长按、工具栏长按排序共用的按住计时状态。
let itemHold = null, toolbarHold = null, toolbarDrag = null, groupHold = null;
let context='',associatedWords=[],candidateMode='words',candidateGridWords=[],candidateGridShown=0;
function refreshAssociations(){associatedWords=[...new Set([...(settings.rememberInput&&!privateField?learnedAssociations(context,personalLexicon.associations(context)):[]),...associationCandidates(context,240)])].slice(0,240);}
function rememberContext(text){context=(context+text).match(/[\u3400-\u9fff]+$/)?.[0]?.slice(-16)||'';refreshAssociations();}
function recordChoice(raw,word,preedit=''){
  if(clipSearch&&localInput){learningChain=[];return;}
  if(quoteEditor||!settings.rememberInput||privateField||noPersonalizedLearning||state.language!=='zh'||!/^[\u3400-\u9fff]{1,60}$/.test(word)||!raw||raw.length>60){learningChain=[];return;}
  try{
    let changes;
    if(state.layout==='9'){changes=learnWord([],'9:'+raw,word,selectionCode(raw,word,preedit,personalLexicon.lookup(raw)));learningChain=[];}
    else({records:changes,chain:learningChain}=learnSelection([],learningChain,raw.toLowerCase(),word,selectionCode(raw,word,preedit,personalLexicon.lookup(raw)),Date.now()));
    personalLexicon.learn(changes);
  }catch(error){learningChain=[];toast('选词记忆未保存：'+error.message);}
}
const scrollPositions=new Map(),nightShade=document.createElement('div');
nightShade.id='night-shade';nightShade.className='night-shade';nightShade.hidden=true;$('phone').append(nightShade);
let polishReady=false,polishBusy=false,polishDraft=null,polishUndo=null;
let hotwords=[];
try{const saved=JSON.parse(localStorage.getItem('voxfix-hotwords-v1'));if(Array.isArray(saved)&&saved.length<=100&&saved.every(word=>typeof word==='string'&&/^[\u3400-\u9fff]{1,30}$/.test(word)))hotwords=saved;}catch{}
const icons = {
  close:'<path d="m6 6 12 12M18 6 6 18"/>',
  'split-back':'<path d="m9 4-5 5 5 5"/><path d="M4 9h10a6 6 0 0 1 0 12h-3"/>',
  'split-cards':'<rect x="4" y="8" width="12" height="13" rx="2"/><path d="M8 8V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2h-2"/>',
  menu: '<rect x="3" y="3" width="6" height="6" rx="1.5"/><rect x="14" y="3" width="6" height="6" rx="1.5"/><rect x="3" y="14" width="6" height="6" rx="1.5"/><rect x="14" y="14" width="6" height="6" rx="1.5"/>',
  layouts: '<path d="M3 18h18"/><path d="M3 3h3v3H3zM8 3h3v3H8zM13 3h3v3h-3zM18 3h3v3h-3zM3 8h3v3H3zM8 8h3v3H8zM13 8h3v3h-3zM18 8h3v3h-3zM3 13h3v3H3zM8 13h3v3H8zM13 13h3v3h-3zM18 13h3v3h-3z" fill="currentColor" stroke="none"/>',
  night: '<path d="M19 14A8 8 0 0 1 10 3a9 9 0 1 0 9 11Z"/>',
  clipboard: '<circle cx="6" cy="18" r="3"/><circle cx="18" cy="18" r="3"/><path d="m5 3 14 12M19 3 5 15"/>',
  copy: '<rect x="8" y="7" width="12" height="14" rx="2"/><path d="M15 7V3H3v14h5m3-6h6m-6 4h6"/>',
  emoji: '<circle cx="12" cy="12" r="9"/><path d="M8 15q4 4 8 0M8 9h.1M16 9h.1"/>',
  hide: '<path d="m5 9 7 7 7-7"/>', back: '<path d="M20 12H4m5-5-5 5 5 5"/>',
  delete: '<path d="m8 5-6 7 6 7h13V5Z"/><path d="m11 9 6 6m0-6-6 6"/>',
  // 选字编辑面板左侧四个操作钮：虚线选框、两张纸页、剪刀、带纸页的剪贴板。
  // 线宽由面板 CSS 统一给（stroke-width），这里只描述几何。
  'select-text': '<rect x="3.6" y="4.2" width="16.8" height="15.6" rx="2.6" stroke-dasharray="3.4 2.7"/><path d="M8 9.2h8M8 12h8M8 14.8h8"/>',
  'copy-pages': '<rect x="3.8" y="8.4" width="11.8" height="12.8" rx="2"/><path d="M7.9 8.4V5.2a2 2 0 0 1 2-2h7.9a2 2 0 0 1 2 2v10.6a2 2 0 0 1-2 2h-2.1"/>',
  cut: '<circle cx="6.2" cy="6.4" r="2.9"/><circle cx="6.2" cy="17.6" r="2.9"/><path d="M19.6 4.2 8.4 15.3M14.7 14.2l4.9 5.6M8.4 8.7 12 12.3"/>',
  'paste-sheet': '<path d="M9.2 4.4H7.4a2.2 2.2 0 0 0-2.2 2.2v12.2a2.2 2.2 0 0 0 2.2 2.2h9.2a2.2 2.2 0 0 0 2.2-2.2V6.6a2.2 2.2 0 0 0-2.2-2.2h-1.8"/><rect x="8.6" y="2.4" width="6.8" height="3.8" rx="1.4"/><path d="M9.2 11.4h5.6M9.2 15h3.6"/>',
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
function enterKey(cls=''){const label=clipSearch&&localInput?'搜索':localInput?'换行':enterLabel;return func(label,'enter',label==='换行'?'enter':'',cls);}
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
  const add=state.clipTab==='phrases'?button('添加常用语','snippet-add','','ctl',icon('add')):'';
  const more=button(state.clipTab==='phrases'?'清空当前分组的常用语':'更多管理操作','clip-manage-menu','','ctl'+(clipMenu?.index===-1?' clip-more-open':''),icon(state.clipTab==='phrases'?'trash':'more'),`aria-haspopup="menu" aria-expanded="${clipMenu?.index===-1}" aria-controls="clip-management-menu"`);
  return `<div class="panel-title clip-header"><div class="clip-header-leading">${ctl('return', '返回键盘', 'hide')}${add?more:''}</div>${tabs}<div class="clip-header-actions">${button('展开搜索','clip-search-toggle','','ctl clip-search-toggle',icon('search','clip-search-glass')+icon('close','clip-search-close'),'aria-expanded="false"')}${add||more}</div><div class="clip-search-surface"><span class="clip-search-input-slot"></span></div></div>`;
}
function openClipSearch(){
  if(localInput||state.voice||state.clipManage)return;
  const header=keyboard.querySelector('.clip-header');if(!header)return;
  clipSearch={keys:true,header,query:null,target:'search',inputs:{host:{comp:state.comp,prefix:englishPrefix,language:state.language,layout:state.layout},search:{comp:null,prefix:'',language:state.language,layout:'26'}}};
  localInput=clipSearchInput;editor=localInput;clipSearchInput.value='';state.comp=null;englishPrefix='';context='';learningChain=[];associatedWords=[];
  clearSelectionLock();state.selectionActions=false;clipMenu=null;state.clipGroupMenu=null;state.clipConfirm=null;state.layout='26';state.page='letters';
  const list=document.createElement('div');list.className='clip-list clip-search-results';list.setAttribute('aria-label','搜索结果，上下滑动，点按全文上屏');
  const resume=document.createElement('button');resume.type='button';resume.className='clip-search-resume';resume.dataset.action='clip-search-keys';resume.textContent='继续输入';
  localPanel.replaceChildren(header,list,resume);nativeIme?.searchFocus?.(true);render();clipSearchInput.focus({preventScroll:true});
  if(!fullPinyinLoaded)loadFullPinyin().catch(()=>feedback('拼音检索词库未能加载，仍可搜索文字。'));
}
function closeClipSearch(restore=true){
  const session=clipSearch;if(!session)return;
  const host=session.target==='host'?{comp:state.comp,prefix:englishPrefix}:session.inputs.host;
  const header=session.header;closeLocalEditor();state.page='clipboard';state.comp=restore?host.comp:null;englishPrefix=restore?host.prefix:'';
  render();const next=keyboard.querySelector('.clip-header');if(next){next.replaceWith(header);updateClipSearchHeader(header,false);}
}
function setClipSearchTarget(target,selectionChanged=false){
  if(!clipSearch)return;
  if(target===clipSearch.target){
    if(target==='host'&&selectionChanged){state.comp=null;englishPrefix='';context='';learningChain=[];associatedWords=[];if(nativeIme)hostEditor.value='';render();}
    return;
  }
  if(state.voice)preserveVoice(state.voice);
  clipSearch.inputs[clipSearch.target]={comp:state.comp,prefix:englishPrefix,language:state.language,layout:state.layout};
  clipSearch.target=target;
  const saved=clipSearch.inputs[target];
  Object.assign(state,{comp:selectionChanged?null:saved.comp,language:saved.language,layout:saved.layout});
  englishPrefix=selectionChanged?'':saved.prefix;context='';learningChain=[];associatedWords=[];clearSelectionLock();state.selectionActions=false;
  localInput=target==='search'?clipSearchInput:null;editor=localInput||hostEditor;
  if(target==='host'&&selectionChanged&&nativeIme)hostEditor.value='';
  if(target==='search')clipSearch.keys=true;
  if(state.page==='candidates')state.page='letters';
  nativeIme?.searchFocus?.(target==='search');render();
  if(target==='search')clipSearchInput.focus({preventScroll:true});else if(nativeIme)clipSearchInput.blur();else hostEditor.focus({preventScroll:true});
}
function updateClipSearchHeader(header,open){
  const slot=header.querySelector('.clip-search-input-slot');if(slot.firstElementChild!==clipSearchInput)slot.replaceChildren(clipSearchInput);
  const label=state.clipTab==='clipboard'?'剪贴板':'常用语';clipSearchInput.placeholder='搜索'+label;clipSearchInput.setAttribute('aria-label','搜索'+label);clipSearchInput.disabled=!open;
  header.querySelector('.tabs-pill').inert=open;
  const toggle=header.querySelector('.clip-search-toggle');toggle.setAttribute('aria-expanded',String(open));toggle.setAttribute('aria-label',open?'关闭搜索':'展开搜索');
  clipSearchInput.readOnly=Boolean(clipSearch&&clipSearch.target==='host');
  if(header.classList.contains('search-open')!==open)header.getBoundingClientRect();
  header.classList.toggle('search-open',open);
}
function paintClipSearch(){
  if(!clipSearch)return;
  updateClipSearchHeader(clipSearch.header,true);
  const texts=state.clipTab==='clipboard'?(clips.includes(latestClipboard)?clips:[latestClipboard,...clips]):currentList();
  const query=clipSearchInput.value,list=localPanel.querySelector('.clip-search-results'),top=query===clipSearch.query?list.scrollTop:0;
  const hits=clipboardMatches(query,texts,{manual:true});clipSearch.query=query;
  list.innerHTML=hits.length?hits.map(hit=>{
    const ranges=hit.ranges.slice().sort((a,b)=>a[0]-b[0]),merged=[];
    for(const range of ranges){const last=merged.at(-1);if(last&&range[0]<=last[1])last[1]=Math.max(last[1],range[1]);else merged.push([...range]);}
    let at=0,body='';for(const [start,end] of merged){body+=esc(hit.text.slice(at,start))+`<mark>${esc(hit.text.slice(start,end))}</mark>`;at=end;}body+=esc(hit.text.slice(at));
    return button(hit.text,'clip-search-paste',hit.text,'clip-search-result',body);
  }).join(''):'<div class="empty-state">没有找到，试试更短的关键词</div>';
  list.scrollTop=top;
}
function pasteClipSearch(text){
  if(!clipSearch||!text||!currentList().includes(text)&&latestClipboard!==text)return;
  const keepSearch=state.clipTab==='clipboard'&&state.clipLocked;
  const host=clipSearch.inputs.host,comp=clipSearch.target==='host'?state.comp:host.comp;
  if(nativeIme){
    if(typeof nativeIme.commitAccepted==='function'){if(!JSON.parse(nativeIme.commitAccepted(text)).committed){toast('未能上屏，请重试');return;}}
    else nativeIme.commit(text);
    hostEditor.value='';hostEditor.setSelectionRange(0,0);
  }else{
    const result=replaceRange(hostEditor.value,comp?.start??hostEditor.selectionStart,comp?comp.start+comp.raw.length:hostEditor.selectionEnd,text);hostEditor.value=result.text;hostEditor.setSelectionRange(result.cursor,result.cursor);
  }
  host.comp=null;host.prefix='';
  clearSelectionLock();
  if(clipSearch.target==='host'){state.comp=null;englishPrefix='';context='';associatedWords=[];}
  if(keepSearch){if(clipSearch.target==='host')render();return;}
  closeClipSearch(false);
  state.page=state.returnPage;
  render();
}
// 管理与确认保留底部 sheet；单条内容菜单贴近被长按的条目。
function sheet(title, rows) {
  return `<div class="clip-menu-backdrop" data-action="clip-close"><div class="clip-menu-sheet"><div class="clip-menu-title">${esc(title)}</div>${rows.map(([action, label, cls]) => button(label, action, '', 'row' + (cls ? ' ' + cls : ''))).join('')}${button('取消', 'clip-close', '', 'clip-menu-cancel')}</div></div>`;
}
function footer() {
  const total=nativeIme&&!localInput?nativeCursor.total:editor.value.length,cursor=nativeIme&&!localInput?nativeCursor.position:editor.selectionEnd;
  const drag=state.cursorDrag,locked=lockedNow(),zoneClass='cursor-zone'+(drag?drag.selecting?' selecting':' dragging':'')+(locked?' locked':'');
  const label=locked?'起点锁定，左右拖动调整终点，长按解除锁定':'底部中间空白可左右拖动光标、点按两侧精调一个字，长按后拖动框选文字';
  return `<div class="keyboard-footer">${settings.footerSwitcher ? button('底部输入法菜单', 'switcher', '', '', icon('keyboard')) : '<span class="empty"></span>'}<div class="${zoneClass}" data-action="cursor-gesture" role="slider" tabindex="0" aria-label="${label}" aria-valuemin="0" aria-valuenow="${cursor}"><span class="cursor-hint"></span><span class="cursor-focus"></span><span class="cursor-contact"></span></div>${clipSearch?button('收起键盘查看结果','clip-search-keys','','',icon('hide')):settings.footerClipboard ? button('底部剪贴板', 'clipboard', '', '', icon('copy')) : '<span class="empty"></span>'}</div>${joyGuide()}`;
}
function selectionEditor(){
  if(!state.selectionActions||state.voice||state.hidden)return '';
  // 面板只由外部点击关闭：左边选中字数、右边空白处放删除钮，中间不留空位。
  // 左侧四个操作钮仍是原来的按钮与命中区，只把文字换成图标（中文名保留在 aria-label）。
  const locked=lockedNow(),mode=locked?'起点锁定 · 拖动调整终点':'移动光标 · 长按锁定起点';
  const zero=selectedCount===0?' disabled':'';
  const pad=directions.map(([x,y,arrow,label])=>`<button class="selection-direction" data-action="cursor-gesture" data-track="mini" data-direction="${x},${y}" aria-label="${label}" style="grid-column:${x+2};grid-row:${y+2}">${arrow}</button>`).join('');
  return `<div class="selection-editor-backdrop" data-action="selection-close"></div><section class="selection-editor" aria-label="选字编辑框"><div class="selection-editor-header"><span class="selection-count">${selectedCount<0?'选字':`已选 ${selectedCount} 字`}</span>${button('删除','delete','','selection-delete',icon('delete'))}</div><div class="selection-editor-body"><div class="selection-editor-clipboard">${button('全选','select-all','','selection-all',icon('select-text'))}${button('复制','copy-text','','selection-copy',icon('copy-pages'),zero)}${button('剪切','cut-text','','',icon('cut'),zero)}${button('粘贴',nativeIme&&!localInput?'paste-text':'paste-last','','',icon('paste-sheet'))}</div><div class="cursor-zone selection-mini-track${locked?' locked':''}" data-action="cursor-gesture" data-track="mini" role="group" aria-label="${esc(mode)}">${pad}<button class="selection-center" data-action="cursor-gesture" data-track="mini" aria-label="长按锁定或解除起点"><span></span></button></div></div></section>`;
}
function feedbackZone(){return keyboard.querySelector(cursorFeedback.mini?'.selection-mini-track':'.keyboard-footer .cursor-zone');}
// 编辑框呼出动效（概念③「层叠卡片」，只动 opacity/transform，不改布局与几何）：
// A=键盘已在场（滑轨长按呼出）：sheet 从键盘上沿升起（56px，damping≈1.0 无过冲的克制弹簧），键盘内容同帧轻微压暗「退后」；
// B=键盘「刚到达」（原生桥报选区起点 / 网页取消隐藏）：键盘先升（9%），编辑框错峰 70ms 从更低处更快追上（超车），落定后同样退后。
// prefers-reduced-motion 与自动化环境（navigator.webdriver）下直接落定，回归断言不受动画影响。
let kbArrivedAt=0; // 最近一次「键盘到达」时刻；400ms 内编辑框出现即视为同屏共现（B）。
let keyboardEntrance=null;
function playEditorEntrance(mode){
  const editor=keyboard.querySelector('.selection-editor');if(!editor)return;
  if(matchMedia('(prefers-reduced-motion: reduce)').matches||navigator.webdriver)return;
  if(mode==='B')keyboardEntrance=keyboard.animate([{transform:'translateY(9%)',opacity:.55},{transform:'none',opacity:1}],{duration:380,easing:'cubic-bezier(.22,1,.36,1)'});
  editor.style.transformOrigin='50% 100%';
  editor.animate([{opacity:0,transform:`translateY(${mode==='B'?112:56}px) scale(.94)`},{opacity:1,transform:'none'}],{duration:mode==='B'?320:340,delay:mode==='B'?70:0,easing:'cubic-bezier(.22,1,.36,1)',fill:'backwards'});
  keyboard.querySelector('.selection-editor-backdrop')?.animate([{opacity:0},{opacity:1}],{duration:260,fill:'backwards'});
}
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
  if(gesture?.padCenter){
    const c=gesture.padCenter,dx=gesture.lastX-(c.left+c.right)/2,dy=gesture.lastY-(c.top+c.bottom)/2;
    const scale=Math.min(1,Math.max(0,Math.min(c.width,c.height)/2-8)/Math.max(1,Math.hypot(dx,dy)));
    zone.querySelector('.selection-center span').style.transform=`translate(${dx*scale}px,${dy*scale}px)`;
    return;
  }
  const bounds=zone.getBoundingClientRect(),x=cursorFeedbackPosition(bounds.width,cursorFeedback.clientX-bounds.left);
  zone.classList.toggle('selecting',cursorFeedback.selecting);zone.classList.toggle('dragging',!cursorFeedback.selecting);
  const line=zone.querySelector('.cursor-contact'),focus=zone.querySelector('.cursor-focus');
  if(!line||!focus)return;
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
  if(clipSearch&&localInput)return '';
  if(state.language==='zh'&&associatedWords.length&&(!privateField||localInput))return `<div class="candidate-bar">${candidateStrip(associatedWords,'association-candidate')}${button('更多候选','more-candidates','','candidate-expand',icon(state.page==='candidates'?'hide':'forward'))}</div>`;
  if(englishPrefix&&!privateField&&state.language==='en')return `<div class="candidate-bar">${candidateStrip(englishCandidates(englishPrefix),'english-candidate')}</div>`;
  if(clipSearch)return '';
  if(state.toolbarSort)return `<div class="candidate-bar clip-candidate"><span class="clip-source">拖动图标排序</span>${button('完成','toolbar-sort-done','','toolbar-done')}<span class="clip-hint">${settings.toolbar.some(key=>String(key).startsWith('custom:'))?'点自定义项上的 × 可移除':'长按自定义项无法移除'}</span></div><div class="toolbar toolbar-sort">${settings.toolbar.map(toolbarItemButton).join('')}</div>`;
  const clipBar=state.clipCandidate&&settings.clipboardCandidate?clipCandidateBar():'';
  const bar=`<div class="toolbar">${settings.toolbar.map(toolbarItemButton).join('')}</div>`;
  return clipBar||bar;
}
function candidateStrip(words,action='candidate') {
  const referenceSlot=action==='candidate'&&!clipSearch&&state.language==='zh'&&state.layout==='26'&&!localInput&&settings.clipboardMatch;
  const rows=words.map(word=>button('候选：'+word,action,word,'candidate-hold',esc(word)));
  const reference=referenceSlot?`<div class="clipboard-slot" inert aria-hidden="true">${button('展开剪贴板匹配','clipboard-reference','','clipboard-reference','<span class="clipboard-preview"></span><span class="clipboard-count" aria-hidden="true"></span>','disabled aria-hidden="true" aria-expanded="false"')}</div>`:'';
  return `<div class="candidate-visible${state.language==='zh'&&state.layout==='26'?' natural-candidates':''}" tabindex="0" aria-label="候选字，左右滑动浏览" data-query="${esc(state.comp?.raw||words.join(','))}">${rows.join('')}</div>${reference}`;

}
function currentCandidates() {
  if(!state.comp)return associatedWords;
  if(state.language==='en')return englishCandidates(state.comp.raw);
  const useNative=nativePinyinActive(),limit=state.page==='candidates'?2000:240;
  let words=useNative?JSON.parse(nativeIme.pinyinCandidates(state.comp.raw,limit)).words||[]:(state.layout==='9'?nineCandidates:pinyinCandidates)(state.comp.raw,limit);
  if(useNative)words=[...new Set([...phraseCandidates(state.comp.raw),...words])];
  if(settings.rememberInput&&!privateField){
    const learning=personalLexicon.lookup(state.comp.raw);
    const recalled=learnedCandidates(state.comp.raw,learning,limit),key=(state.layout==='9'?'9:':'p:')+state.comp.raw.toLowerCase();
    words=[...new Set([...recalled,...words])];
    words=rankLearned(words,learning.filter(row=>row.key===key||recalled.includes(row.word)),key);
  }
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
// 保留旧工具栏内容的读取和移除，不再提供新增入口。
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
    ?[...(state.clipTab==='clipboard'?[['context-split','拆词']]:[]),['context-edit','编辑'],...(state.clipTab==='clipboard'?[['context-favorite','添加到常用语']]:[]),['context-copy','复制'],['context-delete','删除']]
    :state.clipTab==='clipboard'
      ?[['snippet-add','添加内容'],['system-paste','从系统剪贴板导入'],['clip-manage','批量管理'],['clear-snippets','清空列表','danger']]
      :[['clear-snippets','清空这个分组的常用语','danger']];
  if(snippetUndo&&snippetUndo.tab===state.clipTab&&(state.clipTab==='clipboard'||snippetUndo.group===currentGroup()))rows.push(['undo-snippet','撤销删除']);
  if(groupUndo)rows.push(['group-undo','撤销删除分组']);
  if(!item)return `<div class="clip-menu-backdrop clip-item-backdrop clip-management-backdrop" data-action="clip-close"><div id="clip-management-menu" class="clip-menu-sheet clip-item-menu clip-management-menu" role="menu" aria-label="更多管理操作">${rows.map(([action,label,cls])=>button(label,action,'','row'+(cls?' '+cls:''),'','role="menuitem" tabindex="-1"')).join('')}</div></div>`;
  return `<div class="clip-menu-backdrop clip-item-backdrop" data-action="clip-close"><div class="clip-menu-sheet clip-item-menu" role="group" aria-label="${esc(clipMenu.text)}">${rows.map(([action,label,cls])=>button(label,action,'','row'+(cls?' '+cls:''))).join('')}</div></div>`;
}
function openClipMenu(index=-1){const list=currentList();clipMenu={index,text:index<0?'':list[index]};render();keyboard.querySelector('.clip-menu-sheet button')?.focus({preventScroll:true});}
function closeClipMenu(){const management=clipMenu?.index===-1;clipMenu=null;state.clipConfirm=null;state.clipGroupMenu=null;render();if(management)keyboard.querySelector('[data-action="clip-manage-menu"]')?.focus({preventScroll:true});}
function paintClipManagement(previous){
  let overlay=keyboard.querySelector('.clip-management-backdrop');
  if(overlay&&previous&&overlay.firstElementChild.innerHTML===previous.firstElementChild.innerHTML){overlay.replaceWith(previous);overlay=previous;}
  if(!overlay){
    if(!previous||state.page!=='clipboard')return;
    keyboard.append(previous);previous.classList.add('closing');previous.inert=true;
    if(previous.motion.playbackRate>0){previous.motion.playbackRate=-1;previous.motion.play();}
    return;
  }
  const menu=overlay.firstElementChild,trigger=keyboard.querySelector('[data-action="clip-manage-menu"]')||keyboard.querySelector('.panel-title');
  const bounds=overlay.getBoundingClientRect(),anchor=trigger.getBoundingClientRect();
  menu.style.left=Math.max(8,Math.min(anchor.right-bounds.left-menu.offsetWidth,bounds.width-menu.offsetWidth-8))+'px';
  menu.style.top=Math.max(0,anchor.bottom-bounds.top+6)+'px';
  menu.style.maxHeight=Math.max(50,keyboard.querySelector('.keyboard-footer').getBoundingClientRect().top-bounds.top-parseFloat(menu.style.top)-8)+'px';
  menu.style.transformOrigin=(anchor.left+anchor.width/2-bounds.left-parseFloat(menu.style.left))+'px '+(anchor.top+anchor.height/2-bounds.top-parseFloat(menu.style.top))+'px';
  overlay.classList.remove('closing');overlay.inert=false;
  if(!overlay.motion){
    const reduce=matchMedia('(prefers-reduced-motion: reduce)').matches;
    overlay.motion=menu.animate([{opacity:0,transform:reduce?'none':'translateY(-6px) scale(.95)'},{opacity:1,transform:'none'}],{duration:180,easing:'cubic-bezier(.23,1,.32,1)',fill:'both'});
    overlay.motion.onfinish=()=>{if(overlay.classList.contains('closing'))overlay.remove();};
  }else if(overlay.motion.playbackRate<0){overlay.motion.playbackRate=1;overlay.motion.play();}
}
function saveSnippets(){try{localStorage.setItem('voxfix-snippets-v1',JSON.stringify({clips,phrases:phraseGroups,clipSignature:/^[a-f0-9]{64}$/.test(nativeClipSignature)?nativeClipSignature:''}));updatePhraseOptions();return true;}catch{const message='未能保存，请检查可用空间后重试';if(localInput===$('snippet-content'))$('snippet-error').textContent=message;else toast(message);return false;}}
function setSnippetList(list,tab=state.clipTab,group=currentGroup()){const previous=tab==='clipboard'?clips:group.items;if(tab==='clipboard')clips=list;else group.items=list;if(saveSnippets())return true;if(tab==='clipboard')clips=previous;else group.items=previous;return false;}
function openSnippet(index=-1){
  splitRequest++;
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
const layouts = [['9','9键拼音','拼'],['26','26键拼音','拼'],['en26','26键英文','EN']];
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
  const stage=panel.querySelector('.voice-stage');
  if(stage)stage.style.visibility=state.voice.micReady===false?'hidden':'';
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
  if(cancelled!==g.cancel){ g.cancel=cancelled; state.voice.phase=cancelled?'cancel':state.voice.micReady===false?'loading':'listening'; paintVoice(); }
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
  if (state.popup?.type==='key') return `<div class="popup key-popup" style="left:${state.popup.x}px;top:${state.popup.y}px">${state.popup.options.map((s,i)=>button(s,'long-insert',s,i===state.popup.choice?'selected':'')).join('')}</div>`;
  return '';
}
function pageLabel() {
  if(state.page==='clipboard-split')return '剪贴板拆词';
  if(state.voice) return `${state.voice.long?'长语音':'长按空格语音'} · ${state.voice.phase==='loading'?'开启麦克风':state.voice.phase==='paused'?'已暂停':state.voice.phase==='recognizing'?'识别中':state.voice.phase==='cancel'?'上滑取消':'倾听中'}${state.voice.demo?'（演示）':''}`;
  return {letters:state.layout==='9'?'9键中文':state.language==='zh'?'26键中文':'26键英文',candidates:'全部候选',numbers:'数字键盘',symbols:`${state.symbol}符号`,clipboard:state.clipTab==='clipboard'?'剪贴板':'常用语',layouts:'键盘选择',emoji:'表情',menu:'功能菜单 · 补充试用',edit:'编辑文字 · 补充试用'}[state.page] || '键盘';
}
const referenceMap = {letters:'keyboard-001.jpg',en:'keyboard-014.jpg',numbers:'keyboard-022.jpg','常用':'keyboard-026.jpg','中文':'keyboard-028.jpg','英文':'keyboard-027.jpg','网址':'keyboard-029.jpg',clipboard:'clipboard.jpg',phrases:'phrases.jpg',layouts:'keyboard-042.jpg',emoji:'keyboard-050.jpg',switcher:'keyboard-055.jpg',voice:'voice.jpg',cancel:'voice-cancel.jpg',recognizing:'voice-recognizing.jpg'};
function render() {
  if(candidateContact&&!quoteEditor&&!privateField&&!document.hidden){candidateRenderPending=true;return;}
  for(const node of keyboard.querySelectorAll('[data-scroll-key]'))scrollPositions.set(node.dataset.scrollKey,node.scrollTop);
  const previousCandidates=keyboard.querySelector('.candidate-visible'),previousReference=keyboard.querySelector('.clipboard-slot');
  const candidateScroll=previousCandidates?.scrollLeft||0;
  // 呼出动效前置采样：「键盘是否刚从收起态回来」「上一帧有没有编辑框」（innerHTML 重建前抓，别放后面）。
  const wasHidden=keyboard.hidden;
  const previousEditor=keyboard.querySelector(':scope > .selection-editor');
  const previousBackdrop=keyboard.querySelector(':scope > .selection-editor-backdrop');
  const previousManagement=keyboard.querySelector('.clip-management-backdrop');
  const managementFocus=previousManagement?.contains(document.activeElement)?document.activeElement:null;
  const hadEditor=!!previousEditor;
  if(wasHidden&&!state.hidden)kbArrivedAt=performance.now();
  // 语音期间不把可见编辑框置为只读：只读的 textarea/input 不再绘制插入光标（实测确认）。
  // 输入守卫已由 keydown / pointerdown 分支（state.voice 直接返回）负责。
  $('send').disabled=Boolean(state.voice);
  snippetForm.querySelector('button[type=submit]').disabled=Boolean(state.voice)||Boolean(quoteEditor&&!editor.value.trim());
  $('snippet-split').disabled=Boolean(state.voice)||!$('snippet-content').value.trim();
  $('long-voice').disabled=Boolean(state.voice)||!speechReady;
  $('pause-voice').disabled=!state.voice?.long||state.voice.demo||!['listening','paused'].includes(state.voice.phase);
  $('pause-voice').textContent=state.voice?.phase==='paused'?'继续':'暂停';
  $('finish-voice').disabled=!state.voice?.long||!['listening','paused'].includes(state.voice.phase);
  $('cancel-voice').disabled=!state.voice;
  $('polish-text').disabled=!polishReady||polishBusy||Boolean(state.voice);
  const localEditing=Boolean(clipSearch)||localInput&&state.page!=='clipboard-split';
  const searchIdle=clipSearch&&state.page==='letters'&&!state.comp&&!(clipSearch.target==='host'&&(associatedWords.length||englishPrefix));
  keyboard.className=`keyboard${state.night?' night':''}${localEditing?' local-edit':''}${state.page==='clipboard-split'?' clip-split-page':''}${clipSearch?' clip-search-mode':''}`;
  const localHeight=clipSearch?(clipSearch.keys?176+(searchIdle?45:0):351):localEditing?(localInput===$('snippet-content')?128:44):0;
  const keyboardHeight=clipSearch?(clipSearch.keys?settings.height+176:settings.height):settings.height+localHeight;
  localPanel.hidden=!localEditing;
  localPanel?.classList.toggle('snippet-edit',localInput===$('snippet-content'));
  localPanel.classList.toggle('clip-search-panel',Boolean(clipSearch));
  localPanel.classList.toggle('night',state.night);
  localPanel.classList.toggle('search-keys-hidden',Boolean(clipSearch&&!clipSearch.keys));
  localPanel.classList.toggle('search-host-target',Boolean(clipSearch&&clipSearch.target==='host'));
  $('phone').style.setProperty('--local-editor-height',localHeight+'px');
  $('phone').style.setProperty('--clip-search-total',keyboardHeight+'px');
  for(const [key,value] of Object.entries({'--keyboard-height':keyboardHeight+'px','--key-font':settings.fontSize+'px','--key-gap':settings.gap+'px','--key-radius':settings.radius+'px','--kb-bg':settings.background})) keyboard.style.setProperty(key,value);
  keyboard.hidden=state.hidden; $('reopen').hidden=!state.hidden;
  const pages={letters:()=>state.layout==='9'?nine():letters(),candidates:candidatePanel,numbers,symbols:symbolPanel,clipboard,'clipboard-split':clipboardSplitPanel,layouts:layoutPanel,emoji:emojiPanel,menu:menuPanel,edit:editPanel};
  // 退场幽灵要在重绘后活下来（它只活 260ms），否则一次多余的 render 就会把动画吞掉。
  const voiceGhost=keyboard.querySelector(':scope > .voice-ghost');
  if(state.voice&&keyboard.querySelector(':scope > .voice-panel'))paintVoice();
  else{
    voiceRenderer?.dispose();voiceRenderer=null;
    keyboard.innerHTML=(clipSearch&&!clipSearch.keys?'':state.voice?voicePanel():(pages[state.page]||letters)())+popup()+selectionEditor();
    // 保留动画目标，只更新内容，避免原生重绘把入场动画截成最终帧。
    const nextEditor=keyboard.querySelector(':scope > .selection-editor');
    if(previousEditor&&nextEditor){
      previousEditor.innerHTML=nextEditor.innerHTML;nextEditor.replaceWith(previousEditor);
      keyboard.querySelector(':scope > .selection-editor-backdrop')?.replaceWith(previousBackdrop);
    }
    if(state.voice){paintVoice();voiceRenderer=createVoiceOrb(keyboard.querySelector('.voice-texture'));}
    if(voiceGhost)keyboard.append(voiceGhost);
  }
  if(clipSearch)paintClipSearch();else{const header=keyboard.querySelector('.clip-header');if(header)updateClipSearchHeader(header,false);}
  // 编辑框呼出动效挂点：面板新出现才播（面板在场期间的普通重绘不重播）；退后态由 .kb-recede 表达，
  // 收起时同一过渡对称还原。只切 filter/transform/opacity，键位几何与面板开合无关的断言仍然成立。
  const editorVisible=!!keyboard.querySelector(':scope > .selection-editor');
  if(!editorVisible){keyboardEntrance?.cancel();keyboardEntrance=null;}
  keyboard.classList.toggle('kb-recede',editorVisible);
  if(editorVisible&&!hadEditor){
    const mode=kbArrivedAt&&performance.now()-kbArrivedAt<400?'B':'A';
    kbArrivedAt=0;
    playEditorEntrance(mode);
  }
  if(gesture?.target.classList.contains('key')){
    const held=[...keyboard.querySelectorAll('.key')].find(key=>key.dataset.action===gesture.action&&key.dataset.value===(gesture.value||''));
    if(held){held.classList.add('pressed');gesture.target=held;}
  }
  // 面板内的连删每删一次都会重绘：新按钮节点上补回按压态，手指按住的视觉不会中途消失。
  else if(gesture?.panelDelete){
    const held=keyboard.querySelector('.selection-delete');
    if(held&&held!==gesture.target){held.classList.add('pressed');gesture.target=held;}
  }
  const candidates=keyboard.querySelector('.candidate-visible');
  if(candidates && previousCandidates?.dataset.query===candidates.dataset.query)candidates.scrollLeft=candidateScroll;
  if(previousReference&&!privateField&&!noPersonalizedLearning&&keyboard.querySelector('.clipboard-slot'))keyboard.querySelector('.clipboard-slot').replaceWith(previousReference);
  syncClipboardReference();
  layoutCandidateGrid();
  keyboard.classList.toggle('text-selecting',Boolean(state.cursorDrag?.selecting));syncSelectionFeedback();
  for(const [selector,id] of [['.number-strip','numbers'],['.symbol-grid','symbols:'+state.symbol],['.clip-list','clipboard:'+state.clipTab],['.t9-symbols','nine'],['.candidate-grid','candidates:'+candidateMode+':'+(state.comp?.raw||associatedWords.join(','))],['.emoji-body','emoji:'+state.emojiTab+':'+state.emojiCategory]]){
    const node=keyboard.querySelector(selector);if(node){node.dataset.scrollKey=id;const top=scrollPositions.get(id)||0;if(selector==='.candidate-grid')while(top+node.clientHeight>node.scrollHeight&&appendCandidates()){}node.scrollTop=top;}
  }
  updateCandidatePages();
  const splitGrid=keyboard.querySelector('.clip-split-grid');
  if(splitGrid){splitGrid.dataset.scrollKey='clipboard-split';splitGrid.scrollTop=scrollPositions.get('clipboard-split')||0;}
  const itemMenu=keyboard.querySelector('.clip-item-menu:not(.clip-management-menu)');
  if(itemMenu){
    const row=[...keyboard.querySelectorAll('.clip-text')].find(node=>node.dataset.value===clipMenu.text);
    const kb=keyboard.getBoundingClientRect(),anchor=row?.getBoundingClientRect(),menu=itemMenu.getBoundingClientRect();
    itemMenu.style.left=Math.max(8,Math.min((anchor?.left??kb.left)-kb.left+15,kb.width-menu.width-8))+'px';
    itemMenu.style.top=Math.max(8,Math.min((anchor?.top??kb.top)-kb.top+8,kb.height-menu.height-8))+'px';
  }
  paintClipManagement(previousManagement);
  if(managementFocus?.isConnected&&!managementFocus.closest('.closing'))managementFocus.focus({preventScroll:true});
  $('page-label').textContent=state.hidden?'键盘已收起':pageLabel();
  $('send').innerHTML=icon(editor.value?'send':'voice');
  // 「来自剪贴板」标签悬在键盘上沿之外（真机由原生 popup 画），键盘高度与「有没有预览」完全无关：
  // 这里绝不能再拿 clip-preview 的实测高度反推增量——45.14 这种亚像素值经 Math.ceil 后会让整块键盘
  // 在有预览／没预览之间抖 1 CSS px，正是用户抱怨的「顶出来」。高度仍由上面的基础路径统一决定。
  const clipPreview=keyboard.querySelector('.clip-preview');
  if(nativeIme)nativeIme.clipHint?.(clipPreview?clipCandidateLabel():'');
  if(nativeIme)nativeIme.resize(Math.ceil(keyboardHeight));
  paintReferenceHighlight();
  syncCursorFeedback();
  nightShade.hidden=!state.night||state.hidden;
  nightShade.style.height=($('phone').getBoundingClientRect().bottom-keyboard.getBoundingClientRect().top)+'px';
  nativeSettings?.classList.toggle('night',state.night);
  document.querySelectorAll('#quick-pages button').forEach(b=>b.setAttribute('aria-pressed', b.dataset.value===state.page));
  let ref=state.voice?(state.voice.phase==='cancel'?'cancel':state.voice.phase==='recognizing'?'recognizing':'voice'):state.popup==='switcher'?'switcher':state.page==='symbols'?state.symbol:state.page==='clipboard'?state.clipTab:state.page==='letters'&&state.language==='en'?'en':state.page;
  $('reference-image').src='reference/'+(referenceMap[ref]||referenceMap.letters);
  $('reference-label').textContent=['menu','edit','candidates'].includes(state.page)?'此页面未在录屏中展开；当前展示全键盘参考。':state.layout==='9'&&state.page==='letters'?'录屏只展示了九宫格入口，未展示九宫格画面。':'截自你提供的录屏；个人剪贴板和常用语内容已隐藏。';
}
function feedback(message) { $('feedback').textContent=message; }
function toast(message) { clearTimeout(toastTimer); $('toast').textContent=message; $('toast').hidden=false; toastTimer=setTimeout(()=>$('toast').hidden=true,2600); feedback(message); }
function persist() { updatePhraseOptions();try { localStorage.setItem('voxfix-layout-v1',JSON.stringify(settings)); } catch { feedback('当前浏览器不能保存设置，可以导出文件留存。'); } }
function applyText(result, end=result.cursor) { editor.value=result.text; editor.setSelectionRange(result.cursor,end); editor.scrollTop=editor.scrollHeight;paintReferenceHighlight(); $('send').innerHTML=icon(editor.value?'send':'voice'); if(nativeIme&&!localInput&&state.comp)nativeIme.compose(state.comp.raw); }
function insert(value) { state.clipCandidate='';englishPrefix='';if(nativeIme&&state.comp){commit(currentCandidates()[0]||state.comp.raw);if(state.comp)commit(state.comp.raw);}learningChain=[];state.comp=null;rememberContext(value);if(nativeIme&&!localInput){nativeIme.commit(value);applyText({text:'',cursor:0});return;} applyText(replaceRange(editor.value,editor.selectionStart,editor.selectionEnd,value)); }
function commit(word) {
  if(!state.comp)return;
  const nativeRows=nativePinyinActive()?JSON.parse(nativeIme.pinyinCandidates(state.comp.raw,state.page==='candidates'?2000:240)):null;
  if(clipSearch&&localInput&&nativeRows&&typeof nativeIme.pinyinChooseLocal==='function'&&word!==state.comp.raw&&word&&(nativeRows.words||[]).includes(word)){
    const selected=JSON.parse(nativeIme.pinyinChooseLocal(state.comp.raw,word));
    if(!selected.committed){toast('未能选词，请重试');return;}
    const previous=state.comp,remaining=selected.remaining||'';
    applyText(replaceRange(editor.value,previous.start,previous.start+previous.raw.length,word+remaining));
    state.comp=remaining?{start:previous.start+word.length,raw:remaining}:null;
    if(state.page==='candidates')state.page='letters';return;
  }
  if(nativeRows&&!localInput&&word!==state.comp.raw&&word&&(nativeRows.words||[]).includes(word)&&/[\u3400-\u9fff]/.test(word)){
    const selected=JSON.parse(nativeIme.pinyinChoose(state.comp.raw,word,settings.rememberInput&&!privateField&&!noPersonalizedLearning));
    if(!selected.committed){feedback('当前选词未提交，请重新选择。');return;}
    recordChoice(state.comp.raw.slice(0,state.comp.raw.length-selected.remaining.length),word,nativeRows.preedit);
    rememberContext(word);state.comp=selected.remaining?{start:0,raw:selected.remaining}:null;
    applyText({text:selected.remaining,cursor:selected.remaining.length});
    if(state.page==='candidates')state.page='letters';return;
  }
  if((!nativeIme||localInput)&&editor.value.slice(state.comp.start,state.comp.start+state.comp.raw.length)!==state.comp.raw){state.comp=null;render();return;}
  const previous=state.comp,consumed=state.language==='zh'&&state.layout!=='9'?pinyinConsumed(previous.raw,word):previous.raw.length,remaining=previous.raw.slice(consumed);
  const checked=nativeIme&&!localInput&&typeof nativeIme.commitAccepted==='function';
  if(checked&&!JSON.parse(nativeIme.commitAccepted(word)).committed){feedback('文字未提交，请重试。');return;}
  recordChoice(previous.raw.slice(0,consumed),word,nativeRows?.preedit);
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
// keepPanel：面板内的删除入口用。删除仍然完全走主键盘那一套（选区优先、否则删光标前一字、连删同一节奏），
// 只是「删除」属于选字流程里的动作，删除后起点锁定照旧失效（原生 delete 也会清 anchor），但面板留着。
function deleteText(keepPanel=false) {
  learningChain=[];
  const hadSelectionActions=state.selectionActions;
  if(keepPanel&&hadSelectionActions)releaseSelectionLock();else clearSelectionLock();
  // 先记录删除前候选栏是否可见：清空文本后必须把联想/英文候选一起刷掉，否则会留下“文字删完了候选还在”。
  const hadCandidateBar=Boolean(englishPrefix)||(state.language==='zh'&&associatedWords.length>0)||state.page==='candidates';
  context='';learningChain=[];associatedWords=[];
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
function showPage(page) { endClipboardSplit();if(state.voice) cancelVoice();clipMenu=null;state.clipGroupMenu=null;state.clipConfirm=null;clearTimeout(clipHold?.timer);clipHold=null;if(['letters','numbers','symbols'].includes(state.page))state.returnPage=state.page;if(page==='symbols'){const origin=['letters','numbers'].includes(state.page)?state.page:state.returnPage;if(origin!=='symbols')state.symbolReturn=origin;}state.popup=null;state.page=page;state.hidden=false;render(); }
function showTab(name) { if(nativeIme&&name==='custom'){$('custom-panel').hidden=false;nativeSettings.showModal();return;} $('try-panel').hidden=name!=='try'; $('custom-panel').hidden=name!=='custom'; document.querySelectorAll('[data-tab]').forEach(b=>b.setAttribute('aria-selected',b.dataset.tab===name)); }
function startVoice(long=false) {
  if(state.voice)return;
  learningChain=[];
  englishPrefix='';
  if(nativeIme){if(!nativeIme.voiceReady()){toast('语音正在准备，请稍后再试。');return;}if(state.comp)commit(state.comp.raw);state.popup=null;state.voice={phase:'loading',micReady:false,long:false,local:localInput,snapshot:localInput?{text:editor.value,start:editor.selectionStart,end:editor.selectionEnd}:null};beginVoiceBall();voicePanelFresh=true;if(localInput)nativeIme.startLocalVoice();else nativeIme.startVoice();render();startVoiceLoop();return;}
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
  if(nativeIme){if(cancelled){cancelVoice();return;}nativeIme.stopVoice();state.voice.phase='recognizing';render();return;}
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
  if(action==='clip-search-toggle'){if(clipSearch)closeClipSearch();else openClipSearch();return;}
  if(action==='clip-search-paste'){pasteClipSearch(value);return;}
  if(action==='clip-search-keys'&&clipSearch){clipSearch.keys=!clipSearch.keys;if(clipSearch.keys)state.page='letters';render();if(clipSearch.keys&&localInput)clipSearchInput.focus({preventScroll:true});return;}
  if(clipSearch&&localInput&&action==='enter'){if(state.comp)commit(currentCandidates()[0]||state.comp.raw);clipSearch.keys=false;state.page='letters';render();return;}
  if(clipSearch&&action==='return'){closeClipSearch();return;}
  if(clipSearch&&['clip-manage-menu','snippet-add','clipboard'].includes(action))closeClipSearch();
  if(action==='context-split'){const text=clipMenu?.text;if(text&&clips.includes(text))openClipboardSplit(text);return;}
  if(action==='split-word'){
    const i=Number(value),session=clipboardSplit;if(!session||!Number.isInteger(i)||i<0||i>=session.words.length)return;
    chooseClipboardWords(session.selected.includes(i)?session.selected.filter(index=>index!==i):[...session.selected,i]);return;
  }
  if(action==='split-all'){if(clipboardSplit)chooseClipboardWords(clipboardSplit.selected.length===clipboardSplit.words.length?[]:clipboardSplit.words.map((_,i)=>i));return;}
  if(action==='split-copy'){copyClipboardSplit();return;}
  if(action==='split-return'){endClipboardSplit(true);render();return;}
  if(clipboardSplit)endClipboardSplit();else if(action!=='context-split')splitRequest++;
  if(!['letter','nine','candidate','split','nine-split','space','more-candidates','candidate-mode','candidate-page','shift'].includes(action))learningChain=[];
  if(action!=='clip-tab'||value!=='clipboard')clipTabTap=null;
  // 关闭面板只收起面板：正文、光标、选区与起点锁定都按原样保留。
  if(action==='selection-close'){state.selectionActions=false;render();return;}
  // 面板内的删除（读屏/外接键盘触发的 click 也走这里）：面板开着时主键盘被遮罩挡住，
  // 所以这里的 delete 只可能来自面板按钮；与主键盘删除键同一套逻辑，删除后面板留着。
  if(action==='delete'&&state.selectionActions){deleteText(true);return;}
  // 除「保留现有选区」的几个操作外，其它按键都会改正文 —— 旧起点随之失效，锁定一并清掉。
  if(!['select-all','copy-text','cut-text','paste-text','paste-last','selection-step'].includes(action))clearSelectionLock();
  else if(['cut-text','paste-text','paste-last'].includes(action))clearSelectionLock();
  if(action==='selection-step'){selectionStep(...value.split(',').map(Number));return;}
  if(hasNativePinyin&&!fullPinyinLoaded&&action==='layout'&&value==='9')loadFullPinyin().catch(()=>feedback('拼音词库未能加载。'));
  if(nativeIme){
    if(action==='hide'){nativeIme.hide();return;}
    if(action==='switcher'){nativeIme.switcher();return;}
    if(action==='clipboard')nativeIme.refreshClipboard();
    if(action==='system-paste'){clipMenu=null;render();nativeIme.importClipboard();return;}
    if(action==='enter'&&!localInput){englishPrefix='';if(state.comp)commit(state.comp.raw);else nativeIme.enter();render();return;}
    if(['language','twentysix','layout'].includes(action)&&state.comp)commit(state.comp.raw);
    if(!localInput&&['select-all','copy-text','cut-text','paste-text','cursor-left','cursor-right'].includes(action)){if(state.comp)commit(state.comp.raw);nativeIme.edit(action);render();return;}
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
    case 'language': state.comp=null;englishPrefix='';context='';learningChain=[];associatedWords=[];state.language=state.language==='zh'?'en':'zh'; state.layout='26'; break;
    case 'shift': state.shift=state.shift?0:1; break;
    case 'split': if(state.comp&&!state.comp.raw.endsWith("'"))inputLetter("'"); break;
    case 'nine': inputLetter(value);break;
    case 'nine-split': if(state.comp&&!state.comp.raw.endsWith("'"))inputLetter("'");break;
    case 'clear-pinyin': if(state.comp)commit('');break;
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
    case 'clipboard-results': case 'clipboard-reference': if(referencePopup)closeReferencePopup();else if(referenceMatches.length){referencePopup={query:state.comp?.raw,matches:referenceMatches,scroll:0};paintClipboardReference();}return;
    case 'clipboard-result': if(!clips.includes(value)&&latestClipboard!==value){closeReferencePopup();syncClipboardReference();toast('这条内容已移除，请重新选择');return;}insertClipboardReference(value);return;
    case 'clip-candidate-dismiss': state.clipCandidate='';break;
    case 'toolbar-custom': { if(state.toolbarSort)break; const row=settings.customToolbar.find(item=>item.id===value); if(row)insert(row.text); break; }
    case 'toolbar-custom-remove': removeCustomToolbar(value);return;
    case 'toolbar-sort-done': state.toolbarSort=false;toolbarDrag=null;render();break;
    case 'clip-pick': state.clipSelected=state.clipSelected.includes(value)?state.clipSelected.filter(item=>item!==value):[...state.clipSelected,value]; break;
    case 'clip-select-all': state.clipSelected=[...currentList()];break;
    case 'clip-manage': state.clipManage=!state.clipManage;state.clipSelected=[];state.clipConfirm=null;clipMenu=null;break;
    case 'clip-manage-menu': if(clipMenu?.index===-1)closeClipMenu();else openClipMenu();return;
    case 'clip-manage-delete': if(!state.clipSelected.length){toast('先勾选要删除的内容。');return;} state.clipConfirm={action:'clip-manage-delete',title:`删除所选 ${state.clipSelected.length} 条记录？`};break;
    case 'clip-group': state.clipGroup=Math.max(0,Math.min(phraseGroups.length-1,Number(value)||0));clipMenu=null;state.clipGroupMenu=null;break;
    case 'clip-group-add': clipMenu=null;if(localInput){toast('请先保存或取消当前编辑');render();return;} if(nativeIme){openPhraseGroupEditor();return;} { let name='';try{name=window.prompt('新建常用语分组名称（最多 8 个字）')||'';}catch{} if(name)try{createPhraseGroup(name);}catch(error){toast(error.message);} } return;
    case 'group-menu-close': closeGroupMenu();return;
    case 'group-rename': { const index=state.clipGroupMenu?.index;state.clipGroupMenu=null;if(index===undefined)break; if(nativeIme){openPhraseGroupEditor(index);return;} { let name='';try{name=window.prompt('重命名分组（最多 8 个字）',phraseGroups[index]?.name||'')||'';}catch{} if(name)try{renamePhraseGroup(index,name);}catch(error){toast(error.message);} } break; }
    case 'group-delete': { const index=state.clipGroupMenu?.index;state.clipGroupMenu=null;const group=phraseGroups[index];if(!group)break;
      if(phraseGroups.length<=1){toast('至少要保留一个常用语分组');break;}
      state.clipConfirm={action:'group-delete',value:String(index),title:`删除分组「${group.name}」及其中 ${group.items.length} 条常用语？`};break; }
    case 'group-undo': clipMenu=null;undoGroupDelete();break;
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
    case 'layout': if(['9','26','en26'].includes(value)){state.layout=value==='en26'?'26':value;state.language=value==='en26'?'en':'zh';state.comp=null;state.page='letters';}break;
    case 'emoji-tab': state.emojiTab=value; break;
    case 'emoji-category': state.emojiCategory=value;emojiQuery=''; break;
    case 'emoji-search': if(nativeIme){openEmojiSearch();return;}state.emojiTab='Emoji';emojiSearchOpen=!emojiSearchOpen;if(!emojiSearchOpen)emojiQuery='';render();if(emojiSearchOpen)keyboard.querySelector('.emoji-search-input').focus();return;
    case 'switcher': state.popup=state.popup==='switcher'?null:'switcher'; break;
    case 'custom': showTab('custom'); state.popup=null; feedback('设置自动保存。'); break;
    case 'cancel-recognition': if(state.voice?.phase==='recognizing') { cancelVoice(); } return;
    case 'select-all': editor.setSelectionRange(0,editor.value.length);localSelection=null;selectedCount=characterCount(editor.value); state.comp=null; break;
    case 'copy-text': case 'cut-text': { const text=editor.value.slice(editor.selectionStart,editor.selectionEnd); if(selectedCount===0||!text)break; {try{clips=addSnippet(clips,text);snippetUndo=null;saveSnippets();}catch(error){toast(error.message);return;} if(settings.clipboardCandidate)state.clipCandidate=text; if(action==='cut-text') { if(editor.selectionStart===editor.selectionEnd) applyText({text:'',cursor:0}); else insert(''); } if(nativeIme)nativeIme.copy(text);else navigator.clipboard?.writeText(text).catch(()=>toast('已存入本页剪贴板，系统复制未获授权。')); } break; }
    case 'paste-last': if(clips[0]) insert(clips[0]); break;
    case 'cursor-left': case 'cursor-right': { state.comp=null; const segments=[...new Intl.Segmenter('zh',{granularity:'grapheme'}).segment(editor.value)].map(s=>s.index); segments.push(editor.value.length); const pos=action==='cursor-left'?segments.filter(p=>p<editor.selectionStart).at(-1)??0:segments.find(p=>p>editor.selectionEnd)??editor.value.length; editor.setSelectionRange(pos,pos); break; }
    default: return;
  }
  if(['language','twentysix','layout'].includes(action))fieldPreference=null;
  if(['language','twentysix','layout','night'].includes(action))saveKeyboard();
  render();
}

// 按键按压态只由 JS 的 .pressed 驱动：官方（讯飞小米版白皮）按下就是换一张
// rgba(202,208,220,.451) 的 9-patch，直接叠在键盘底上，无过渡、无缩放、无位移。
// CSS 里**不能**再给 .key 写 :active —— Android WebView 对 :active 的清除不可靠
// （首次触摸还会被当成 hover 起点），会出现"松手了颜色还在"，这正是颜色退不走的根因。
function clearKeyPress(){keyboard.querySelectorAll('.key.pressed,.selection-delete.pressed').forEach(node=>node.classList.remove('pressed'));}
// 字母键长按浮层：顺序固定为「大写 ｜ 键帽提示 ｜ 小写」，默认停在中间的键帽提示（D 键就是 D｜@｜d）。
// 命中只看「相对长按落点的水平位移」，不看浮层实际坐标——浮层为防越界被顶回键盘内时，
// 中间档不可能压在触点上（左边缘键被右推、右边缘键被左推），按浮层坐标判定必然错档。
const HOLD_OPTION=44, HOLD_SWITCH=24, HOLD_RETURN=12;
function holdOptions(hint,value){
  const upper=String(value??'').toUpperCase(),lower=String(value??'').toLowerCase();
  return [...new Set([upper,hint,lower].filter(Boolean))];
}
function holdMiddle(hint,options){const index=options.indexOf(hint);return index<0?Math.floor(options.length/2):index;}
// 浮层几何：左右各 1px 边框 + 6px 内边距，每档 44px；middle 是浮层左沿到中间档中心的距离。
function holdPopupGeometry(count,middle){return {width:14+count*HOLD_OPTION,middle:7+HOLD_OPTION*middle+HOLD_OPTION/2};}
// 三档位移判定带滞回：离开中间要明确移动 HOLD_SWITCH，回到中间只要 HOLD_RETURN，
// 边界上来回抖动不会反复切换；大位移一步跨档也直接落到目标档，不会卡在中间。
function holdChoiceAt(dx,current,middle,count){
  if(count<=1)return 0;
  const left=0,right=count-1;
  if(count===2)return current<=left?dx>=HOLD_RETURN?right:left:dx<=-HOLD_RETURN?left:right;
  if(current===left)return dx>=HOLD_SWITCH?right:dx>=-HOLD_RETURN?middle:left;
  if(current===right)return dx<=-HOLD_SWITCH?left:dx<=HOLD_RETURN?middle:right;
  return dx<=-HOLD_SWITCH?left:dx>=HOLD_SWITCH?right:middle;
}
function paintHoldChoice(choice){
  [...keyboard.querySelectorAll('.key-popup button')].forEach((option,index)=>option.classList.toggle('selected',index===choice));
}
keyboard.addEventListener('pointerdown',e=>{if(referencePopup&&!e.target.closest('.clipboard-results,.clipboard-slot')){closeReferencePopup();consumedClick={id:e.pointerId,until:Date.now()+700};e.preventDefault();e.stopImmediatePropagation();}},true);
keyboard.addEventListener('pointerdown',e=>{if(e.target.closest('.candidate-visible,.clipboard-slot')&&e.button===0)candidateContact=e.pointerId;},true);
function releaseCandidateContact(e){if(candidateContact!==e.pointerId)return;candidateContact=null;setTimeout(()=>{if(candidateContact!==null)return;if(candidateRenderPending){candidateRenderPending=false;render();}else syncClipboardReference();},0);}
for(const event of ['pointerup','pointercancel'])document.addEventListener(event,e=>{if(event==='pointercancel'&&e.pointerType==='touch')return;releaseCandidateContact(e);},true);
for(const event of ['touchend','touchcancel'])document.addEventListener(event,()=>{if(candidateContact!==null)releaseCandidateContact({pointerId:candidateContact});},true);
window.addEventListener('blur',()=>{candidateContact=null;clearItemHold();if(candidateRenderPending){candidateRenderPending=false;render();}else syncClipboardReference();});
keyboard.addEventListener('pointerdown',e=>{
  if(e.button!==0 || gesture) return;
  consumedClick=null;
  const row=e.target.closest('.candidate-visible');
  if(row && e.pointerType==='mouse') {
    e.preventDefault();if(e.isTrusted)keyboard.setPointerCapture(e.pointerId);
    row.classList.add('candidate-dragging');
    gesture={id:e.pointerId,action:'candidate-scroll',x:e.clientX,scroll:row.scrollLeft,row,target:row,dragged:false};
    return;
  }
  const target=e.target.closest('[data-action]'); if(!target) return;
  if(target.dataset.action==='split-word'&&clipboardSplit){
    e.preventDefault();keyboard.setPointerCapture(e.pointerId);
    const start=Number(target.dataset.value),base=[...clipboardSplit.selected];
    gesture={id:e.pointerId,action:'split-drag',target,session:clipboardSplit,start,base,removing:base.includes(start),x:e.clientX,y:e.clientY,lastX:e.clientX,lastY:e.clientY,moved:false};
    return;
  }
  if(clipboardSplit&&target.dataset.action==='cursor-gesture')return;
  if(target.dataset.action==='cursor-gesture' && !state.voice) {
    learningChain=[];
    if(state.selectionActions&&!target.closest('.selection-editor'))return;
    if(target.dataset.track==='mini'&&!target.dataset.direction&&!target.classList.contains('selection-center'))return;
    e.preventDefault(); keyboard.setPointerCapture(e.pointerId);
    // 滑轨上开始移动前先收尾未提交的拼音组合，和原来长按进入时的处理一致。
    if(nativeIme&&state.comp)commit(state.comp.raw);
    englishPrefix='';state.comp=null;state.popup=null;
    // 摇杆半径在按下这一瞬间定死：原点始终是触控点，贴边按下时靠收缩半径保住向外行程，
    // 否则在滑轨最左端按下会向左永远推不到高档、向右推一半就饱和。
    const zoneRect=(target.closest('.selection-mini-track')||target).getBoundingClientRect(),kbRect=keyboard.getBoundingClientRect();
    const side=Math.min(e.clientX-zoneRect.left,zoneRect.right-e.clientX);
    const g={id:e.pointerId,action:'cursor-gesture',x:e.clientX,y:e.clientY,lastX:e.clientX,lastY:e.clientY,long:false,selecting:false,moved:false,selectionMoved:false,target,joy:{...joystickRadii(side,e.clientY-kbRect.top,kbRect.bottom-e.clientY),originX:e.clientX,originY:e.clientY,level:0,rate:0,raf:0,ts:0}};
    gesture=g;
    state.cursorDrag={origin:nativeIme&&!localInput?nativeCursor.position:editor.selectionEnd,selecting:false};
    g.mini=target.dataset.track==='mini';
    g.tapDirection=target.dataset.direction;
    if(g.mini&&!g.tapDirection)g.padCenter=target.closest('.selection-mini-track').querySelector('.selection-center').getBoundingClientRect();
    // 主轨只保留左右：短按落在中线哪一侧，就往那一侧精调一个字（与编辑框箭头同一套 selectionStep）。
    if(!g.mini)g.tapSide=e.clientX<(zoneRect.left+zoneRect.right)/2?-1:1;
    // 两条滑轨只有尺寸和位置不同：行为完全由「当前是否已锁定」决定。
    const locked=lockedNow();
    cursorFeedback={clientX:e.clientX,visible:true,selecting:locked,mini:g.mini};
    if(nativeIme&&!localInput){nativeIme.cursorPosition?.();nativeIme.gestureStart?.(locked);}
    else {editor.focus({preventScroll:true});g.head=editor.selectionDirection==='backward'?editor.selectionStart:editor.selectionEnd;primeSelectionGeometry(g,g.head);}
    if(locked){
      // 已锁定：拖动只调整终点；面板被收起过就顺手再呼出，状态与收起前一致。
      state.selectionActions=true;
      beginSelectionAdjust(g);
    }
    // 未锁定：手指一碰滑轨就进入移动模式（不再要求先长按），按住不动约 350ms 才标定起点。
    if(!g.tapDirection)g.timer=setTimeout(()=>{
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
  // 面板里的删除钮不在 .key 之列，但要和主键盘删除键共用同一套按住/连删手势，所以一并放行。
  const panelDelete=target.classList.contains('selection-delete');
  if(!target.classList.contains('key')&&!panelDelete) return;
  e.preventDefault();
  if(state.voice) return;
  keyboard.setPointerCapture(e.pointerId);
  const rect=target.getBoundingClientRect(), kbRect=keyboard.getBoundingClientRect();
  const g={id:e.pointerId,action:target.dataset.action,value:target.dataset.value,y:e.clientY,long:false,cancel:false,hint:target.querySelector('.hint')?.textContent,target,panelDelete}; gesture=g;
  clearKeyPress();
  target.classList.add('pressed');
  if(g.action==='delete'){g.immediate=true;deleteText(panelDelete);}
  g.timer=setTimeout(()=>{
    if(gesture!==g) return;
    if(g.action==='space') { g.long=true; startVoice(); }
    else if(g.action==='delete') { g.long=true;scheduleRepeat(g,0); }
    else if(g.action==='emoji') { g.long=true;g.repeatTimer=setTimeout(()=>emitEmojiLongPress(g),320); }
    else if(g.action==='letter') {
      // 长按浮层：固定「大写 / 键帽提示 / 小写」三档，首次出现就选中中间的提示项，
      // 浮层位置让中间档压住触点（边缘按键防越界被顶回键盘内时允许偏移）。
      const options=holdOptions(g.hint,g.value),middle=holdMiddle(g.hint,options);
      if(options.length<2){g.long=true;render();return;}
      const size=holdPopupGeometry(options.length,middle);
      const x=Math.max(5,Math.min(kbRect.width-size.width-5,rect.left-kbRect.left+rect.width/2-size.middle));
      g.long=true;g.originX=e.clientX;
      const choice=holdChoiceAt((g.lastX??g.originX)-g.originX,middle,middle,options.length);
      g.choice=choice;
      state.popup={type:'key',x,y:Math.max(0,rect.top-kbRect.top-52),options,middle,choice};
      render();
    }
    else if(g.action==='nine'||g.action==='nine-split'){g.long=true;insert(g.value);render();}
  },g.action==='space'?300:420);
});
keyboard.addEventListener('pointermove',e=>{
  if(!gesture || gesture.id!==e.pointerId) return;
  if(gesture.action==='split-drag'){
    const g=gesture;g.lastX=e.clientX;g.lastY=e.clientY;
    if(!g.moved&&Math.hypot(e.clientX-g.x,e.clientY-g.y)>6){g.moved=true;moveSplitDrag(g);}
    return;
  }
  if(gesture.action==='letter'){gesture.lastX=e.clientX;gesture.lastY=e.clientY;}
  // 长按浮层：只按「相对落点的水平位移」判定档位；浮层偏移、重绘或动画都不影响选择。
  if(gesture.long&&gesture.action==='letter'&&state.popup?.type==='key'){
    const choice=holdChoiceAt(e.clientX-gesture.originX,gesture.choice,state.popup.middle,state.popup.options.length);
    if(choice!==gesture.choice){gesture.choice=choice;state.popup.choice=choice;paintHoldChoice(choice);}
    return;
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
    if(gesture.tapDirection){if(Math.hypot(delta,e.clientY-gesture.y)>=8)gesture.moved=true;return;}
    if(gesture.mini&&!gesture.moved){
      if(Math.hypot(delta,e.clientY-gesture.y)<8)return;
      gesture.moved=true;clearTimeout(gesture.timer);
    }
    if(!gesture.selecting&&!gesture.moved&&Math.hypot(delta,e.clientY-gesture.y)>=cursorStep){gesture.moved=true;clearTimeout(gesture.timer);}
    if(gesture.selecting){
      const dx=e.clientX-gesture.x,dy=e.clientY-gesture.y;
      if(!gesture.selectionMoved&&Math.hypot(dx,dy)<5)return;
      gesture.selectionMoved=true;clearTimeout(gesture.timer);
      // 点按与拖动分开，转向后只输出新八方向。
      dragSelection(gesture,e.clientX-previousX,e.clientY-previousY);syncSelectionFeedback();return;
    }
    // 未锁定时同样支持八方向移动光标。
    dragSelection(gesture,e.clientX-previousX,e.clientY-previousY);
    return;
  }
  // 上滑取消：改成"跟手"——只改类名与 CSS 变量，走 render 会把动画打断。
  if(gesture.long && gesture.action==='space' && state.voice) { syncVoiceDrag(gesture,e.clientY); }
});
// 长按连删：对齐官方手感 —— 按住后先删一个，随后连续删除并逐渐加快。
// 首个间隔约 110ms，每个 tick 缩短 8ms，最快 35ms 封底。
function scheduleRepeat(g,step){
  g.repeatTimer=setTimeout(()=>{
    if(gesture!==g)return;
    // 面板内的连删：面板在按住期间被收起（点框外、改正文、宿主刷新选区）就立刻停手，不再继续删。
    if(g.panelDelete&&!state.selectionActions)return;
    deleteText(Boolean(g.panelDelete));
    scheduleRepeat(g,step+1);
  },repeatDelay(step));
}
function endGesture(e,cancelled=false) {
  const g=gesture; if(!g || g.id!==e.pointerId) return;
  if(g.action==='split-drag'&&g.moved&&!cancelled){
    cancelAnimationFrame(g.raf);moveSplitDrag(g,performance.now(),false);
    if(gesture!==g)return;
  }
  gesture=null; clearTimeout(g.timer); clearTimeout(g.repeatTimer); clearKeyPress();
  if(g.raf)cancelAnimationFrame(g.raf);
  if(g.joy?.raf){cancelAnimationFrame(g.joy.raf);g.joy.raf=0;g.joy.rate=0;}
  consumedClick={id:g.id,target:g.target,action:g.action,x:e.clientX,y:e.clientY,until:Date.now()+500};
  if(keyboard.hasPointerCapture(e.pointerId)) keyboard.releasePointerCapture(e.pointerId);
  if(g.action==='split-drag'){
    if(!cancelled&&!g.moved&&clipboardSplit===g.session)activate('split-word',String(g.start));
    return;
  }
  if(g.action==='candidate-scroll') {
    g.row.classList.remove('candidate-dragging');
    if(itemHold?.fired)return;
    if(g.dragged || cancelled)candidateSuppressUntil=Date.now()+350;
    else {const target=document.elementFromPoint(e.clientX,e.clientY)?.closest('.candidate-visible button');if(target && g.row.contains(target))activate(target.dataset.action,target.dataset.value);}
    return;
  }
  if(g.action==='cursor-gesture') {
    if(nativeIme&&!localInput)nativeIme.gestureEnd?.();
    state.cursorDrag=null;
    if(!cancelled&&g.tapDirection&&!g.moved&&!g.selectionMoved&&!g.long)selectionStep(...g.tapDirection.split(',').map(Number));
    // 主轨短按＝精调一个字：点中线左侧左移、右侧右移，免得为了挪一格还得拖一小段。
    if(!cancelled&&g.tapSide&&!g.moved&&!g.selectionMoved&&!g.long)selectionStep(g.tapSide,0);
    if(!cancelled&&g.padCenter&&!g.moved&&!g.selectionMoved&&!g.long)markSelectionOrigin();
    // 不改 state.selectionActions：面板只由「点击编辑框外」或改正文收起来。
    render();fadeCursorFeedback();return;
  }
  if(!cancelled&&!(g.long&&g.action==='space')&&Number.isFinite(e.clientX)&&Number.isFinite(e.clientY)){
    const inside=element=>{if(!element)return false;const r=element.getBoundingClientRect();return e.clientX>=r.left&&e.clientX<=r.right&&e.clientY>=r.top&&e.clientY<=r.bottom;};
    if(!inside(keyboard)&&!inside(keyboard.querySelector('.key-popup')))cancelled=true;
  }
  if(cancelled) { if(state.voice) cancelVoice(); state.popup=null; render(); return; }
  if(g.long && g.action==='space') finishVoice(g.cancel);
  else if(g.long && g.action==='letter')activate('long-insert',state.popup?.type==='key'?state.popup.options[state.popup.choice??0]:g.hint||g.value);
  else if(!g.long && !g.immediate) activate(g.hint && g.y-e.clientY>35?'insert':g.action,g.hint && g.y-e.clientY>35?g.hint:g.value);
}
keyboard.addEventListener('pointerup',e=>endGesture(e));
keyboard.addEventListener('pointercancel',e=>endGesture(e,true));
// 兜底自愈：WebView 失焦、页面转后台或指针捕获被抢走时，pointerup 可能永远不来。
// 残留的按压态与被卡住的 gesture 都要在这一层清掉，否则那一下的颜色会留在屏幕上、
// 并且之后所有按键都按不动。
function dropGesture(){if(!gesture)return;if(['cursor-gesture','split-drag'].includes(gesture.action)){endGesture({pointerId:gesture.id},true);return;}if(gesture.action==='candidate-scroll'){gesture.row.classList.remove('candidate-dragging');clearItemHold();}consumedClick={id:gesture.id,until:Date.now()+500};clearTimeout(gesture.timer);clearTimeout(gesture.repeatTimer);gesture=null;clearKeyPress();if(state.popup?.type==='key'){state.popup=null;render();}}
keyboard.addEventListener('lostpointercapture',e=>{if(gesture&&gesture.id===e.pointerId)dropGesture();});
// 失焦/转后台只清视觉，不动正在进行的语音或光标手势（它们靠自己的 pointerup 收尾）。
window.addEventListener('blur',()=>{clearKeyPress();if(gesture&&!(gesture.long&&gesture.action==='space'))dropGesture();});
keyboard.addEventListener('lostpointercapture',e=>{if(gesture?.action==='cursor-gesture')endGesture(e,true);});
// 转后台：清按压态，并收掉长按浮层——浮层留在屏幕上会在切回时被误当成一次待确认的选择。
document.addEventListener('visibilitychange',()=>{if(!document.hidden)return;clearKeyPress();if(gesture?.long&&gesture.action==='letter')dropGesture();else if(state.popup?.type==='key'){state.popup=null;render();}});
keyboard.addEventListener('click',e=>{
  const pointerClick=e.detail!==0||Boolean(e.pointerType);
  if(pointerClick&&consumedClick&&Date.now()<consumedClick.until&&(e.pointerId===undefined||e.pointerId===consumedClick.id)&&(consumedClick.action!=='cursor-gesture'||!consumedClick.target||e.target===keyboard||e.target===consumedClick.target||consumedClick.target.contains(e.target)||Math.hypot(e.clientX-consumedClick.x,e.clientY-consumedClick.y)<2)){consumedClick=null;e.preventDefault();return;}
  const target=e.target.closest('[data-action]'); if(!target) return;
  if(target.tagName==='BUTTON'&&target.disabled)return;
  if(target.dataset.direction){if(!pointerClick)selectionStep(...target.dataset.direction.split(',').map(Number));return;}
  if(target.closest('.candidate-visible') && pointerClick && (e.pointerType==='mouse'||Date.now()<candidateSuppressUntil))return;
  if(target.dataset.action==='paste'&&Date.now()<clipSuppressUntil)return;
  if(state.toolbarSort&&!['toolbar-sort-done','toolbar-custom-remove'].includes(target.dataset.action)){e.preventDefault();return;}
  if(target.classList.contains('key') && pointerClick) return;
  activate(target.dataset.action,target.dataset.value);
});
keyboard.addEventListener('contextmenu',e=>e.preventDefault());
keyboard.addEventListener('scroll',e=>{if(e.target.matches('.candidate-grid')){const grid=e.target;if(grid.scrollTop+grid.clientHeight>=grid.scrollHeight-grid.clientHeight)appendCandidates();updateCandidatePages();}else if(referencePopup&&e.target.matches('.candidate-visible'))closeReferencePopup();},true);
// 表情长按连发 / 剪贴板引用长按编辑：滑动即取消，普通候选不自动收藏。
function emojiEmit(value){
  insert(value);
  if(Object.values(emojiGroups).some(group=>group.includes(value)))recentEmoji=[value,...recentEmoji.filter(word=>word!==value)].slice(0,16);
  render();
}
function clearItemHold(){if(!itemHold)return;clearTimeout(itemHold.timer);clearInterval(itemHold.repeatTimer);itemHold=null;}
keyboard.addEventListener('pointerdown',e=>{
  if(e.button!==0)return;
  // 鼠标在候选栏上会先起一个拖动滚动的手势，按住不动时仍然允许长按候选词。
  if(gesture&&gesture.action!=='candidate-scroll')return;
  const target=e.target.closest('.emoji-hold,[data-action="clipboard-result"]');if(!target||target.disabled)return;
  clearItemHold();
  const hold={id:e.pointerId,x:e.clientX,y:e.clientY,target,kind:target.dataset.action.startsWith('clipboard-')?'reference':'emoji',value:target.dataset.value};
  itemHold=hold;
  hold.timer=setTimeout(()=>{
    if(itemHold!==hold)return;
    hold.fired=true;hold.ready=true;
    consumedClick={id:hold.id,until:Date.now()+700};
    if(hold.kind==='emoji'){
      emojiEmit(hold.value);
      hold.repeatTimer=setInterval(()=>{if(itemHold!==hold||state.voice){clearInterval(hold.repeatTimer);return;}emojiEmit(hold.value);},220);
    }else openClipboardEditor(hold.value);
  },hold.kind==='reference'?550:340);
});
keyboard.addEventListener('pointermove',e=>{if(itemHold&&e.pointerId===itemHold.id&&Math.hypot(e.clientX-itemHold.x,e.clientY-itemHold.y)>8){if(itemHold.kind==='reference')consumedClick={id:e.pointerId,until:Date.now()+700};clearItemHold();}});
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
keyboard.addEventListener('keydown',e=>{
  if(!e.target.closest('.clip-management-menu'))return;
  if(e.key==='Tab'){closeClipMenu();return;}
  if(!['ArrowDown','ArrowUp','Home','End'].includes(e.key))return;
  e.preventDefault();const items=[...keyboard.querySelectorAll('.clip-management-menu button')],index=items.indexOf(document.activeElement);
  items[e.key==='Home'?0:e.key==='End'?items.length-1:(index+(e.key==='ArrowDown'?1:-1)+items.length)%items.length]?.focus({preventScroll:true});
});
function searchEmoji(input){emojiQuery=input.value;const cursor=input.selectionStart;render();const next=keyboard.querySelector('.emoji-search-input');next.focus();next.setSelectionRange(cursor,cursor);}
keyboard.addEventListener('input',e=>{if(e.target.matches('.emoji-search-input')&&!e.isComposing)searchEmoji(e.target);});
keyboard.addEventListener('compositionend',e=>{if(e.target.matches('.emoji-search-input'))searchEmoji(e.target);});
let clipSearchTouch=null;
localPanel.addEventListener('pointerdown',e=>{if(!clipSearch)return;if(e.target.closest('.clip-search-result'))clipSearchTouch={id:e.pointerId,x:e.clientX,y:e.clientY,moved:false};else if(e.target.closest('button'))e.preventDefault();});
localPanel.addEventListener('pointermove',e=>{if(clipSearchTouch?.id===e.pointerId&&Math.hypot(e.clientX-clipSearchTouch.x,e.clientY-clipSearchTouch.y)>8)clipSearchTouch.moved=true;});
localPanel.addEventListener('pointercancel',()=>{if(clipSearchTouch)clipSearchTouch.moved=true;});
localPanel.addEventListener('click',e=>{if(!clipSearch)return;const button=e.target.closest('[data-action]');if(!button)return;e.preventDefault();if(button.classList.contains('clip-search-result')&&clipSearchTouch?.moved)return;activate(button.dataset.action,button.dataset.value);});
clipSearchInput.addEventListener('pointerdown',e=>{if(clipSearch?.target==='host'){setClipSearchTarget('search');e.stopPropagation();}});
clipSearchInput.addEventListener('focus',()=>{if(!clipSearch)return;setClipSearchTarget('search');if(!clipSearch.keys){clipSearch.keys=true;state.page='letters';render();}});
hostEditor.addEventListener('pointerdown',()=>{if(clipSearch)setClipSearchTarget('host',true);});
hostEditor.addEventListener('focus',()=>{if(clipSearch)setClipSearchTarget('host');});
keyboard.addEventListener('keydown',e=>{
  if(!e.target.closest('.cursor-zone') || !['ArrowLeft','ArrowRight','Home','End'].includes(e.key))return;
  e.preventDefault();if(state.comp)commit(state.comp.raw);englishPrefix='';
  if(nativeIme&&!localInput){nativeIme.moveCursor(e.key==='Home'?-100:e.key==='End'?100:e.key==='ArrowLeft'?-1:1);return;}
  const position=e.key==='Home'?0:e.key==='End'?editor.value.length:moveCursor(editor.value,editor.selectionEnd,e.key==='ArrowLeft'?-1:1);
  editor.setSelectionRange(position,position);render();keyboard.querySelector('.cursor-zone').focus({preventScroll:true});
});
keyboard.addEventListener('pointerdown',e=>{ if(e.target.closest('button') && !e.target.closest('.candidate-visible,.candidate-grid,.clip-list,.clipboard-results,.symbol-grid,.number-strip,.layout-grid,.emoji-body')) e.preventDefault(); });
let editorHold=null;
document.addEventListener('pointerdown',e=>{
  if(e.target!==editor||nativeIme&&!localInput||e.button!==0)return;
  if(selectionLocked||state.selectionActions){clearSelectionLock();render();}
  clearTimeout(editorHold?.timer);const hold={id:e.pointerId,x:e.clientX,y:e.clientY};editorHold=hold;
  hold.timer=setTimeout(()=>{if(editorHold!==hold)return;const head=editor.selectionDirection==='backward'?editor.selectionStart:editor.selectionEnd;selectionLocked=true;lockedSelection={editor,text:editor.value,anchor:head};localSelection={editor,text:editor.value,anchor:head,head};editor.setSelectionRange(head,head);selectedCount=0;state.selectionActions=true;render();},cursorHoldDelay);
});
for(const event of ['pointerup','pointercancel','pointermove'])document.addEventListener(event,e=>{if(editorHold&&e.pointerId===editorHold.id&&(event!=='pointermove'||Math.hypot(e.clientX-editorHold.x,e.clientY-editorHold.y)>8)){clearTimeout(editorHold.timer);editorHold=null;}});
document.addEventListener('visibilitychange',()=>{ if(document.hidden) {clearTimeout(editorHold?.timer);editorHold=null;if(state.voice)preserveVoice(state.voice);if(gesture)endGesture({pointerId:gesture.id},true); } });
document.addEventListener('compositionstart',e=>{if(e.target!==editor)return;nativeComposition=true;state.comp=null;render();});
document.addEventListener('compositionend',e=>{if(e.target!==editor)return;nativeComposition=false;state.comp=null;render();});
document.addEventListener('input',e=>{
  if(e.target===$('snippet-content'))$('snippet-split').disabled=Boolean(state.voice)||!e.target.value.trim();
  if(e.target!==editor)return;
  if(clipboardSplit)endClipboardSplit();
  state.comp=null;context='';learningChain=[];associatedWords=[];
  if(!localInput&&!nativeComposition && state.language==='zh' && e.inputType!=='insertFromPaste'){
    const raw=editor.value.slice(0,editor.selectionStart).match(/[a-z']+$/i)?.[0];
    if(raw)state.comp={start:editor.selectionStart-raw.length,raw:raw.toLowerCase()};
  }
  render();
});
document.addEventListener('keydown',e=>{
  if(e.target!==editor)return;
  if(clipboardSplit)endClipboardSplit();
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
document.addEventListener('pointerdown',e=>{if(e.target!==editor)return;if(clipboardSplit)endClipboardSplit();if(localInput&&state.voice)preserveVoice(state.voice);state.comp=null;englishPrefix='';context='';learningChain=[];associatedWords=[];render();});
$('host-mic').innerHTML=icon('mic'); $('send').innerHTML=icon('voice');
$('host-plus').onclick=()=>toast('这是输入框周边的参考界面，可直接试用下方键盘。');
$('host-mic').onclick=()=>toast('这是聊天应用的语音按钮。输入法语音请长按下方空格。');
$('send').onclick=()=>{ if(!editor.value.trim()) { toast('先输入一段试用文字。'); return; } if(state.voice) cancelVoice(); const message=document.createElement('div');message.className='message';message.textContent=editor.value;$('sent-messages').append(message);state.comp=null;applyText({text:'',cursor:0});render(); };
$('reopen').onclick=()=>{state.hidden=false;render();};
$('clear-input').onclick=()=>{ if(state.voice) cancelVoice();state.comp=null;applyText({text:'',cursor:0});$('sent-messages').replaceChildren();render(); };
document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>showTab(b.dataset.tab));
const quick=[['letters','全键盘'],['numbers','数字'],['symbols','符号'],['clipboard','剪贴板'],['layouts','键盘选择'],['emoji','表情']];
$('quick-pages').innerHTML=quick.map(([id,label])=>button(label,'quick',id)).join('');
$('quick-pages').onclick=e=>{const b=e.target.closest('button');if(b){if(b.dataset.value==='letters'){state.layout='26';state.language='zh';}showPage(b.dataset.value);}};
$('compare-toggle').onclick=()=>{ const show=$('comparison').hidden; $('comparison').hidden=!show;$('compare-toggle').setAttribute('aria-pressed',show); };
function syncControls() {
  $('clipboard-toggles').innerHTML=[['clipboardHistory','保存剪贴板记录','保留复制内容，方便找回'],['clipboardMatch','输入时引用剪贴板','拼音匹配时显示可引用的内容']].map(([id,label,hint])=>`<label class="settings-row"><span><b>${label}</b><small>${hint}</small></span><input type="checkbox" role="switch" data-setting="${id}" ${settings[id]?'checked':''}></label>`).join('');
  $('voice-text').value=settings.voiceText;
}
$('clipboard-toggles').onchange=e=>applyToggleChange(e.target);
function applyToggleChange(input){
  if(input.dataset.setting)settings[input.dataset.setting]=input.checked;
  persist();render();feedback('功能设置已保存，键盘尺寸保持固定。');
}
$('voice-text').oninput=e=>{if(!e.target.value)return;settings.voiceText=e.target.value;persist();render();};
function downloadLexicon(text,name,type){const url=URL.createObjectURL(new Blob([text],{type}));const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
$('personal-lexicon').onclick=()=>{
  if(nativeIme?.openLexicon){nativeIme.openLexicon();return;}
  $('lexicon-actions').hidden=!$('lexicon-actions').hidden;
};
$('lexicon-backup').onclick=async()=>{try{downloadLexicon(await personalLexicon.backup(),'voxfix-personal-lexicon.json','application/json');toast('已导出完整个人词库备份。');}catch(error){toast(error.message);}};
$('lexicon-text').onclick=async()=>{try{downloadLexicon(await personalLexicon.tsv(),'voxfix-personal-lexicon.tsv','text/tab-separated-values');toast('已导出完整拼音词条；仅有简拼的记录保留在完整备份中。');}catch(error){toast(error.message);}};
$('lexicon-import').onclick=()=>$('lexicon-file').click();
$('lexicon-file').onchange=async e=>{const file=e.target.files[0];if(!file)return;try{if(file.size>lexiconMaxBytes)throw Error('词库文件超过32MiB，请分批导入');await personalLexicon.import(await file.text());learningChain=[];refreshAssociations();render();toast('个人词库已合并，重复导入不累加词频。');}catch(error){toast('词库未导入：'+error.message);}finally{e.target.value='';}};
window.addEventListener('focus',()=>{if(personalLexicon.ready){refreshAssociations();render();}});
$('export').onclick=()=>{ const file=new Blob([JSON.stringify({schema:1,...settings},null,2)],{type:'application/json'});const url=URL.createObjectURL(file);const a=document.createElement('a');a.href=url;a.download='voxfix-settings.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);feedback('已导出功能设置。'); };
$('import').onclick=()=>$('config-file').click();
$('config-file').onchange=async e=>{ const file=e.target.files[0];if(!file)return;try{if(file.size>50000)throw Error('配置文件过大');const value=JSON.parse(await file.text());if(value.schema!==1)throw Error('不支持的配置版本');const next=settingsFrom(value);if(state.voice)cancelVoice();settings=next;syncControls();persist();render();}catch(error){toast(error.message||'无法读取布局配置');}finally{e.target.value='';} };
$('reset').onclick=()=>{ if(state.voice)cancelVoice();settings=structuredClone(defaults);state.night=false;state.hidden=false;syncControls();persist();render();feedback('已恢复默认功能设置，键盘尺寸保持固定，试用文字保留。'); };
$('snippet-cancel').onclick=()=>{if(quoteEditor){finishClipboardEditor(false);return;}if(nativeIme){closeLocalEditor();state.page='clipboard';render();}else snippetDialog.close();};
$('snippet-content').addEventListener('input',()=>paintReferenceHighlight());
$('snippet-content').addEventListener('scroll',()=>{const layer=$('snippet-content').parentElement.querySelector('.snippet-highlight');if(layer){layer.scrollTop=$('snippet-content').scrollTop;layer.scrollLeft=$('snippet-content').scrollLeft;}});
new ResizeObserver(()=>paintReferenceHighlight()).observe($('snippet-content'));
new ResizeObserver(()=>paintClipboardReference()).observe(keyboard);
$('snippet-split').onclick=()=>{
  if(state.voice)return;
  if(localInput===$('snippet-content')&&state.comp){commit(currentCandidates()[0]||state.comp.raw);if(state.comp)commit(state.comp.raw);}
  openClipboardSplit($('snippet-content').value,true);
};
snippetDialog.addEventListener('cancel',e=>{if(quoteEditor){e.preventDefault();finishClipboardEditor(false);}});
$('snippet-form').onsubmit=e=>{
  e.preventDefault();
  if(quoteEditor){finishClipboardEditor(true);return;}
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
window.addEventListener('storage',e=>{if(e.key!=='voxfix-snippets-v1'||!e.newValue)return;try{const saved=JSON.parse(e.newValue);clips=validateSnippets(saved.clips);phraseGroups=migratePhraseGroups(saved.phrases);snippetUndo=null;state.clipGroup=Math.min(state.clipGroup,phraseGroups.length-1);updatePhraseOptions();if(state.page==='clipboard')render();else syncClipboardReference();}catch{/* Invalid cross-tab data is ignored. */}});
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
globalThis.voxfixHostFocus=(selectionChanged=false)=>{if(clipSearch)setClipSearchTarget('host',Boolean(selectionChanged));};
if(nativeIme){
  globalThis.voxfixSplitInvalidated=()=>{if(clipboardSplit){clipboardSplit=null;splitRequest++;state.page='clipboard';render();toast('输入位置已变化，本次拆词已结束');}};
  globalThis.voxfixPinyinReady=(failed=false)=>{if(failed)loadFullPinyin().catch(()=>feedback('拼音词库未能加载。'));else if(state.comp)render();};
  globalThis.voxfixCursor=(position,total)=>{nativeCursor={position,total};const zone=keyboard.querySelector('.cursor-zone');if(zone){zone.setAttribute('aria-valuemax',total);zone.setAttribute('aria-valuenow',position);}};
  globalThis.voxfixSelection=(start,end,count=-1)=>{if(localInput)return;selectedCount=start===end?0:count;syncSelectionFeedback();if((start<0||end<0)&&(state.selectionActions||selectionLocked)){clearSelectionLock();render();}};
  globalThis.voxfixSelectionReleased=()=>{
    if(localInput)return;
    if(gesture?.action==='cursor-gesture')dropGesture();else nativeIme.gestureEnd?.();
    clearSelectionLock();state.cursorDrag=null;cursorFeedback.visible=false;render();
  };
  // 宿主自己长按选中的文字：那里本来就有固定起点，按「已锁定」处理。
  globalThis.voxfixSelectionOrigin=()=>{if(localInput||state.voice||gesture||clipboardSplit)return;kbArrivedAt=performance.now();selectionLocked=true;state.selectionActions=true;render();};
  globalThis.voxfixSelectionUnavailable=()=>toast('当前输入框暂不提供段落文本，请使用滑轨框选。');
  globalThis.voxfixSelectionBlocked=()=>{if(gesture?.selecting){gesture.blockedDirection=gesture.direction?.slice();haltJoystick(gesture);}};
  globalThis.voxfixReset=()=>{endClipboardSplit();if(gesture)haltJoystick(gesture);releaseSelectionLock();state.selectionActions=false;state.cursorDrag=null;cursorFeedback.visible=false;closeLocalEditor(true);quoteEditor=null;candidateContact=null;clearTimeout(referenceTimer);referenceWanted='' ;clipReference='';latestClipboard='';stopVoiceLoop();englishPrefix='';context='';learningChain=[];associatedWords=[];clearTimeout(gesture?.timer);clearTimeout(gesture?.repeatTimer);gesture=null;itemHold=null;state.voice=null;state.comp=null;state.popup=null;state.locked=false;state.hidden=false;state.page='letters';state.clipManage=false;state.clipSelected=[];state.clipConfirm=null;state.clipCandidate='';state.toolbarSort=false;state.clipGroupMenu=null;applyText({text:'',cursor:0});render();};
  globalThis.voxfixClearComposition=(keepEnglish=false)=>{if(localInput||keepEnglish||clipboardSplit)return;const changed=Boolean(englishPrefix||state.comp||associatedWords.length||state.page==='candidates');englishPrefix='';context='';learningChain=[];associatedWords=[];state.comp=null;if(state.page==='candidates')state.page='letters';applyText({text:'',cursor:0});if(changed)render();};
  // 从 inputType 推导「键盘形态」：数字/电话 → 数字页；文本密码/邮箱/网址等 → 英文；其余 → 中文字母。
  // 用于「同一编辑器重启时只有形态真变才重置」——支付宝/银行密码框每输入一个字符都会带 flag 位
  // 抖动重启一次，若按原始 inputType 数值判等，会把数字页 reset 成字母页再切回来，表现为「按一个数字弹一下」。
  const inputForm=type=>{const kind=type&15,variation=type&4080;if(kind===2||kind===3)return 'numbers';if(kind===1&&[16,32,128,144,224].includes(variation))return 'en';return 'letters';};
  const isPrivateField=type=>{const kind=type&15,variation=type&4080;return kind===1&&[128,144,224].includes(variation)||kind===2&&variation===16;};
  globalThis.voxfixInputType=type=>{
    if(clipSearch)closeLocalEditor();
    // 换了输入框，原来那份起点已经不指向同一个地方，锁定安全清理。
    clearSelectionLock();
    const kind=type&15,variation=type&4080;
    privateField=isPrivateField(type);
    if(kind===2||kind===3)state.page='numbers';
    else if(kind===1&&[16,32,128,144,224].includes(variation)){fieldPreference??={language:state.language,layout:state.layout};state.language='en';state.layout='26';}
    else if(fieldPreference!==null){Object.assign(state,fieldPreference);fieldPreference=null;}
    render();
  };
  let inputType=null;
  globalThis.voxfixRedraw=()=>render();
  globalThis.voxfixStartInput=(type,restarting,options=0)=>{
    const wasSplit=Boolean(clipboardSplit);endClipboardSplit();
    const previous=enterLabel;noPersonalizedLearning=Boolean(options&0x1000000);
    enterLabel=options&0x40000000?'换行':({2:'前往',3:'搜索',4:'发送',5:'下一项',6:'完成',7:'上一项'}[options&255]||'换行');
    if(!restarting){globalThis.voxfixReset();globalThis.voxfixInputType(type);}
    else if(inputType!==null&&inputForm(inputType)!==inputForm(type)){globalThis.voxfixClearComposition();englishPrefix='';state.page='letters';globalThis.voxfixInputType(type);}
    else{
      const wasPrivate=privateField;privateField=isPrivateField(type);
      if(privateField&&!wasPrivate)globalThis.voxfixClearComposition();
      if(previous!==enterLabel||wasPrivate!==privateField)render();
    }
    inputType=type;
    if(wasSplit)render();
  };
  globalThis.voxfixVoiceDone=message=>{exitVoice();if(message)toast(message);};
  globalThis.voxfixVoiceListening=()=>{const session=state.voice;if(!session||!['loading','cancel'].includes(session.phase))return;session.micReady=true;session.phase=gesture?.cancel?'cancel':'listening';paintVoice();};
  globalThis.voxfixVoiceText=text=>{const session=state.voice;if(!session?.local||session.local!==editor)return;session.text=text;if(session.phase!=='cancel')applyText(voiceResult(session.snapshot,text));};
  globalThis.voxfixVoiceLevel=level=>setVoiceLevel(level/12);
  globalThis.voxfixClipboard=(text,signature=text,explicit=false)=>{
    latestClipboard=privateField?'':text;syncClipboardReference();
    if(privateField||!explicit&&signature===nativeClipSignature)return;
    const previous=clips,previousSignature=nativeClipSignature;
    nativeClipSignature=signature;
    if(text.trim()){
      if(settings.clipboardHistory||explicit)clips=addSnippet(clips,text);
      if(settings.clipboardCandidate)state.clipCandidate=text;
    }
    if((settings.clipboardHistory||explicit)&&!saveSnippets()){clips=previous;nativeClipSignature=previousSignature;}
    syncClipboardReference();
    if(state.page==='clipboard'||state.clipCandidate)render();
  };
  nativeIme.ready();
}
$('voice-hotwords').value=hotwords.join('\n');
$('voice-hotwords').onchange=()=>{const words=[...new Set($('voice-hotwords').value.split('\n').map(word=>word.trim()).filter(Boolean))];if(words.length>100||words.some(word=>!/^[\u3400-\u9fff]{1,30}$/.test(word))){toast('热词最多 100 条，每条 1–30 个汉字；请修改后再保存。');return;}hotwords=words;try{localStorage.setItem('voxfix-hotwords-v1',JSON.stringify(words));feedback('热词已保存，下次录音生效。');}catch{toast('热词仅在当前页面保留。');}};
