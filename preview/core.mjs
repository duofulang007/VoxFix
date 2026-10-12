export const toolbarItems = [
  ['menu', '功能菜单'], ['layouts', '键盘选择'], ['night', '夜间模式'],
  ['clipboard', '剪贴板'], ['emoji', '表情'], ['hide', '收起键盘'],
];
export const keyboardGeometry = Object.freeze({height:351,fontSize:22,gap:4,radius:7,background:'#edeff2',candidateCount:3,candidateFont:22});
export const defaults = {
  ...keyboardGeometry,
  toolbar: toolbarItems.map(([id]) => id), footerSwitcher: true, footerClipboard: true,
  comma: '，', period: '。', voiceText: '你好。', rememberInput: true, phrasePacks: [],
  clipboardHistory: true, clipboardCandidate: false, customToolbar: [],
};
export function settingsFrom(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw Error('请选择有效的布局配置文件');
  const result = structuredClone(defaults);
  if('phrasePacks' in value){
    if(!Array.isArray(value.phrasePacks)||value.phrasePacks.some(name=>!['quotes','technology','running'].includes(name)))throw Error('词库选项无效');
    result.phrasePacks=[...new Set(value.phrasePacks)];
  }
  // Saved/imported appearance values are obsolete; the official geometry always wins.
  if ('toolbar' in value) {
    if (!Array.isArray(value.toolbar) || value.toolbar.some(id => !toolbarItems.some(([valid]) => valid === id) && !/^custom:[a-z0-9]{1,8}$/.test(id))) throw Error('快捷栏配置无效');
    result.toolbar = [...new Set(value.toolbar)];
  }
  for (const key of ['footerSwitcher', 'footerClipboard', 'rememberInput', 'clipboardHistory', 'clipboardCandidate']) {
    if (key in value && typeof value[key] !== 'boolean') throw Error('底部入口配置无效');
    if (key in value) result[key] = value[key];
  }
  if ('customToolbar' in value) {
    const list = value.customToolbar;
    if (!Array.isArray(list) || list.length > 12) throw Error('自定义工具栏配置无效');
    if (list.some(row => !row || typeof row.label !== 'string' || typeof row.text !== 'string' || !row.label.trim() || row.label.length > 6 || !row.text.trim() || row.text.length > 10000)) throw Error('自定义工具栏配置无效');
    result.customToolbar = list.map(row => ({ label: row.label, text: row.text }));
  }
  for (const key of ['comma', 'period', 'voiceText']) {
    if (key in value) {
      if (typeof value[key] !== 'string' || value[key].length < 1 || value[key].length > (key === 'voiceText' ? 100 : 8)) throw Error('按键文字或语音演示文字无效');
      result[key] = value[key];
    }
  }
  return result;
}
export function replaceRange(text, start, end, value) {
  start = Math.max(0, Math.min(text.length, start));
  end = Math.max(start, Math.min(text.length, end));
  return { text: text.slice(0, start) + value + text.slice(end), cursor: start + value.length };
}
export function erase(text, start, end) {
  if (start !== end) return replaceRange(text, start, end, '');
  const parts = [...new Intl.Segmenter('zh', { granularity: 'grapheme' }).segment(text)];
  const before = parts.filter(part => part.index < start).at(-1);
  return before ? replaceRange(text, before.index, before.index + before.segment.length, '') : { text, cursor: start };
}
export function moveCursor(text, start, steps) {
  const boundaries = [...new Intl.Segmenter('zh', { granularity: 'grapheme' }).segment(text)].map(part => part.index);
  boundaries.push(text.length);
  let index = boundaries.findIndex(position => position >= start);
  if (index === -1) index = boundaries.length - 1;
  return boundaries[Math.max(0, Math.min(boundaries.length - 1, index + steps))];
}
export function voiceResult(snapshot, value, cancelled = false) {
  return cancelled ? { text: snapshot.text, cursor: snapshot.start } : replaceRange(snapshot.text, snapshot.start, snapshot.end, value);
}
// 官方小米版剪贴板固定「最多记录 300 条」，到上限后丢弃最旧的一条。这里对齐同一策略。
export const snippetLimit = 300;
export function validateSnippets(list) {
  if(!Array.isArray(list)||list.length>snippetLimit||list.some(text=>typeof text!=='string'||!text.trim()||text.length>10000))throw Error('剪贴板内容格式无效');
  return [...new Set(list)];
}
export function addSnippet(list,text,index=-1) {
  validateSnippets([text]);
  if(index>=0){
    if(index>=list.length)throw Error('该内容已不存在');
    if(list.some((item,i)=>i!==index&&item===text))throw Error('这条内容已经存在');
    return list.map((item,i)=>i===index?text:item);
  }
  // 到达上限时丢最旧的一条，而不是让新增失败（官方行为）。
  return [text,...list.filter(item=>item!==text)].slice(0,snippetLimit);
}
// 官方常用语：分组长按上限 20 个，每个分组内上限 100 条，每条上限 500 字。
export const phraseGroupLimit = 20;
export const phraseItemLimit = 100;
export const phraseLengthLimit = 500;
export function validatePhraseGroups(groups) {
  if (!Array.isArray(groups) || groups.length > phraseGroupLimit) throw Error('常用语分组格式无效');
  return groups.map(group => {
    if (!group || typeof group.name !== 'string' || !group.name.trim() || group.name.length > 8) throw Error('常用语分组名称无效');
    if (!Array.isArray(group.items) || group.items.length > phraseItemLimit) throw Error('常用语分组内容无效');
    if (group.items.some(text => typeof text !== 'string' || !text.trim() || text.length > phraseLengthLimit)) throw Error('常用语内容无效');
    return { name: group.name.trim(), items: [...new Set(group.items)] };
  });
}
export function flattenPhrases(groups) { return [...new Set(groups.flatMap(group => group.items))]; }
// 兼容旧的扁平常用语列表：自动放进一个默认分组。
export function migratePhraseGroups(value) {
  if (!Array.isArray(value) || !value.length || typeof value[0] === 'string') {
    const legacy = Array.isArray(value) ? validateSnippets(value.filter(text => typeof text === 'string' && text.trim()).slice(0, phraseItemLimit)) : [];
    return [{ name: '常用语', items: legacy.map(text => text.slice(0, phraseLengthLimit)) }];
  }
  return validatePhraseGroups(value);
}
export function validateLearning(records){
  if(!Array.isArray(records)||records.length>2000||records.some(row=>!row||!/^([p9]):[a-z'2-9]{1,60}$/.test(row.key)||typeof row.word!=='string'||!/[\u3400-\u9fff]/.test(row.word)||row.word.length>60||!Number.isInteger(row.hits)||row.hits<1||row.hits>1000000))throw Error('选词记忆格式无效');
  return records;
}
export function learnWord(records,key,word){
  if(!/[\u3400-\u9fff]/.test(word))return records;
  const previous=records.find(row=>row.key===key&&row.word===word);
  const row={key,word,hits:Math.min(1000000,(previous?.hits||0)+1)};
  validateLearning([row]);
  // shortcut: keep the most recent 2000 choices; use a database if long-term vocabulary exceeds this.
  return [row,...records.filter(item=>item.key!==key||item.word!==word)].slice(0,2000);
}
export function rankLearned(words,records,key){
  const scores=new Map();
  for(const row of records)if(words.includes(row.word)&&(row.key===key||row.key.slice(0,2)===key.slice(0,2)))scores.set(row.word,(scores.get(row.word)||0)+row.hits);
  const learned=[...scores].filter(([,hits])=>hits>=2).sort((a,b)=>b[1]-a[1]).map(([word])=>word);
  return [...new Set([...learned,...words])];
}
