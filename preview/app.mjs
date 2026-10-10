import { defaults, toolbarItems, settingsFrom, replaceRange, erase, moveCursor, voiceResult, validateSnippets, addSnippet, validateLearning, learnWord, rankLearned } from './core.mjs';
import { LocalVoice, voiceStatus, enableMicrophone } from './voice.mjs';
import { loadPinyin, pinyinCandidates, nineCandidates, setPhraseOptions, phraseSearch } from './pinyin.mjs';
import { InkPad } from './ink.mjs';

const $ = id => document.getElementById(id);
const editor = $('editor'), keyboard = $('keyboard');
const nativeIme = globalThis.VoxFixIme;
if(nativeIme){document.documentElement.classList.add('native-ime');document.body.append($('phone'));}
const nativeSettings=nativeIme?document.createElement('dialog'):null;
if(nativeSettings){nativeSettings.className='snippet-dialog';const close=document.createElement('button');close.textContent='返回键盘';close.onclick=()=>nativeSettings.close();nativeSettings.append(close,$('custom-panel'));document.body.append(nativeSettings);}
let settings = structuredClone(defaults);
try { const stored = localStorage.getItem('voxfix-layout-v1'); if (stored) settings = settingsFrom(JSON.parse(stored)); } catch { /* An invalid saved layout falls back to the recording layout. */ }
const state = { page: 'letters', returnPage:'letters', symbolReturn:'letters', language: 'zh', shift: 0, symbol: '常用', locked: false, night: false, clipTab: 'clipboard', popup: null, comp: null, voice: null, hidden: false, emojiTab: 'Emoji', emojiCategory: '常用', layout: '26', voiceTimer: null, cursorDrag: null };
if(nativeIme)settings.rememberInput=false;
let clips = ['有没有免费的', 'https://example.com/演示链接', 'obsidian'];
let phrases = ['常用语示例', 'example@example.com', '稍后回复你'];
let nativeSystemClip='';
if(nativeIme){clips=[];phrases=[];}
try{const saved=JSON.parse(localStorage.getItem('voxfix-snippets-v1'));if(saved){clips=validateSnippets(saved.clips);phrases=validateSnippets(saved.phrases);}}catch{/* Keep malformed stored data untouched. */}
let snippetEdit=null,snippetUndo=null;
let clipMenu=null,clipHold=null,clipSuppressUntil=0,learning=[];
try{const saved=localStorage.getItem('voxfix-learning-v1');if(saved)learning=validateLearning(JSON.parse(saved));}catch{/* Keep invalid learning data untouched. */}
let inkPad=null,inkWords=[],handReady=false,lastPinyin='26';
let emojiQuery='',emojiSearchOpen=false;
let gesture = null, candidateSuppressUntil=0, toastTimer, speechReady = false, nativeComposition = false;
let polishReady=false,polishBusy=false,polishDraft=null,polishUndo=null;
let hotwords=[];
try{const saved=JSON.parse(localStorage.getItem('voxfix-hotwords-v1'));if(Array.isArray(saved)&&saved.length<=100&&saved.every(word=>typeof word==='string'&&/^[\u3400-\u9fff]{1,30}$/.test(word)))hotwords=saved;}catch{}
const icons = {
  menu: '<rect x="3" y="3" width="6" height="6" rx="1.5"/><rect x="14" y="3" width="6" height="6" rx="1.5"/><rect x="3" y="14" width="6" height="6" rx="1.5"/><rect x="14" y="14" width="6" height="6" rx="1.5"/>',
  layouts: '<path d="M3 18h18M4 5h1m4 0h1m4 0h1m4 0h1M4 9h1m4 0h1m4 0h1m4 0h1M4 13h1m4 0h1m4 0h1m4 0h1"/>',
  night: '<path d="M19 14A8 8 0 0 1 10 3a9 9 0 1 0 9 11Z"/>',
  clipboard: '<circle cx="6" cy="18" r="3"/><circle cx="18" cy="18" r="3"/><path d="m5 3 14 12M19 3 5 15"/>',
  copy: '<rect x="8" y="7" width="12" height="14" rx="2"/><path d="M15 7V3H3v14h5m3-6h6m-6 4h6"/>',
  emoji: '<circle cx="12" cy="12" r="9"/><path d="M8 15q4 4 8 0M8 9h.1M16 9h.1"/>',
  hide: '<path d="m5 9 7 7 7-7"/>', back: '<path d="M20 12H4m5-5-5 5 5 5"/>',
  delete: '<path d="m8 5-6 7 6 7h13V5Z"/><path d="m11 9 6 6m0-6-6 6"/>',
  enter: '<path d="M20 6v8H4m5-5-5 5 5 5"/>', space: '<path d="M3 14v4h18v-4"/>',
  mic: '<rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2m-7 9v3m-3 0h6"/>',
  keyboard: '<rect x="2" y="4" width="20" height="16" rx="3"/><path d="M6 8h.1M10 8h.1M14 8h.1M18 8h.1M6 12h.1M10 12h.1M14 12h.1M18 12h.1M7 16h10"/>',
  lock: '<rect x="5" y="10" width="14" height="12" rx="2"/><path d="M8 10V6a4 4 0 0 1 8 0v4"/>',
  trash: '<path d="M4 6h16M9 6V3h6v3m-9 0 1 15h10l1-15M10 10v7m4-7v7"/>',
  add: '<path d="M12 4v16M4 12h16"/>', settings: '<circle cx="12" cy="12" r="3"/><path d="m10 2-1 3-3 1-3-1-1 4 2 2v3l-2 2 2 4 3-1 3 1 1 3 4-1 1-3 3-1 3 1 1-4-2-2v-3l2-2-2-4-3 1-3-1-1-3Z"/>',
  send: '<path d="M12 20V5m-5 5 5-5 5 5"/>', voice: '<path d="M4 9v6m4-10v14m4-17v20m4-17v14m4-10v6"/>',
  shift: '<path d="m12 3-9 9h6v8h6v-8h6Z" fill="currentColor" stroke="none"/>', caps: '<path d="m12 2-9 9h6v6h6v-6h6ZM9 20h6" fill="currentColor"/>',
  search: '<circle cx="10" cy="10" r="6"/><path d="m15 15 6 6"/>',
  forward: '<path d="m9 5 7 7-7 7"/>',
};
function icon(name, cls = '') { return `<svg class="${cls}" viewBox="0 0 24 24" aria-hidden="true">${icons[name] || icons.menu}</svg>`; }
function esc(value) { return String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
function button(label, action, value = '', cls = '', body = '') {
  return `<button type="button" class="${cls}" data-action="${action}" data-value="${esc(value)}" aria-label="${esc(label)}">${body || esc(label)}</button>`;
}
function key(label, action = 'insert', value = label, cls = '', hint = '') {
  return button(label, action, value, `key ${cls}`, (hint ? `<span class="hint">${esc(hint)}</span>` : '') + esc(label));
}
function func(label, action, symbol = '', cls = '') { return button(label, action, '', `key function ${cls}`, symbol ? icon(symbol) : esc(label)); }
function spaceKey() { return button('空格，长按语音输入', 'space', '', 'key space', icon('mic', 'microphone') + icon('space')); }
function languageKey() { return button('切换中英文', 'language', '', 'key function', state.language === 'zh' ? '中<span class="lang-small">/英</span>' : '<span class="lang-small">中/</span>英'); }
function footer() {
  const position=editor.value.length ? editor.selectionEnd/editor.value.length*100 : 50;
  return `<div class="keyboard-footer">${settings.footerSwitcher ? button('底部输入法菜单', 'switcher', '', '', icon('keyboard')) : '<span class="empty"></span>'}<div class="cursor-zone" data-action="cursor-gesture" role="slider" tabindex="0" aria-label="底部光标移动，长按后左右拖动" aria-valuemin="0" aria-valuemax="${editor.value.length}" aria-valuenow="${editor.selectionEnd}"><span class="cursor-track" ${state.cursorDrag?'':'hidden'}><i style="left:${position}%"></i></span></div>${settings.footerClipboard ? button('底部剪贴板', 'clipboard', '', '', icon('copy')) : '<span class="empty"></span>'}</div>`;
}
function toolbar() {
  if (state.comp) return `<div class="candidate-bar">${candidateStrip(currentCandidates())}${button('更多候选', 'more-candidates', '', 'candidate-expand', icon(state.page==='candidates'?'hide':'forward'))}</div>`;
  return settings.toolbar.length ? `<div class="toolbar">${settings.toolbar.map(id => button(toolbarItems.find(([name]) => name === id)[1], id, '', '', icon(id))).join('')}</div>` : '';
}
function candidateStrip(words,action='candidate') {
  return `<div class="candidate-visible" tabindex="0" aria-label="候选字，左右滑动浏览" data-query="${esc(state.comp?.raw||words.join(','))}" style="--candidate-count:${settings.candidateCount};--candidate-font:${settings.candidateFont}px">${words.map(word=>button('候选：'+word,action,word,'',esc(word))).join('')}</div>`;
}
function currentCandidates() {
  if(!state.comp)return [];
  if(state.language==='en')return [...new Set([state.comp.raw,state.comp.raw.toLowerCase(),state.comp.raw.toUpperCase()])];
  let words=(state.layout==='9'?nineCandidates:pinyinCandidates)(state.comp.raw,state.page==='candidates'?2000:240);
  if(settings.rememberInput)words=rankLearned(words,learning,(state.layout==='9'?'9:':'p:')+state.comp.raw.toLowerCase());
  return words.length?words:[state.comp.raw];
}
function candidatePanel(){return `${toolbar()}<div class="candidate-spelling">${esc(state.comp?.raw||'')}</div><div class="candidate-grid" style="--candidate-font:${settings.candidateFont}px">${currentCandidates().map(word=>button('候选：'+word,'candidate',word,'',esc(word))).join('')}</div>${footer()}`;}
const upperHints = ['1','2','3','4','5','6','7','8','9','0'];
const middleHints = ['-','!','@','/','%','…','"','\'','?'];
const bottomHints = ['(',')',':','-','_',',','.'];
function letters() {
  const upper = state.language === 'zh' || state.shift > 0;
  const row = (letters, hints) => [...letters].map((letter, i) => key(upper ? letter : letter.toLowerCase(), 'letter', upper ? letter : letter.toLowerCase(), '', hints[i])).join('');
  const left = state.language === 'zh' ? func('分词', 'split', '', 'side') : `<button type="button" data-action="shift" data-value="" aria-label="英文大小写" aria-pressed="${Boolean(state.shift)}" class="key function side shift-key">${icon(state.shift?'caps':'shift')}</button>`;
  const comma=state.language==='en' && settings.comma==='，'?',':settings.comma;
  const period=state.language==='en' && settings.period==='。'?'.':settings.period;
  return `${toolbar()}<div class="key-rows"><div class="key-row">${row('QWERTYUIOP',upperHints)}</div><div class="key-row row-middle">${row('ASDFGHJKL',middleHints)}</div><div class="key-row row-third">${left}${row('ZXCVBNM',bottomHints)}${func('删除', 'delete', 'delete', 'side')}</div><div class="key-row">${func('符','symbols')}${func('123','numbers')}${key(comma)}${spaceKey()}${key(period)}${languageKey()}${func('换行','enter','enter','side')}</div></div>${footer()}`;
}
function nine() {
  const groups=['分词','ABC','DEF','GHI','JKL','MNO','PQRS','TUV','WXYZ'];
  const cells=groups.map((label,i)=>button(label,i===0?'nine-split':'nine',String(i+1),'key t9-letter',`<span class="hint">${i+1}</span><span>${esc(label)}</span>`));
  const clear=button('清除拼音','clear-pinyin','','key function',esc('清除')).replace('<button ',state.comp?'<button ':'<button disabled ');
  return `${toolbar()}<div class="t9-grid"><div class="t9-symbols">${['，','。','？','！','@','…','～','、','：','；'].map(s=>button(s,'insert',s)).join('')}</div>${cells[0]}${cells[1]}${cells[2]}${func('删除','delete','delete')}${cells[3]}${cells[4]}${cells[5]}${clear}${cells[6]}${cells[7]}${cells[8]}${key('0','insert','0','function')}${func('符','symbols')}${func('123','numbers')}${spaceKey()}${languageKey()}${func('换行','enter','enter')}</div>${footer()}`;
}
function numbers() {
  return `${toolbar()}<div class="number-body"><div class="number-column"><div class="number-strip">${['.','/','+','-','%','：','@','¥','='].map(s=>button(s,'insert',s)).join('')}</div><div class="key-row">${func('符','symbols')}</div></div><div class="number-main">${[[1,2,3],[4,5,6],[7,8,9]].map(row=>`<div class="key-row">${row.map(n=>key(n)).join('')}</div>`).join('')}<div class="key-row">${func('返回','letters')}${key('0')}${button('空格','space','','key function',icon('space'))}</div></div><div class="number-column"><div class="key-row">${func('删除','delete','delete')}</div><div class="key-row">${key('*','insert','*','function')}</div><div class="key-row">${key('#','insert','#','function')}</div><div class="key-row">${func('换行','enter','enter')}</div></div></div>${footer()}`;
}
const symbols = {
  '常用': ['、','-','。','+',':','!','#','=','_','()','/','*','（）','~','×','%','°','……','<>'],
  '中文': ['，','。','？','！','：','；','……','～','“”','“','”','（）','（','）','——','‘’','‘','’','《','》'],
  '英文': ['@qq.com','《','》','&','¥','@',',','“”','?',',',';','$','^',':-)','^0^','www.','.com'],
  '网址': ['.',':','/','@','-','_','www.','wap.','.com','.cn','.com.cn','.net','baidu.com','google.com.hk','taobao.com'],
};
function symbolPanel() {
  return `<div class="symbol-body"><div class="symbol-nav">${Object.keys(symbols).map(name => button(name, 'symbol-category', name, state.symbol === name ? 'selected' : '')).join('')}${button('锁定符号页','symbol-lock','',state.locked?'selected':'',icon('lock'))}${button('返回','symbol-back')}</div><div class="symbol-grid ${state.symbol==='网址'?'url':''}">${symbols[state.symbol].map(s=>button(s,'symbol',s,s.length>5?'wide':'')).join('')}</div></div>${footer()}`;
}
function clipboard() {
  const list = state.clipTab === 'clipboard' ? clips : phrases;
  return `<div class="panel-title">${button('返回键盘','return','', '',icon('hide'))}<div class="tabs-pill">${button('剪贴板','clip-tab','clipboard',state.clipTab==='clipboard'?'selected':'')}${button('常用语','clip-tab','phrases',state.clipTab==='phrases'?'selected':'')}</div>${state.clipTab==='clipboard'?button('剪贴板管理','clip-manage','','',icon('trash')):button('添加常用语','snippet-add','','',icon('add'))}</div><div class="clip-list" aria-label="长按内容或空白处管理">${list.length?list.map((text,i)=>button(text,'paste',text,'clip-text',esc(text)).replace('data-action="paste"',`data-action="paste" data-index="${i}"`)).join(''):'<div class="empty-state">长按空白处添加内容</div>'}</div>${footer()}${clipMenu?clipContextMenu():''}`;
}
function clipContextMenu(){
  const item=clipMenu.index>=0;
  const actions=item?[['context-edit','编辑'],['context-delete','删除'],...(state.clipTab==='clipboard'?[['context-favorite','设为常用语']]:[]),['context-copy','复制']]:[['snippet-add','添加内容'],...(state.clipTab==='clipboard'?[['system-paste','从系统剪贴板导入']]:[]),['clear-snippets','清空列表']];
  if(snippetUndo)actions.push(['undo-snippet','撤销删除']);
  return `<div class="clip-menu-backdrop" data-action="clip-close"><div class="clip-menu-sheet"><div class="clip-menu-title">${esc(item?clipMenu.text:'管理内容')}</div>${actions.map(([action,label])=>button(label,action)).join('')}${button('取消','clip-close','','clip-menu-cancel')}</div></div>`;
}
function openClipMenu(index=-1){const list=state.clipTab==='clipboard'?clips:phrases;clipMenu={index,text:index<0?'':list[index]};render();keyboard.querySelector('.clip-menu-sheet button')?.focus({preventScroll:true});}
function closeClipMenu(){clipMenu=null;render();}
function saveSnippets(){updatePhraseOptions();try{localStorage.setItem('voxfix-snippets-v1',JSON.stringify({clips,phrases}));return true;}catch{toast('当前浏览器无法保存，内容只保留到本页关闭。');return false;}}
function setSnippetList(list){if(state.clipTab==='clipboard')clips=list;else phrases=list;saveSnippets();}
function openSnippet(index=-1){const original=index<0?'':(state.clipTab==='clipboard'?clips:phrases)[index];snippetEdit={tab:state.clipTab,index,original};$('snippet-title').textContent=(index<0?'添加':'编辑')+(state.clipTab==='clipboard'?'剪贴板内容':'常用语');$('snippet-content').value=original;$('snippet-error').textContent='';$('snippet-dialog').showModal();$('snippet-content').focus();}
const layouts = [['9','9键拼音','拼'],['26','26键拼音','拼'],['fullhand','全屏手写','手']];
function layoutPanel() {
  return `<div class="panel-title"><span>键盘选择</span>${button('返回键盘','return','','',icon('back'))}</div><div class="layout-grid">${layouts.map(([id,label,glyph])=>button(label,'layout',id,state.layout===id?'selected':'',`<span class="layout-icon">${glyph}</span><span>${label}</span>`)).join('')}</div>${footer()}`;
}
const emojiGroups = {'常用':['😊','🍀','😂','⭐','🌸','🙄','😭','🌝'],'精选':['👍','❤️','🎉','🙏','💪','👌','🥳','👏'],'花样':['🌸','🌷','🌼','🌹','💐','🌻','🍃','🍀'],'黄脸':['😀','😁','😂','🥲','😊','😍','🤔','😭'],'动物':['🐱','🐶','🐰','🐼','🦊','🐸','🐻','🐵']};
const emojiNames={'😊':'微笑 开心 smile','🍀':'幸运 四叶草 clover','😂':'笑 哭 laugh','⭐':'星星 star','🌸':'花 flower','🙄':'白眼 无语','😭':'哭 伤心 cry','🌝':'月亮 moon','👍':'赞 支持 good','❤️':'爱 心 love','🎉':'庆祝 party','🙏':'谢谢 祈祷','💪':'加油 力量','👌':'好 ok','🥳':'庆祝 party','👏':'鼓掌','🐱':'猫 cat','🐶':'狗 dog','🐰':'兔 rabbit','🐼':'熊猫 panda','🦊':'狐狸 fox','🐸':'青蛙 frog','🐻':'熊 bear','🐵':'猴 monkey'};
function emojiPanel() {
  const tabs = ['Emoji','颜文字'];
  let body = '';
  if (state.emojiTab === 'Emoji'){const words=emojiQuery?[...new Set(Object.values(emojiGroups).flat())].filter(s=>(s+' '+(emojiNames[s]||'')).toLowerCase().includes(emojiQuery.toLowerCase())):emojiGroups[state.emojiCategory];body=`${emojiSearchOpen?`<input class="emoji-search-input" aria-label="搜索表情" value="${esc(emojiQuery)}" placeholder="输入笑、猫、爱等关键词">`:''}<div class="emoji-categories">${Object.keys(emojiGroups).map(s=>button(s,'emoji-category',s,state.emojiCategory===s?'selected':'')).join('')}</div><div class="emoji-grid">${words.map(s=>button(s,'insert',s)).join('')}${button('删除','delete','','',icon('delete'))}</div>${words.length?'':'<p class="emoji-placeholder">没有匹配的表情</p>'}`;}
  else if (state.emojiTab==='颜文字') body=`<div class="emoji-grid" style="grid-template-columns:repeat(3,1fr)">${['(＾▽＾)','(╥﹏╥)','(•‿•)','(ง •̀_•́)ง','(￣▽￣)','♡'].map(s=>button(s,'insert',s,'',esc(s))).join('')}</div>`;
  return `<div class="emoji-top">${button('搜索表情','emoji-search','','',icon('search'))}${tabs.map(s=>button(s,'emoji-tab',s,state.emojiTab===s?'selected':'')).join('')}${button('返回键盘','return','','',icon('back'))}</div><div class="emoji-body">${body}</div>${footer()}`;
}
function menuPanel() { return `<div class="panel-title"><span>${nativeIme?'功能菜单':'功能菜单 · 未展开页面的补充入口'}</span>${button('返回键盘','return','','',icon('back'))}</div><div class="menu-grid">${[...toolbarItems.filter(([id])=>!['menu','hide'].includes(id)),['symbols','符号'],['numbers','数字'],['edit','编辑文字'],['custom','键盘设置']].map(([id,label])=>button(label,id,'','',icon(id)+`<span>${label}</span>`)).join('')}</div>${footer()}`; }
function editPanel() { return `<div class="panel-title"><span>${nativeIme?'编辑文字':'编辑文字 · 补充试用'}</span>${button('返回键盘','return','','',icon('back'))}</div><div class="menu-grid">${[['select-all','全选'],['copy-text','复制'],['cut-text','剪切'],['paste-last','粘贴'],['cursor-left','左移'],['cursor-right','右移'],['delete','删除'],['enter','换行']].map(([id,label])=>button(label,id,'','',icon(id==='delete'?'delete':'copy')+`<span>${label}</span>`)).join('')}</div>${footer()}`; }
function handPanel(){return `${inkWords.length?`<div class="candidate-bar">${candidateStrip(inkWords,'ink-candidate')}</div>`:toolbar()}<div class="hand-controls key-row">${func('符','symbols')}${func('123','numbers')}${spaceKey()}${func('拼','last-pinyin')}${func('删除','delete','delete')}${func('换行','enter','enter')}</div>${footer()}`;}
function voicePanel() {
  const phase=state.voice.phase;
  const title=phase==='loading'?'正在开启麦克风…':phase==='paused'?'已暂停':phase==='cancel'?'松开手指，取消输入':phase==='recognizing'?'识别中':'倾听中…';
  const hint=phase==='paused'?'点击继续，接着说话':phase==='cancel'?'正在输入…':phase==='recognizing'?'点击空白处取消':state.voice.long?'点击暂停或结束':'上滑取消输入';
  const bars=Array.from({length:58},(_,i)=>`<i style="--bar:${8+Math.abs(Math.sin(i*.72))*Math.max(8,80-Math.abs(i-28)*4)}px;--delay:${i%9*-.1}s"></i>`).join('');
  return `<div class="voice-panel ${phase}" data-action="cancel-recognition" role="status"><span class="voice-title">${title}</span><div class="waveform" aria-hidden="true">${bars}</div><span class="voice-hint">${hint}</span></div><div class="voice-filler"></div>${footer()}`;
}
function popup() {
  if (state.popup==='switcher') return `<div class="popup">${button('讯飞输入法小米版','dismiss-popup','','selected','讯飞输入法小米版　✓')}${button('键盘设置','custom')}</div>`;
  if (state.popup?.type==='key') return `<div class="popup key-popup" style="left:${state.popup.x}px;top:${state.popup.y}px">${state.popup.options.map(s=>button(s,'long-insert',s)).join('')}</div>`;
  return '';
}
function pageLabel() {
  if(state.voice) return `${state.voice.long?'长语音':'长按空格语音'} · ${state.voice.phase==='loading'?'开启麦克风':state.voice.phase==='paused'?'已暂停':state.voice.phase==='recognizing'?'识别中':state.voice.phase==='cancel'?'上滑取消':'倾听中'}${state.voice.demo?'（演示）':''}`;
  return {letters:state.layout==='fullhand'?'全屏手写':state.layout==='9'?'9键中文':state.language==='zh'?'26键中文':'26键英文',candidates:'全部候选',numbers:'数字键盘',symbols:`${state.symbol}符号`,clipboard:state.clipTab==='clipboard'?'剪贴板':'常用语',layouts:'键盘选择',emoji:'表情',menu:'功能菜单 · 补充试用',edit:'编辑文字 · 补充试用'}[state.page] || '键盘';
}
const referenceMap = {letters:'keyboard-001.jpg',en:'keyboard-014.jpg',numbers:'keyboard-022.jpg','常用':'keyboard-026.jpg','中文':'keyboard-028.jpg','英文':'keyboard-027.jpg','网址':'keyboard-029.jpg',clipboard:'clipboard.jpg',phrases:'phrases.jpg',layouts:'keyboard-042.jpg',emoji:'keyboard-050.jpg',switcher:'keyboard-055.jpg',voice:'voice.jpg',cancel:'voice-cancel.jpg',recognizing:'voice-recognizing.jpg'};
function render() {
  const clipboardScroll=keyboard.querySelector('.clip-list')?.scrollTop||0;
  const previousCandidates=keyboard.querySelector('.candidate-visible');
  const candidateScroll=previousCandidates?.scrollLeft||0;
  editor.readOnly=Boolean(state.voice);$('send').disabled=Boolean(state.voice);
  $('long-voice').disabled=Boolean(state.voice)||!speechReady;
  $('pause-voice').disabled=!state.voice?.long||state.voice.demo||!['listening','paused'].includes(state.voice.phase);
  $('pause-voice').textContent=state.voice?.phase==='paused'?'继续':'暂停';
  $('finish-voice').disabled=!state.voice?.long||!['listening','paused'].includes(state.voice.phase);
  $('cancel-voice').disabled=!state.voice;
  $('polish-text').disabled=!polishReady||polishBusy||Boolean(state.voice);
  const handVisible=state.layout==='fullhand' && state.page==='letters' && !state.voice && !state.hidden;
  $('handwriting-surface').hidden=!handVisible;
  keyboard.className=`keyboard${state.night?' night':''}`;
  for(const [key,value] of Object.entries({'--keyboard-height':settings.height+'px','--key-font':settings.fontSize+'px','--key-gap':settings.gap+'px','--key-radius':settings.radius+'px','--kb-bg':settings.background})) keyboard.style.setProperty(key,value);
  if(handVisible&&!nativeIme)keyboard.style.setProperty('--keyboard-height','155px');
  if(state.night) keyboard.style.removeProperty('--kb-bg');
  keyboard.hidden=state.hidden; $('reopen').hidden=!state.hidden;
  const pages={letters:()=>state.layout==='fullhand'?handPanel():state.layout==='9'?nine():letters(),candidates:candidatePanel,numbers,symbols:symbolPanel,clipboard,layouts:layoutPanel,emoji:emojiPanel,menu:menuPanel,edit:editPanel};
  keyboard.innerHTML=(state.voice?voicePanel():(pages[state.page]||letters)())+popup();
  const candidates=keyboard.querySelector('.candidate-visible');
  if(candidates && previousCandidates?.dataset.query===candidates.dataset.query && previousCandidates.textContent===candidates.textContent)candidates.scrollLeft=candidateScroll;
  if(state.page==='clipboard')keyboard.querySelector('.clip-list').scrollTop=clipboardScroll;
  $('page-label').textContent=state.hidden?'键盘已收起':pageLabel();
  $('send').innerHTML=icon(editor.value?'send':'voice');
  if(nativeIme)nativeIme.resize(Math.ceil(keyboard.getBoundingClientRect().height));
  if(handVisible){const phoneRect=$('phone').getBoundingClientRect(),composerRect=editor.closest('.composer').getBoundingClientRect();$('handwriting-surface').style.bottom=nativeIme?'108px':(phoneRect.bottom-composerRect.top+5)+'px';$('ink-guide').hidden=Boolean(inkPad?.hasInk);inkPad?.draw();}
  document.querySelectorAll('#quick-pages button').forEach(b=>b.setAttribute('aria-pressed', b.dataset.value===state.page));
  let ref=state.voice?(state.voice.phase==='cancel'?'cancel':state.voice.phase==='recognizing'?'recognizing':'voice'):state.popup==='switcher'?'switcher':state.page==='symbols'?state.symbol:state.page==='clipboard'?state.clipTab:state.page==='letters'&&state.language==='en'?'en':state.page;
  $('reference-image').src='reference/'+(referenceMap[ref]||referenceMap.letters);
  $('reference-label').textContent=['menu','edit','candidates'].includes(state.page)?'此页面未在录屏中展开；当前展示全键盘参考。':state.layout==='9'&&state.page==='letters'?'录屏只展示了九宫格入口，未展示九宫格画面。':'截自你提供的录屏；个人剪贴板和常用语内容已隐藏。';
}
function feedback(message) { $('feedback').textContent=message; }
function toast(message) { clearTimeout(toastTimer); $('toast').textContent=message; $('toast').hidden=false; toastTimer=setTimeout(()=>$('toast').hidden=true,2600); feedback(message); }
function persist() { updatePhraseOptions();try { localStorage.setItem('voxfix-layout-v1',JSON.stringify(settings)); } catch { feedback('当前浏览器不能保存布局，可以导出文件留存。'); } }
function applyText(result, end=result.cursor) { editor.value=result.text; editor.setSelectionRange(result.cursor,end); editor.scrollTop=editor.scrollHeight; $('send').innerHTML=icon(editor.value?'send':'voice'); if(nativeIme&&state.comp)nativeIme.compose(state.comp.raw); }
function insert(value) { if(nativeIme&&state.comp)commit(currentCandidates()[0]||state.comp.raw);state.comp=null; if(nativeIme){nativeIme.commit(value);applyText({text:'',cursor:0});return;} applyText(replaceRange(editor.value,editor.selectionStart,editor.selectionEnd,value)); }
function commit(word) { if(state.comp) {if(nativeIme){state.comp=null;nativeIme.commit(word);applyText({text:'',cursor:0});if(state.page==='candidates')state.page='letters';return;}if(settings.rememberInput&&state.language==='zh'&&word&&word.length<=60&&state.comp.raw.length<=60){learning=learnWord(learning,(state.layout==='9'?'9:':'p:')+state.comp.raw.toLowerCase(),word);try{localStorage.setItem('voxfix-learning-v1',JSON.stringify(learning));}catch{feedback('选词记忆只保留到本页关闭。');}updateLearningStatus();} applyText(replaceRange(editor.value,state.comp.start,state.comp.start+state.comp.raw.length,word)); state.comp=null;if(state.page==='candidates')state.page='letters'; } }
function inputLetter(value) {
    if(nativeIme&&state.language==='en'){insert(state.shift?value.toUpperCase():value.toLowerCase());return;}
    const start=editor.selectionStart;
    if(!state.comp || start!==state.comp.start+state.comp.raw.length || start!==editor.selectionEnd) state.comp={start,raw:''};
    const letter=state.language==='zh'?value.toLowerCase():value;
    const result=replaceRange(editor.value,start,editor.selectionEnd,letter);
    state.comp.raw+=letter; applyText(result);
}
function deleteText() {
  if(nativeIme&&!state.comp){nativeIme.delete();return;}
  if(state.layout==='fullhand' && inkPad?.hasInk){inkPad.clear();render();return;}
  const result=erase(editor.value,editor.selectionStart,editor.selectionEnd);
  if(state.comp && editor.selectionStart===state.comp.start+state.comp.raw.length) { state.comp.raw=state.comp.raw.slice(0,-1); if(!state.comp.raw) state.comp=null; } else state.comp=null;
  applyText(result); render();
  if(nativeIme&&!state.comp)nativeIme.compose('');
  if(!state.comp && state.page==='candidates'){state.page='letters';render();}
}
function showPage(page) { if(state.voice) cancelVoice();clipMenu=null;clearTimeout(clipHold?.timer);clipHold=null;if(['letters','numbers','symbols'].includes(state.page))state.returnPage=state.page;if(page==='symbols'){const origin=['letters','numbers'].includes(state.page)?state.page:state.returnPage;if(origin!=='symbols')state.symbolReturn=origin;}state.popup=null;state.page=page;state.hidden=false;render(); }
function showTab(name) { if(nativeIme&&name==='custom'){$('custom-panel').hidden=false;nativeSettings.showModal();return;} $('try-panel').hidden=name!=='try'; $('custom-panel').hidden=name!=='custom'; document.querySelectorAll('[data-tab]').forEach(b=>b.setAttribute('aria-selected',b.dataset.tab===name)); }
function startVoice(long=false) {
  if(state.voice)return;
  if(nativeIme){if(!nativeIme.voiceReady()){toast('语音正在准备，请稍后再试。');return;}if(state.comp)commit(state.comp.raw);state.popup=null;state.voice={phase:'listening',long:false};render();nativeIme.startVoice();return;}
  const demo=$('demo-voice').checked;
  if(!demo && !speechReady){toast('离线语音尚未就绪，请先检查左侧的语音状态。');return;}
  state.comp=null; state.popup=null;
  const snapshot={text:editor.value,start:editor.selectionStart,end:editor.selectionEnd};
  state.voice={phase:demo?'listening':'loading',snapshot,demo,long}; render();
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
    },hotwords);
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
function cancelVoice() { if(!state.voice) return; if(nativeIme){nativeIme.cancelVoice();state.voice=null;render();return;} clearInterval(state.voiceTimer);state.voice.controller?.cancel(); applyText(voiceResult(state.voice.snapshot,'',true),state.voice.snapshot.end); state.voice=null; render(); }
function preserveVoice(session) {
  clearInterval(state.voiceTimer);session.controller?.cancel();
  if(session.text)applyText(voiceResult(session.snapshot,session.text));
  else applyText(voiceResult(session.snapshot,'',true),session.snapshot.end);
  state.voice=null;render();
}
function finishVoice(cancelled) {
  if(!state.voice) return;
  if(nativeIme){if(cancelled){cancelVoice();return;}state.voice.phase='recognizing';render();nativeIme.stopVoice();return;}
  clearInterval(state.voiceTimer);
  if(cancelled) { cancelVoice(); toast('已取消，试用文字保持原样。'); return; }
  const session=state.voice; session.phase='recognizing'; render();
  if(session.controller){
    session.controller.finish().then(text=>{
      if(state.voice!==session)return;
      if(text)applyText(voiceResult(session.snapshot,text));else applyText(voiceResult(session.snapshot,'',true),session.snapshot.end);
      state.voice=null;render();feedback(text?'离线识别完成，已做整句识别和标点恢复。':'没有识别到语音，原文字保持不变。');
    }).catch(error=>{if(state.voice!==session)return;preserveVoice(session);toast((error.message||'本地识别失败')+'；已保留收到的识别文字。');});
    return;
  }
  setTimeout(()=>{ if(state.voice!==session) return; applyText(voiceResult(session.snapshot,settings.voiceText)); state.voice=null; render(); feedback('语音演示结束；本页未使用真实语音识别。'); },900);
}
function activate(action,value='') {
  if(nativeIme){
    if(action==='hide'){nativeIme.hide();return;}
    if(action==='switcher'){nativeIme.switcher();return;}
    if(action==='clipboard'||action==='system-paste')nativeIme.refreshClipboard();
    if(action==='system-paste')return;
    if(action==='enter'){if(state.comp)commit(state.comp.raw);nativeIme.enter();render();return;}
    if(['language','twentysix','layout','last-pinyin'].includes(action)&&state.comp)commit(state.comp.raw);
    if(['select-all','copy-text','cut-text','cursor-left','cursor-right'].includes(action)){if(state.comp)commit(state.comp.raw);nativeIme.edit(action);return;}
    if(action==='layout'&&value==='fullhand')handReady=nativeIme.handReady();
  }
  switch(action) {
    case 'letter': inputLetter(value); break;
    case 'insert': case 'long-insert': insert(value); state.popup=null; break;
    case 'candidate': commit(value); break;
    case 'more-candidates': state.page=state.page==='candidates'?'letters':'candidates';break;
    case 'delete': deleteText(); return;
    case 'space': if(state.comp){commit(currentCandidates()[0]);if(state.language==='en')insert(' ');}else insert(' ');break;
    case 'enter': if(state.comp) commit(state.comp.raw); insert('\n'); break;
    case 'language': state.comp=null; state.language=state.language==='zh'?'en':'zh'; state.layout='26'; break;
    case 'shift': state.shift=state.shift?0:1; break;
    case 'split': inputLetter("'"); break;
    case 'nine': inputLetter(value);break;
    case 'nine-split': if(state.comp&&!state.comp.raw.endsWith("'"))inputLetter("'");break;
    case 'clear-pinyin': if(state.comp)commit('');break;
    case 'ink-candidate': insert(value);inkPad.clear();break;
    case 'last-pinyin': state.layout=lastPinyin;state.language='zh';state.page='letters';inkPad.clear();break;
    case 'twentysix': state.layout='26';state.comp=null;state.page='letters';break;
    case 'letters': state.page='letters'; break;
    case 'numbers': case 'symbols': case 'layouts': case 'emoji': case 'menu': case 'edit': showPage(action); return;
    case 'clipboard': state.clipTab='clipboard'; showPage('clipboard'); return;
    case 'night': state.night=!state.night; toast(state.night?'夜间模式已开启':'夜间模式已关闭'); break;
    case 'hide': state.hidden=true; break;
    case 'return': state.popup=null; state.page=state.returnPage; break;
    case 'dismiss-popup': state.popup=null;break;
    case 'symbol-back': state.page=state.symbolReturn; break;
    case 'symbol-category': state.symbol=value; break;
    case 'symbol-lock': state.locked=!state.locked; break;
    case 'symbol': insert(value); if(!state.locked) state.page=state.symbolReturn; break;
    case 'clip-tab': state.clipTab=value;clipMenu=null;break;
    case 'paste': insert(value); break;
    case 'clip-manage': openClipMenu();return;
    case 'clip-close': closeClipMenu();return;
    case 'context-edit': case 'context-delete': case 'context-favorite': case 'context-copy':{
      const text=clipMenu?.text,list=state.clipTab==='clipboard'?clips:phrases,index=list.indexOf(text);clipMenu=null;
      if(index<0){toast('内容已更新，请重新长按。');render();return;}
      if(action==='context-edit'){openSnippet(index);render();return;}
      if(action==='context-delete'){activate('snippet-delete',String(index));return;}
      if(action==='context-favorite'){try{phrases=addSnippet(phrases,text);snippetUndo=null;saveSnippets();toast('已设为常用语。');}catch(error){toast(error.message);}break;}
      if(!navigator.clipboard?.writeText)toast('系统复制不可用，可短按内容插入输入框后复制。');else navigator.clipboard.writeText(text).then(()=>toast('已复制。')).catch(()=>toast('系统复制未获授权，可短按内容插入输入框后复制。'));break;
    }
    case 'snippet-add': clipMenu=null;openSnippet();render();return;
    case 'snippet-edit': openSnippet(Number(value));return;
    case 'snippet-delete': case 'clear-snippets': {clipMenu=null;const list=state.clipTab==='clipboard'?clips:phrases;snippetUndo={tab:state.clipTab,list:[...list]};setSnippetList(action==='clear-snippets'?[]:list.filter((_,i)=>i!==Number(value)));break;}
    case 'undo-snippet': clipMenu=null;if(snippetUndo){const prior=snippetUndo;snippetUndo=null;if(prior.tab==='clipboard')clips=prior.list;else phrases=prior.list;saveSnippets();}break;
    case 'system-paste': clipMenu=null;render();if(!navigator.clipboard?.readText){openSnippet();toast('请粘贴系统剪贴板内容。');return;}navigator.clipboard.readText().then(text=>{if(!text.trim())throw Error('系统剪贴板没有文字');clips=addSnippet(clips,text);snippetUndo=null;saveSnippets();render();toast('已导入系统剪贴板文字。');}).catch(()=>{openSnippet();toast('可在这里粘贴系统剪贴板内容。');});return;
    case 'layout': if(['9','26','fullhand'].includes(value)){if(value==='fullhand'&&!handReady){toast('手写模型还未就绪，请稍后再试。');return;}if(state.layout!=='fullhand')lastPinyin=state.layout;state.layout=value;state.language='zh';state.comp=null;state.page='letters';inkPad.clear();}break;
    case 'emoji-tab': state.emojiTab=value; break;
    case 'emoji-category': state.emojiCategory=value;emojiQuery=''; break;
    case 'emoji-search': state.emojiTab='Emoji';emojiSearchOpen=!emojiSearchOpen;if(!emojiSearchOpen)emojiQuery='';render();if(emojiSearchOpen)keyboard.querySelector('.emoji-search-input').focus();return;
    case 'switcher': state.popup=state.popup==='switcher'?null:'switcher'; break;
    case 'custom': showTab('custom'); state.popup=null;$('custom-panel').scrollIntoView({block:'nearest'}); feedback('可调整尺寸、颜色和保留入口。'); break;
    case 'cancel-recognition': if(state.voice?.phase==='recognizing') { cancelVoice(); toast('识别演示已取消。'); } return;
    case 'select-all': editor.setSelectionRange(0,editor.value.length); state.comp=null; break;
    case 'copy-text': case 'cut-text': { const text=editor.value.slice(editor.selectionStart,editor.selectionEnd)||editor.value; if(text) {try{clips=addSnippet(clips,text);snippetUndo=null;saveSnippets();}catch(error){toast(error.message);return;} if(action==='cut-text') { if(editor.selectionStart===editor.selectionEnd) applyText({text:'',cursor:0}); else insert(''); } toast('已复制到本页剪贴板。');navigator.clipboard?.writeText(text).catch(()=>toast('已存入本页剪贴板，系统复制未获授权。')); } break; }
    case 'paste-last': if(clips[0]) insert(clips[0]); break;
    case 'cursor-left': case 'cursor-right': { state.comp=null; const segments=[...new Intl.Segmenter('zh',{granularity:'grapheme'}).segment(editor.value)].map(s=>s.index); segments.push(editor.value.length); const pos=action==='cursor-left'?segments.filter(p=>p<editor.selectionStart).at(-1)??0:segments.find(p=>p>editor.selectionEnd)??editor.value.length; editor.setSelectionRange(pos,pos); break; }
    default: return;
  }
  render();
}

keyboard.addEventListener('pointerdown',e=>{
  if(e.button!==0 || gesture) return;
  const row=e.target.closest('.candidate-visible');
  if(row && e.pointerType==='mouse') {
    e.preventDefault();keyboard.setPointerCapture(e.pointerId);
    gesture={id:e.pointerId,action:'candidate-scroll',x:e.clientX,scroll:row.scrollLeft,row,target:row,dragged:false};
    return;
  }
  const target=e.target.closest('[data-action]'); if(!target) return;
  if(target.dataset.action==='cursor-gesture' && !state.voice) {
    e.preventDefault(); keyboard.setPointerCapture(e.pointerId);
    const g={id:e.pointerId,action:'cursor-gesture',x:e.clientX,y:e.clientY,long:false,target};gesture=g;
    g.timer=setTimeout(()=>{if(gesture!==g)return;g.long=true;state.comp=null;state.popup=null;state.cursorDrag={origin:editor.selectionEnd};editor.focus({preventScroll:true});editor.setSelectionRange(editor.selectionEnd,editor.selectionEnd);render();feedback('左右拖动移动光标，松手结束。');},420);
    return;
  }
  if(target.tagName!=='BUTTON') return;
  // Scrollable lists use native scrolling and click; only keys need hold/slide capture.
  if(!target.classList.contains('key')) return;
  e.preventDefault();
  if(state.voice) return;
  keyboard.setPointerCapture(e.pointerId);
  const rect=target.getBoundingClientRect(), kbRect=keyboard.getBoundingClientRect();
  const g={id:e.pointerId,action:target.dataset.action,value:target.dataset.value,y:e.clientY,long:false,cancel:false,hint:target.querySelector('.hint')?.textContent,target}; gesture=g;
  target.classList.add('pressed');
  if(g.action==='letter')keyboard.insertAdjacentHTML('beforeend',`<div class="key-preview" aria-hidden="true" style="left:${Math.max(0,Math.min(kbRect.width-46,rect.left-kbRect.left+rect.width/2-23))}px;top:${Math.max(0,rect.top-kbRect.top-44)}px">${esc(g.value)}</div>`);
  if(g.action==='delete'){g.immediate=true;g.inkErase=state.layout==='fullhand'&&inkPad?.hasInk;deleteText();}
  g.timer=setTimeout(()=>{
    if(gesture!==g) return;
    if(g.action==='space') { g.long=true; startVoice(); }
    else if(g.action==='delete') { g.long=true;if(!g.inkErase)g.repeat=setInterval(deleteText,85); }
    else if(g.action==='letter') { g.long=true; state.popup={type:'key',x:Math.max(5,Math.min(kbRect.width-145,rect.left-kbRect.left-35)),y:Math.max(0,rect.top-kbRect.top-52),options:[...new Set([g.hint,g.value.toLowerCase(),g.value.toUpperCase()].filter(Boolean))]}; render(); }
    else if(g.action==='nine'||g.action==='nine-split'){g.long=true;insert(g.value);render();}
  },420);
});
keyboard.addEventListener('pointermove',e=>{
  if(!gesture || gesture.id!==e.pointerId) return;
  if(gesture.action==='candidate-scroll') {
    const distance=e.clientX-gesture.x;
    if(Math.abs(distance)>6)gesture.dragged=true;
    if(gesture.dragged)gesture.row.scrollLeft=gesture.scroll-distance;
    return;
  }
  if(gesture.long && gesture.action==='cursor-gesture' && state.cursorDrag) {
    if(nativeIme){const steps=Math.trunc((e.clientX-gesture.x)/14);nativeIme.moveCursor(steps-(gesture.steps||0));gesture.steps=steps;return;}
    const position=moveCursor(editor.value,state.cursorDrag.origin,Math.trunc((e.clientX-gesture.x)/14));
    editor.setSelectionRange(position,position);render();return;
  }
  if(gesture.long && gesture.action==='space' && state.voice) {
    const cancelled=gesture.y-e.clientY>55;
    if(cancelled!==gesture.cancel) { gesture.cancel=cancelled; state.voice.phase=cancelled?'cancel':'listening'; render(); }
  }
});
function endGesture(e,cancelled=false) {
  const g=gesture; if(!g || g.id!==e.pointerId) return;
  gesture=null; clearTimeout(g.timer); clearInterval(g.repeat); g.target.classList.remove('pressed');
  keyboard.querySelector('.key-preview')?.remove();
  if(keyboard.hasPointerCapture(e.pointerId)) keyboard.releasePointerCapture(e.pointerId);
  if(g.action==='candidate-scroll') {
    if(g.dragged || cancelled)candidateSuppressUntil=Date.now()+350;
    else {const target=document.elementFromPoint(e.clientX,e.clientY)?.closest('.candidate-visible button');if(target && g.row.contains(target))activate(target.dataset.action,target.dataset.value);}
    return;
  }
  if(g.action==='cursor-gesture') {state.cursorDrag=null;render();return;}
  if(cancelled) { if(state.voice) cancelVoice(); state.popup=null; render(); return; }
  if(g.long && g.action==='space') finishVoice(g.cancel);
  else if(!g.long && !g.immediate) activate(g.hint && g.y-e.clientY>35?'insert':g.action,g.hint && g.y-e.clientY>35?g.hint:g.value);
}
keyboard.addEventListener('pointerup',e=>endGesture(e));
keyboard.addEventListener('pointercancel',e=>endGesture(e,true));
keyboard.addEventListener('click',e=>{
  const target=e.target.closest('[data-action]'); if(!target) return;
  const pointerClick=e.detail!==0||Boolean(e.pointerType);
  if(target.closest('.candidate-visible') && pointerClick && (e.pointerType==='mouse'||Date.now()<candidateSuppressUntil))return;
  if(target.dataset.action==='paste'&&Date.now()<clipSuppressUntil)return;
  if(target.classList.contains('key') && pointerClick) return;
  activate(target.dataset.action,target.dataset.value);
});
keyboard.addEventListener('contextmenu',e=>e.preventDefault());
keyboard.addEventListener('pointerdown',e=>{
  if(e.button!==0||!e.target.closest('.clip-list'))return;
  clearTimeout(clipHold?.timer);
  const target=e.target.closest('.clip-text'),hold={id:e.pointerId,x:e.clientX,y:e.clientY,index:target?Number(target.dataset.index):-1};clipHold=hold;
  hold.timer=setTimeout(()=>{if(clipHold!==hold)return;clipSuppressUntil=Date.now()+700;openClipMenu(hold.index);},500);
});
keyboard.addEventListener('pointermove',e=>{if(clipHold&&e.pointerId===clipHold.id&&Math.hypot(e.clientX-clipHold.x,e.clientY-clipHold.y)>10){clearTimeout(clipHold.timer);clipHold=null;}});
for(const event of ['pointerup','pointercancel'])keyboard.addEventListener(event,e=>{if(clipHold&&e.pointerId===clipHold.id){clearTimeout(clipHold.timer);clipHold=null;}});
keyboard.addEventListener('contextmenu',e=>{if(!e.target.closest('.clip-list'))return;const item=e.target.closest('.clip-text');openClipMenu(item?Number(item.dataset.index):-1);});
keyboard.addEventListener('keydown',e=>{if(e.target.matches('.clip-text')&&(e.key==='ContextMenu'||(e.shiftKey&&e.key==='F10'))){e.preventDefault();openClipMenu(Number(e.target.dataset.index));}});
keyboard.addEventListener('keydown',e=>{if(clipMenu&&e.key==='Escape'){e.preventDefault();closeClipMenu();}});
function searchEmoji(input){emojiQuery=input.value;const cursor=input.selectionStart;render();const next=keyboard.querySelector('.emoji-search-input');next.focus();next.setSelectionRange(cursor,cursor);}
keyboard.addEventListener('input',e=>{if(e.target.matches('.emoji-search-input')&&!e.isComposing)searchEmoji(e.target);});
keyboard.addEventListener('compositionend',e=>{if(e.target.matches('.emoji-search-input'))searchEmoji(e.target);});
keyboard.addEventListener('keydown',e=>{
  if(!e.target.closest('.cursor-zone') || !['ArrowLeft','ArrowRight','Home','End'].includes(e.key))return;
  e.preventDefault();state.comp=null;
  const position=e.key==='Home'?0:e.key==='End'?editor.value.length:moveCursor(editor.value,editor.selectionEnd,e.key==='ArrowLeft'?-1:1);
  editor.setSelectionRange(position,position);render();keyboard.querySelector('.cursor-zone').focus({preventScroll:true});
});
keyboard.addEventListener('pointerdown',e=>{ if(e.target.closest('button') && !e.target.closest('.candidate-visible,.candidate-grid,.clip-list,.symbol-grid,.number-strip,.layout-grid,.emoji-body')) e.preventDefault(); });
document.addEventListener('visibilitychange',()=>{ if(document.hidden) { if(state.voice)preserveVoice(state.voice);if(gesture)endGesture({pointerId:gesture.id},true); } });
editor.addEventListener('compositionstart',()=>{nativeComposition=true;state.comp=null;render();});
editor.addEventListener('compositionend',()=>{nativeComposition=false;state.comp=null;render();});
editor.addEventListener('input',e=>{
  state.comp=null;
  if(!nativeComposition && state.language==='zh' && e.inputType!=='insertFromPaste'){
    const raw=editor.value.slice(0,editor.selectionStart).match(/[a-z']+$/i)?.[0];
    if(raw)state.comp={start:editor.selectionStart-raw.length,raw:raw.toLowerCase()};
  }
  render();
});
editor.addEventListener('keydown',e=>{
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
editor.addEventListener('pointerdown',()=>{state.comp=null;render();});
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
  $('sliders').innerHTML=[['height','键盘高度',220,390,'px'],['fontSize','按键字号',14,28,'px'],['gap','按键间距',2,10,'px'],['radius','按键圆角',0,14,'px'],['candidateCount','候选每屏参考数量',3,9,'个'],['candidateFont','候选字号',13,22,'px']].map(([id,label,min,max,unit])=>`<label class="slider-field">${label}<output id="${id}-value">${settings[id]} ${unit}</output><input id="${id}" type="range" min="${min}" max="${max}" value="${settings[id]}" data-setting="${id}" data-unit="${unit}" aria-label="${label}"></label>`).join('');
  $('feature-toggles').innerHTML=toolbarItems.map(([id,label])=>`<label><input type="checkbox" data-feature="${id}" ${settings.toolbar.includes(id)?'checked':''}>${label}</label>`).join('')+[['footerSwitcher','底部输入法菜单'],['footerClipboard','底部剪贴板']].map(([id,label])=>`<label><input type="checkbox" data-setting="${id}" ${settings[id]?'checked':''}>${label}</label>`).join('');
  for(const id of ['background','comma','period']) $(id).value=settings[id];
  $('voice-text').value=settings.voiceText;
  $('remember-input').checked=settings.rememberInput;
  updateLearningStatus();
}
function updateLearningStatus(){$('learning-status').textContent=`已记忆 ${learning.length} 条选词偏好。常选两次后提前，只保存在当前浏览器。`;}
$('remember-input').onchange=e=>{settings.rememberInput=e.target.checked;persist();render();};
$('clear-learning').onclick=()=>{if(!confirm('清空当前浏览器的选词记忆？此操作不能撤销。'))return;learning=[];localStorage.removeItem('voxfix-learning-v1');updateLearningStatus();render();};
$('sliders').oninput=e=>{ const key=e.target.dataset.setting;if(!key)return;settings[key]=Number(e.target.value);$(key+'-value').textContent=settings[key]+' '+e.target.dataset.unit;persist();render(); };
$('feature-toggles').onchange=e=>{ const input=e.target;if(input.dataset.feature)settings.toolbar=toolbarItems.map(([id])=>id).filter(id=>id===input.dataset.feature?input.checked:settings.toolbar.includes(id));else if(input.dataset.setting)settings[input.dataset.setting]=input.checked;persist();render();feedback('入口已调整，可随时恢复录屏布局。'); };
for(const id of ['background','comma','period','voice-text']) $(id).oninput=e=>{const key=id==='voice-text'?'voiceText':id;if(!e.target.value)return;settings[key]=e.target.value;persist();render();};
$('export').onclick=()=>{ const file=new Blob([JSON.stringify({schema:1,...settings},null,2)],{type:'application/json'});const url=URL.createObjectURL(file);const a=document.createElement('a');a.href=url;a.download='voxfix-layout.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);feedback('已导出布局配置。'); };
$('import').onclick=()=>$('config-file').click();
$('config-file').onchange=async e=>{ const file=e.target.files[0];if(!file)return;try{if(file.size>50000)throw Error('配置文件过大');const value=JSON.parse(await file.text());if(value.schema!==1)throw Error('不支持的配置版本');const next=settingsFrom(value);if(state.voice)cancelVoice();settings=next;syncControls();persist();render();toast('布局已导入。');}catch(error){toast(error.message||'无法读取布局配置');}finally{e.target.value='';} };
$('reset').onclick=()=>{ if(state.voice)cancelVoice();settings=structuredClone(defaults);state.night=false;state.hidden=false;syncControls();persist();render();feedback('已恢复录屏布局，试用文字保留。'); };
$('snippet-cancel').onclick=()=>$('snippet-dialog').close();
$('snippet-form').onsubmit=e=>{e.preventDefault();try{const list=snippetEdit.tab==='clipboard'?clips:phrases;const index=snippetEdit.index<0?-1:list.indexOf(snippetEdit.original);if(snippetEdit.index>=0&&index<0)throw Error('原内容已更改，请重新打开后编辑');const next=addSnippet(list,$('snippet-content').value,index);if(snippetEdit.tab==='clipboard')clips=next;else phrases=next;snippetUndo=null;const stored=saveSnippets();$('snippet-dialog').close();render();toast(stored?'内容已保存。':'内容只保留到本页关闭，当前浏览器未能保存。');}catch(error){$('snippet-error').textContent=error.message;}};
window.addEventListener('storage',e=>{if(e.key==='voxfix-learning-v1'){try{learning=e.newValue?validateLearning(JSON.parse(e.newValue)):[];updateLearningStatus();if(state.comp)render();}catch{}return;}if(e.key!=='voxfix-snippets-v1'||!e.newValue)return;try{const saved=JSON.parse(e.newValue);const nextClips=validateSnippets(saved.clips),nextPhrases=validateSnippets(saved.phrases);clips=nextClips;phrases=nextPhrases;snippetUndo=null;updatePhraseOptions();if(state.page==='clipboard')render();}catch{/* Invalid cross-tab data is ignored. */}});
inkPad=new InkPad($('ink-canvas'),words=>{inkWords=words;render();},message=>toast(message));
$('ink-clear').onclick=()=>{inkPad.clear();render();};
async function updateHandStatus(){if(nativeIme){handReady=nativeIme.handReady();return;}try{const response=await fetch('/api/handwriting/status');const data=await response.json();handReady=Boolean(data.ready);}catch{handReady=false;}if(location.protocol!=='file:')setTimeout(updateHandStatus,handReady?15000:5000);}
updateHandStatus();
syncControls();render();
loadPinyin().then(()=>{updatePhraseOptions();if(state.comp)render();$('pinyin-status').textContent='全拼、简拼和混输已就绪：wzdl 找“我知道了”、mtj 找“明天见”。';}).catch(()=>$('pinyin-status').textContent='拼音词库未能加载，请刷新页面或重新生成词库。');
async function updateVoiceStatus(){
  if(nativeIme){speechReady=true;return;}
  const status=await voiceStatus();speechReady=Boolean(status.ready);
  $('speech-status').textContent=speechReady?'已就绪 · 电脑本地离线':status.error||'正在加载离线模型…';
  $('enable-mic').disabled=!speechReady;
  $('long-voice').disabled=!speechReady||Boolean(state.voice);
  if(location.protocol!=='file:')setTimeout(updateVoiceStatus,speechReady?15000:5000);
}
$('enable-mic').onclick=async()=>{try{await enableMicrophone();toast('麦克风已启用，现在可以长按空格说话。');}catch(error){toast(error.name==='NotAllowedError'?'请在浏览器中允许这个本机页面使用麦克风。':error.message);}};
$('demo-voice').onchange=()=>{if(state.voice)cancelVoice();$('demo-fields').hidden=!$('demo-voice').checked;feedback($('demo-voice').checked?'已切换为交互演示，不录音。':'已切换为真实本地离线语音。');};
updateVoiceStatus();
function updatePhraseOptions(){setPhraseOptions(phrases,settings.phrasePacks);for(const input of $('phrase-options').querySelectorAll('input'))input.checked=settings.phrasePacks.includes(input.value);const query=$('phrase-search').value.trim();$('phrase-results').innerHTML=query?phraseSearch(query).slice(0,20).map(text=>button(text,'phrase-result',text)).join(''):'';}
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
  $('remember-input').disabled=true;
  $('clear-learning').disabled=true;
  globalThis.voxfixReset=()=>{clearTimeout(gesture?.timer);clearInterval(gesture?.repeat);gesture=null;state.voice=null;state.comp=null;state.popup=null;state.hidden=false;state.page='letters';inkPad?.clear();applyText({text:'',cursor:0});render();};
  globalThis.voxfixClearComposition=()=>{state.comp=null;if(state.page==='candidates')state.page='letters';applyText({text:'',cursor:0});render();};
  let fieldLanguage=null;
  globalThis.voxfixInputType=type=>{
    const kind=type&15,variation=type&4080;
    if(kind===2||kind===3)state.page='numbers';
    else if(kind===1&&[16,32,128,144,224].includes(variation)){fieldLanguage??=state.language;state.language='en';state.layout='26';}
    else if(fieldLanguage!==null){state.language=fieldLanguage;fieldLanguage=null;}
    render();
  };
  globalThis.voxfixVoiceDone=message=>{state.voice=null;render();if(message)toast(message);};
  globalThis.voxfixVoiceLevel=level=>{keyboard.querySelector('.waveform')?.style.setProperty('--mic-level',Math.max(.12,Math.min(1,level/12)));};
  globalThis.voxfixClipboard=text=>{clips=clips.filter(value=>value!==nativeSystemClip);nativeSystemClip=text;if(text&&!clips.includes(text))clips.unshift(text);if(state.page==='clipboard')render();};
  nativeIme.ready();
}
$('voice-hotwords').value=hotwords.join('\n');
$('voice-hotwords').onchange=()=>{const words=[...new Set($('voice-hotwords').value.split('\n').map(word=>word.trim()).filter(Boolean))];if(words.length>100||words.some(word=>!/^[\u3400-\u9fff]{1,30}$/.test(word))){toast('热词最多 100 条，每条 1–30 个汉字；请修改后再保存。');return;}hotwords=words;try{localStorage.setItem('voxfix-hotwords-v1',JSON.stringify(words));feedback('热词已保存，下次录音生效。');}catch{toast('热词仅在当前页面保留。');}};
