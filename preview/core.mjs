export const toolbarItems = [
  ['menu', '功能菜单'], ['layouts', '键盘选择'], ['night', '夜间模式'],
  ['clipboard', '剪贴板'], ['emoji', '表情'], ['hide', '收起键盘'],
];
export const keyboardGeometry = Object.freeze({height:351,fontSize:22,gap:4,radius:7,background:'#edeff2',candidateCount:3});
// 66px 的整块触感区域两侧各留 8px，阻尼只能在安全边界内发生。
export function cursorFeedbackPosition(width, x) {
  const edge=Math.min(41,width/2),soft=Math.min(16,Math.max(0,width/2-edge));
  if(!soft)return width/2;
  const left=edge+soft,right=width-edge-soft;
  if(x<left)return left-soft*(left-x)/(soft+left-x);
  if(x>right)return right+soft*(x-right)/(soft+x-right);
  return x;
}
// 底部滑轨摇杆：以触控点为原点，按到原点的距离分档调速。
// 等值线是超椭圆 |dx/a|^n + |dy/b|^n = 1 —— n=3 近似圆角方：斜推不像正方形那样多走 1.41 倍，
// 又不像正圆那样把键盘角落白白浪费。a/b 由按下瞬间的可用空间决定（joystickRadii），
// 手势内锁死，否则手指一动半径就变、手感会漂。
// 关键：「推进最深档」不等于「停下来」——ρ≥1 只是钉在最高档，整片区域始终有输出。
export const joystick = Object.freeze({
  shape: 3,
  // 只分两档：绝大多数区域是「跟手」（手指移多少、光标/选区就动多少，能精确定位），
  // 只有推到最外圈才切高速。
  // 先前三档（0.42 / 0.72）门槛太低：向下可用空间只有几十像素，推 23px 就进 8 字/秒，
  // 手感就是「稍微动一下就跨行」。门槛提到 0.78 后，短距离推不出速率。
  gates: [0, 0.78],
  rates: [0, 24],
  hysteresis: 0.06,
  minSide: 60,
  minUp: 90,
  // 上下分开定半径：滑轨贴着键盘底，向下只剩几十像素。minDown 取 40，让「推到键盘底」
  // （约 32px）刚好落在最快档门槛上（32/40 = 0.8 ≥ 0.78）。
  minDown: 40,
  maxRadius: 96,
});
export function joystickRadii(side,up,down,config=joystick){
  // 大键盘也只需推约75px进入持续档，不能把整张键盘高度当成上推行程。
  return {a:Math.min(config.maxRadius,Math.max(config.minSide,side)),bUp:Math.min(config.maxRadius,Math.max(config.minUp,up)),bDown:Math.min(config.maxRadius,Math.max(config.minDown,down))};
}
export function joystickRho(dx,dy,{a,bUp,bDown},shape=joystick.shape){
  const b=dy<0?bUp:bDown;
  const x=Math.abs(dx)/Math.max(1,a),y=Math.abs(dy)/Math.max(1,b);
  if(!x&&!y)return 0;
  return Math.pow(Math.pow(x,shape)+Math.pow(y,shape),1/shape);
}
export function joystickLevel(rho,current,config=joystick){
  const gates=config.gates,top=gates.length-1;
  let level=Math.max(0,Math.min(top,Math.round(current)));
  while(level<top&&rho>=gates[level+1])level++;
  while(level>0&&rho<gates[level]-config.hysteresis)level--;
  return level;
}
export const defaults = {
  ...keyboardGeometry,
  toolbar: toolbarItems.map(([id]) => id), footerSwitcher: true, footerClipboard: true,
  comma: '，', period: '。', voiceText: '你好。', rememberInput: true, phrasePacks: [],
  clipboardHistory: true, clipboardCandidate: false, clipboardMatch: true, customToolbar: [],
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
  for (const key of ['footerSwitcher', 'footerClipboard', 'clipboardHistory', 'clipboardCandidate', 'clipboardMatch']) {
    if (key in value && typeof value[key] !== 'boolean') throw Error('底部入口配置无效');
    if (key in value) result[key] = value[key];
  }
  if ('customToolbar' in value) {
    const list = value.customToolbar;
    if (!Array.isArray(list) || list.length > 12) throw Error('自定义工具栏配置无效');
    if (list.some(row => !row || typeof row.label !== 'string' || typeof row.text !== 'string' || !row.label.trim() || row.label.length > 6 || !row.text.trim() || row.text.length > 10000)) throw Error('自定义工具栏配置无效');
    const legacyIds=result.toolbar.filter(id=>id.startsWith('custom:')),used=new Set();
    result.customToolbar = list.map((row,index) => {
      const id=row.id===undefined?(legacyIds[index]??'custom:legacy'+index):row.id;
      if(typeof id!=='string'||!/^custom:[a-z0-9]{1,8}$/.test(id)||used.has(id))throw Error('自定义工具栏标识无效');
      used.add(id);
      if(row.id===undefined&&!result.toolbar.includes(id))result.toolbar.push(id);
      return {id,label:row.label,text:row.text};
    });
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
export const clipboardShortLimit=32;
export function validateLearning(records){
  if(!Array.isArray(records)||records.some(row=>!row||!(/^(?:p:[a-z']{1,60}|9:[2-9]{1,60})$/.test(row.key))||typeof row.word!=='string'||!/[\u3400-\u9fff]/.test(row.word)||row.word.length>60||/[\u0000-\u001f\u007f-\u009f]/.test(row.word)||!Number.isInteger(row.hits)||row.hits<1||row.hits>1000000))throw Error('选词记忆格式无效');
  if(records.some(row=>row.code!==undefined&&(typeof row.code!=='string'||!/^p:/.test(row.key)||!/^[a-z]{1,6}( [a-z]{1,6}){0,15}$/.test(row.code)||!/^[\u3400-\u9fff]{1,16}$/.test(row.word)||row.code.split(' ').length!==row.word.length||row.code.replaceAll(' ','').length>60)))throw Error('选词读音格式无效');
  if(records.some(row=>row.pinned!==undefined&&typeof row.pinned!=='boolean'))throw Error('词条来源格式无效');
  return records;
}
export function learnWord(records,key,word,code=''){
  if(!/[\u3400-\u9fff]/.test(word))return records;
  if(code)key='p:'+code.replaceAll(' ','');
  const previous=records.find(row=>row.key===key&&row.word===word);
  const row={key,word,hits:Math.min(1000000,(previous?.hits||0)+1)};
  if(code||previous?.code)row.code=code||previous.code;
  validateLearning([row]);
  return [row,...records.filter(item=>item.key!==key||item.word!==word)];
}
export function learnSelection(records,chain,raw,word,code,now){
  records=learnWord(records,'p:'+raw.toLowerCase(),word,code);
  if(!code)return {records,chain:[]};
  chain=chain.filter(row=>now>=row.at&&now-row.at<=30000);
  chain=[...chain,{word,code,at:now}];
  while(chain.reduce((size,row)=>size+row.word.length,0)>16||chain.reduce((size,row)=>size+row.code.replaceAll(' ','').length,0)>60)chain.shift();
  const seen=new Set([word]);
  for(let start=chain.length-2;start>=0;start--){
    const phrase=chain.slice(start).map(row=>row.word).join(''),spelling=chain.slice(start).map(row=>row.code).join(' ');
    if(!seen.has(phrase))records=learnWord(records,'p:'+spelling.replaceAll(' ',''),phrase,spelling);
    seen.add(phrase);
  }
  return {records,chain};
}
export function rankLearned(words,records,key){
  const scores=new Map(),valid=new Set(words);
  for(const row of records)if(valid.has(row.word)&&(row.key===key||row.key.slice(0,2)===key.slice(0,2)))scores.set(row.word,(scores.get(row.word)||0)+row.hits);
  const learned=[...scores].filter(([,hits])=>hits>=2).sort((a,b)=>b[1]-a[1]).map(([word])=>word);
  return [...new Set([...learned,...words])];
}

// 删除和摇杆共用按住加速曲线；持续方向计时，转向或松手重新起步。
export function repeatDelay(step){return Math.max(35,110-Math.max(0,step)*8);}
export function joystickHoldRate(elapsed){return Math.min(24,1000/repeatDelay(Math.floor(Math.max(0,elapsed)/110)));}
