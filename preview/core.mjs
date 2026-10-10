export const toolbarItems = [
  ['menu', '功能菜单'], ['layouts', '键盘选择'], ['night', '夜间模式'],
  ['clipboard', '剪贴板'], ['emoji', '表情'], ['hide', '收起键盘'],
];
export const defaults = {
  height: 312, fontSize: 20, gap: 5, radius: 7, background: '#edeef2',
  toolbar: toolbarItems.map(([id]) => id), footerSwitcher: true, footerClipboard: true,
  comma: '，', period: '。', voiceText: '你好。', candidateCount: 5, candidateFont: 17, rememberInput: true, phrasePacks: [],
};
export function settingsFrom(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw Error('请选择有效的布局配置文件');
  const result = structuredClone(defaults);
  if('phrasePacks' in value){
    if(!Array.isArray(value.phrasePacks)||value.phrasePacks.some(name=>!['quotes','technology','running'].includes(name)))throw Error('词库选项无效');
    result.phrasePacks=[...new Set(value.phrasePacks)];
  }
  for (const [key, min, max] of [['height', 220, 390], ['fontSize', 14, 28], ['gap', 2, 10], ['radius', 0, 14], ['candidateCount', 3, 9], ['candidateFont', 13, 22]]) {
    if (key in value) {
      if (!Number.isFinite(value[key]) || value[key] < min || value[key] > max || (key === 'candidateCount' && !Number.isInteger(value[key]))) throw Error('尺寸配置超出范围');
      result[key] = value[key];
    }
  }
  if ('background' in value) {
    if (!/^#[0-9a-f]{6}$/i.test(value.background)) throw Error('背景颜色无效');
    result.background = value.background;
  }
  if ('toolbar' in value) {
    if (!Array.isArray(value.toolbar) || value.toolbar.some(id => !toolbarItems.some(([valid]) => valid === id))) throw Error('快捷栏配置无效');
    result.toolbar = [...new Set(value.toolbar)];
  }
  for (const key of ['footerSwitcher', 'footerClipboard', 'rememberInput']) {
    if (key in value && typeof value[key] !== 'boolean') throw Error('底部入口配置无效');
    if (key in value) result[key] = value[key];
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
export function validateSnippets(list) {
  if(!Array.isArray(list)||list.length>200||list.some(text=>typeof text!=='string'||!text.trim()||text.length>10000))throw Error('剪贴板内容格式无效');
  return [...new Set(list)];
}
export function addSnippet(list,text,index=-1) {
  validateSnippets([text]);
  if(index>=0){
    if(index>=list.length)throw Error('该内容已不存在');
    if(list.some((item,i)=>i!==index&&item===text))throw Error('这条内容已经存在');
    return list.map((item,i)=>i===index?text:item);
  }
  const result=[text,...list.filter(item=>item!==text)];
  if(result.length>200)throw Error('最多保存 200 条，请先删除不需要的内容');
  return result;
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
  for(const row of records)if(row.key===key||(row.key.slice(0,2)===key.slice(0,2)&&words.includes(row.word)))scores.set(row.word,(scores.get(row.word)||0)+row.hits);
  const learned=[...scores].filter(([,hits])=>hits>=2).sort((a,b)=>b[1]-a[1]).map(([word])=>word);
  return [...new Set([...learned,...words])];
}
