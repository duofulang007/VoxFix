let dictionary=Object.create(null), initialDictionary=Object.create(null), keys=[], characters={}, syllables=[];
let mixedBuckets=Object.create(null), shortPrefixes=Object.create(null), nineKeys=null;
let personal=[], packs={}, enabledPacks=[];
let pinyinCache=null,nineCache=null;
let wordStarts=Object.create(null),clipReadings=new Map(),clipReadingSize=0,clipOverrides=Object.create(null);
const digits='22233344455566677778889999';
const toNine=code=>code.replace(/[a-z]/g,char=>digits[char.charCodeAt(0)-97]);
const unique=rows=>[...new Set(rows.map(row=>row[0]))];
function respectsBoundaries(row,input){
  if(!input.includes("'"))return true;
  if(!row[2])return false;
  const ends=new Set();let position=0;
  for(const part of row[2]){position+=part.length;ends.add(position);}
  position=0;
  return input.split("'").slice(0,-1).every(part=>{position+=part.length;return ends.has(position);});
}
function push(index,key,row){(index[key]??=[]).push(row);}
export function setPinyinData(data,initials={}) {
  dictionary=Object.create(null);initialDictionary=Object.create(null);mixedBuckets=Object.create(null);shortPrefixes=Object.create(null);nineKeys=null;
  wordStarts=Object.create(null);
  characters=data.characters||{};syllables=data.syllables||[];
  if(data.schema===2){
    for(const [word,freq,ids] of data.entries){
      const parts=ids.map(id=>syllables[id]),code=parts.join(''),row=[word,freq,parts];
      push(dictionary,code,row);
      push(wordStarts,word[0],row);
      if(parts.length>1){push(initialDictionary,parts.map(s=>s[0]).join(''),row);push(mixedBuckets,code[0],row);}
      if(word.length===1&&parts.length===1)for(let n=1;n<=Math.min(2,code.length);n++)push(shortPrefixes,code.slice(0,n),row);
    }
  }else{
    dictionary=Object.assign(Object.create(null),data);initialDictionary=Object.assign(Object.create(null),initials);
    for(const rows of Object.values(dictionary))for(const row of rows)if(row[0].length>1)push(wordStarts,row[0][0],row);
  }
  keys=Object.keys(dictionary).sort();
  clipReadings.clear();clipReadingSize=0;clipOverrides=Object.create(null);
  for(const rows of Object.values(wordStarts))for(const row of rows)if(row[0].length>1&&row[2]?.length===row[0].length&&row[2].some((part,i)=>part!==syllables[characters[row[0][i]]]))push(clipOverrides,row[0][0],row);
  for(const rows of Object.values(clipOverrides))rows.sort((a,b)=>b[0].length-a[0].length||b[1]-a[1]);
  for(const rows of Object.values(shortPrefixes))rows.sort((a,b)=>b[1]-a[1]);
  pinyinCache=null;nineCache=null;
}
// 候选面板左侧“相近拼音”列使用：合法音节判定。
export function isSyllable(value){return syllables.includes(value);}
export async function loadPinyin(light=false){
  const data=typeof embeddedPinyinData!=='undefined'?embeddedPinyinData:await (await fetch(light?'pinyin-lite.json':'pinyin-data.json')).json();
  setPinyinData(data);
  packs=typeof embeddedPhrasePacks!=='undefined'?embeddedPhrasePacks:await (await fetch('phrase-packs.json')).json();
}
export function setPhraseOptions(phrases=[],enabled=[]){
  personal=phrases.map(text=>{
    const parts=[...text].filter(c=>/[\u3400-\u9fff]/.test(c)).map(c=>syllables[characters[c]]);
    return parts.length&&parts.every(Boolean)?[text,200000,parts]:null;
  }).filter(Boolean);
  enabledPacks=enabled.filter(name=>Object.hasOwn(packs,name));
  pinyinCache=null;nineCache=null;
}
export function phraseSearch(query=''){
  return [...new Set([...personal.map(row=>row[0]),...enabledPacks.flatMap(name=>packs[name].map(row=>row[0]))])].filter(text=>text.includes(query));
}
function extraRows(){return [...personal,...enabledPacks.flatMap(name=>packs[name].map(([text,code])=>[text,200000,code.split(' ')]))];}
export function phraseCandidates(input){
  const raw=input.toLowerCase().replace(/'/g,'');
  if(!/^[a-z]{1,100}$/.test(raw)||!input.includes("'")&&syllables.some(s=>s.startsWith(raw)))return [];
  return unique(extraRows().filter(row=>(row[2].join('')===raw||row[2].map(s=>s[0]).join('')===raw||mixedMatch(row[2],raw))&&respectsBoundaries(row,input)));
}
export function associationCandidates(context,limit=30){
  const tail=context.match(/[\u3400-\u9fff]+$/)?.[0]?.slice(-6);if(!tail)return [];
  const words=[];
  for(let length=tail.length;length>0;length--){
    const prefix=tail.slice(-length),rows=[...extraRows(),...(wordStarts[prefix[0]]||[])].filter(row=>row[0].startsWith(prefix)&&row[0].length>prefix.length).sort((a,b)=>b[1]-a[1]);
    for(const row of rows){const suffix=row[0].slice(prefix.length);if(!words.includes(suffix))words.push(suffix);if(words.length>=limit)return words;}
  }
  return words;
}
export function pinyinConsumed(input,word){pinyinCandidates(input);return pinyinCache?.query===input.toLowerCase()?pinyinCache.consumed?.get(word)??input.length:input.length;}
export function hasPinyinMatch(input){pinyinCandidates(input);return pinyinCache?.query===input.toLowerCase()&&Boolean(pinyinCache.matched);}
function lowerBound(value){let left=0,right=keys.length;while(left<right){const middle=(left+right)>>1;if(keys[middle]<value)left=middle+1;else right=middle;}return left;}
function mixedMatch(parts,raw){
  let positions=new Set([0]);
  for(const part of parts){
    const next=new Set();
    const tokens=new Set([part,part[0],/^[zcs]h/.test(part)?part.slice(0,2):part[0]]);
    for(const position of positions)for(const token of tokens)if(raw.startsWith(token,position))next.add(position+token.length);
    positions=next;if(!positions.size)return false;
  }
  return positions.has(raw.length);
}
export function clipboardMatch(input,text){
  return clipboardMatches(input,[text])[0]?.text||'';
}
function clipRuns(text){
  if(clipReadings.has(text))return clipReadings.get(text);
  const runs=[];let run=[];
  for(let i=0;i<text.length;){
    const char=String.fromCodePoint(text.codePointAt(i));
    const row=clipOverrides[char]?.find(row=>text.startsWith(row[0],i));
    const parts=row?.[2]||[syllables[characters[char]]];
    if(parts.every(Boolean)){for(let j=0;j<parts.length;j++)run.push({part:parts[j],start:i+j,end:i+j+1});i+=row?.[0].length||char.length;}
    else{if(run.length)runs.push(run);run=[];i+=char.length;}
  }
  if(run.length)runs.push(run);
  while(clipReadings.size&&(clipReadings.size>=300||clipReadingSize+text.length>200000)){const oldest=clipReadings.keys().next().value;clipReadingSize-=oldest.length;clipReadings.delete(oldest);}
  clipReadingSize+=text.length;
  clipReadings.set(text,runs);return runs;
}
function oneEdit(a,b){
  if(Math.abs(a.length-b.length)>1)return false;
  let i=0,j=0,edits=0;
  while(i<a.length&&j<b.length){if(a[i]===b[j]){i++;j++;continue;}if(++edits>1)return false;if(a.length>=b.length)i++;if(b.length>=a.length)j++;}
  return edits+(a.length-i)+(b.length-j)<=1;
}
function clipToken(input,text,manual){
  const token=input.toLowerCase(),literal=text.toLowerCase().indexOf(token);
  if(literal>=0){const ranges=[];for(let at=literal;at>=0;at=text.toLowerCase().indexOf(token,at+token.length))ranges.push([at,at+token.length]);return {start:literal,end:literal+token.length,ranges,score:400,weak:false};}
  if(manual&&/^[\u3400-\u9fff]{2,32}$/.test(token)){
    const ranges=[];let at=0;
    for(const char of token){const index=text.indexOf(char,at);if(index<0)return null;ranges.push([index,index+1]);at=index+1;}
    return {start:ranges[0][0],end:at,ranges,score:150,weak:true};
  }
  const raw=token.replace(/'/g,'');if(!/^[a-z]{2,60}$/.test(raw)&&!(manual&&/^[a-z]$/.test(raw)))return null;
  let best=null;
  for(const run of clipRuns(text))for(let start=0;start<run.length;start++){
    let positions=new Map([[0,0]]),full='';
    for(let end=start;end<run.length&&end-start<raw.length+1;end++){
      const part=run[end].part,next=new Map();full+=part;
      for(const [at,cost] of positions){
        for(const piece of new Set([part,part[0],/^[zcs]h/.test(part)?part.slice(0,2):part[0]]))if(raw.startsWith(piece,at)){
          const to=at+piece.length,penalty=cost+(piece===part?0:1);
          if(!next.has(to)||next.get(to)>penalty)next.set(to,penalty);
        }
        if(end>start&&raw.length-at>=2&&part.startsWith(raw.slice(at)))next.set(raw.length,Math.min(next.get(raw.length)??99,cost+1));
      }
      positions=next;
      const cost=positions.get(raw.length),count=end-start+1;
      if(cost!==undefined&&(count>=2||manual||cost===0&&raw.length>=3)){
        const parts=run.slice(start,end+1).map(x=>x.part);
        if(respectsBoundaries(['',0,parts],token)){const hit={start:run[start].start,end:run[end].end,score:300-Math.min(40,cost*10),weak:false};if(!best||hit.score>best.score)best=hit;}
      }
      if(raw.length>=4&&count>=2&&Math.abs(full.length-raw.length)<=1&&oneEdit(raw,full)&&!token.includes("'")){
        const hit={start:run[start].start,end:run[end].end,score:100,weak:true};if(!best||best.score<hit.score)best=hit;
      }
      if(!positions.size&&full.length>raw.length+1)break;
    }
  }
  return best;
}
export function clipboardMatches(input,texts,{manual=false,recent=false}={}){
  const tokens=input.trim().toLowerCase().split(/\s+/).filter(Boolean);
  if(!manual&&(!tokens.length||tokens.some(token=>token.replace(/'/g,'').length<2)))return [];
  if(input.length>100)return [];
  const hits=[];
  for(const [order,text] of [...new Set(texts)].entries()){
    if(!text.trim()||!manual&&/验证码|密码|口令|密钥|https?:\/\/|www\.|[\w.+-]+@[\w.-]+|\d{6,}/i.test(text))continue;
    const matches=tokens.map(token=>clipToken(token,text,manual));if(matches.some(hit=>!hit))continue;
    const start=matches.length?Math.min(...matches.map(hit=>hit.start)):0,end=matches.length?Math.max(...matches.map(hit=>hit.end)):0;
    hits.push({text,start,end,ranges:matches.flatMap(hit=>hit.ranges||[[hit.start,hit.end]]),score:matches.length?Math.min(...matches.map(hit=>hit.score)):0,weak:matches.some(hit=>hit.weak),order});
  }
  return hits.sort((a,b)=>recent? a.order-b.order:b.score-a.score||a.order-b.order);
}
export function selectionCode(input,word,preedit='',records=[]){
  if(input.length>60||!/^[\u3400-\u9fff]{1,16}$/.test(word))return '';
  const raw=input.toLowerCase().replace(/'/g,''),parts=preedit.trim().split(/[\s']+/).slice(0,word.length);
  if(parts.length===word.length&&parts.every(part=>syllables.includes(part))&&parts.join('')===raw)return parts.join(' ');
  const codes=new Set((wordStarts[word[0]]||[]).filter(row=>row[0]===word&&row[2]&&mixedMatch(row[2],raw)&&respectsBoundaries(row,input)).map(row=>row[2].join(' ')));
  for(const row of records)if(row.word===word&&row.code&&mixedMatch(row.code.split(' '),raw)&&respectsBoundaries([word,0,row.code.split(' ')],input))codes.add(row.code);
  // shortcut: ambiguous or absent readings keep exact-input memory; never guess a personal word's pronunciation.
  const code=codes.size===1?[...codes][0]:'';
  return code.replaceAll(' ','').length<=60?code:'';
}
export function learnedCandidates(input,records,limit=240){
  const query=input.toLowerCase(),raw=query.replace(/'/g,'');
  if(!/^[a-z]{1,60}$/.test(raw))return [];
  const single=!query.includes("'")&&syllables.some(part=>part.startsWith(raw));
  return [...new Set(records.filter(row=>row.hits>=2&&row.key.startsWith('p:')&&(!single||row.word.length===1)&&
    (row.key==='p:'+query||row.code&&mixedMatch(row.code.split(' '),raw)&&respectsBoundaries([row.word,0,row.code.split(' ')],query)))
    .sort((a,b)=>b.hits-a.hits).map(row=>row.word))].slice(0,limit);
}
export function learnedAssociations(context,records,limit=240){
  const tail=context.match(/[\u3400-\u9fff]+$/)?.[0]?.slice(-16);if(!tail)return [];
  const words=[];
  for(let size=tail.length;size>0;size--){
    const prefix=tail.slice(-size);
    for(const row of records.filter(row=>row.code&&row.hits>=2&&row.word.startsWith(prefix)&&row.word.length>size).sort((a,b)=>b.hits-a.hits)){
      const suffix=row.word.slice(size);if(!words.includes(suffix))words.push(suffix);if(words.length>=limit)return words;
    }
  }
  return words;
}
export function nineCandidates(input,limit=240){
  const raw=input.replace(/'/g,'');if(!/^[2-9]{1,60}$/.test(raw))return [];
  if(nineCache?.raw===raw&&nineCache.limit>=limit)return nineCache.words.slice(0,limit);
  if(!nineKeys)nineKeys=keys.map(key=>[toNine(key),key]).sort((a,b)=>a[0]<b[0]?-1:a[0]>b[0]?1:0);
  let left=0,right=nineKeys.length;while(left<right){const middle=(left+right)>>1;if(nineKeys[middle][0]<raw)left=middle+1;else right=middle;}
  const exact=[],prefix=[];
  for(let i=left;i<nineKeys.length&&nineKeys[i][0].startsWith(raw);i++)(nineKeys[i][0]===raw?exact:prefix).push(...dictionary[nineKeys[i][1]]);
  for(const row of extraRows()){const code=toNine(row[2].join(''));if(code===raw)exact.unshift(row);else if(code.startsWith(raw))prefix.unshift(row);}
  exact.sort((a,b)=>b[1]-a[1]);prefix.sort((a,b)=>b[1]-a[1]);
  const words=unique([...exact,...prefix]).slice(0,limit);nineCache={raw,limit,words};return [...words];
}
export function pinyinCandidates(input,limit=240){
  const query=input.toLowerCase(),raw=query.replace(/'/g,'');if(!/^[a-z]{1,100}$/.test(raw))return [];
  if(pinyinCache?.query===query&&pinyinCache.limit>=limit)return pinyinCache.words.slice(0,limit);
  const extras=extraRows();
  const extraExact=extras.filter(row=>row[2].join('')===raw||row[2].map(s=>s[0]).join('')===raw||mixedMatch(row[2],raw));
  const exact=dictionary[raw]||[],initials=initialDictionary[raw]||[],prefix=shortPrefixes[raw]?[...shortPrefixes[raw]]:[];
  if(!prefix.length)for(let i=lowerBound(raw);i<keys.length&&keys[i].startsWith(raw);i++)prefix.push(...dictionary[keys[i]]);
  prefix.sort((a,b)=>b[1]-a[1]);
  if(!query.includes("'")&&syllables.some(syllable=>syllable.startsWith(raw))){
    const words=unique([...extraExact,...exact,...prefix].filter(row=>row[0].length===1).sort((a,b)=>b[1]-a[1])).slice(0,limit);
    pinyinCache={query,limit,words,matched:words.length>0};return [...words];
  }
  const mixed=[];
  if(raw.length>=3&&!exact.length)for(const row of mixedBuckets[raw[0]]||[]){if(mixedMatch(row[2],raw))mixed.push(row);if(mixed.length>=limit)break;}
  // shortcut: local word frequencies provide no semantic model; upgrade the decoder if sentence ranking remains insufficient.
  const best=Array(raw.length+1).fill(null);best[0]={text:'',score:0};
  const repeated=/^([a-z])\1+$/.test(raw);
  if(!query.includes("'")&&!exact.length&&((!initials.length&&!mixed.length)||repeated)){
    for(let start=0;start<raw.length;start++){
      if(!best[start])continue;
      for(let end=start+1;end<=Math.min(raw.length,start+60);end++){
        const code=raw.slice(start,end),abbreviated=/^([bpmfdtnlgkhjqxrzcswy]|[zcs]h)$/.test(code)&&(end===raw.length||/[bpmfdtnlgkhjqxrzcswy]/.test(raw[end]));
        const rows=abbreviated?(shortPrefixes[code]||dictionary[code]||[]):dictionary[code]||[];
        for(const [word,freq] of rows.slice(0,3)){
          const score=best[start].score+word.length*2+Math.log10(freq+1)-8;
          if(!best[end]||score>best[end].score)best[end]={text:best[start].text+word,score};
        }
      }
    }
  }
  const assembled=best[raw.length]&&!exact.length&&((!initials.length&&!mixed.length)||repeated)?[[best[raw.length].text,0]]:[];
  let words=unique([...extraExact,...exact,...(repeated?assembled:[]),...initials,...mixed,...(!repeated?assembled:[]),...prefix].filter(row=>respectsBoundaries(row,query))).slice(0,limit);
  const consumed=new Map();
  if(!words.length||assembled.length){
    for(let end=raw.length-1;end>0;end--){
      const rows=(dictionary[raw.slice(0,end)]||[]).filter(row=>respectsBoundaries(row,query));
      const text=!query.includes("'")?best[end]?.text:null;
      if(!rows.length&&!text)continue;
      const partial=[...new Set([...(text?[text]:[]),...unique(rows)])];
      const full=new Set(words);words=[...new Set([...words,...partial])].slice(0,limit);
      let count=0,position=0;while(count<end){if(query[position]!=="'")count++;position++;}while(query[position]==="'")position++;
      for(const word of partial)if(!full.has(word))consumed.set(word,position);break;
    }
  }
  const matched=[...extraExact,...exact,...initials,...mixed].some(row=>respectsBoundaries(row,query));
  pinyinCache={query,limit,words,consumed,matched};return [...words];
}
