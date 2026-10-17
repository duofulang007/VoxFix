import {validateLearning} from './core.mjs';

export const lexiconMaxBytes=32*1024*1024;
export const lexiconId=row=>`${row.code?'p:'+row.code.replaceAll(' ',''):row.key}\0${row.word}`;
export function parseLexicon(text){
  if(new TextEncoder().encode(text).length>lexiconMaxBytes)throw Error('词库文件超过32MiB，请分批导入');
  text=text.replace(/^\uFEFF/,'');
  if(text.trimStart().startsWith('{')){
    const value=JSON.parse(text);
    if(value.format!=='voxfix-personal-lexicon'||value.version!==1)throw Error('不支持的词库备份版本');
    validateLearning(value.entries);
    const legacy=value.legacy??{};
    if(!legacy||typeof legacy!=='object'||Array.isArray(legacy)||Object.entries(legacy).some(([key,text])=>!(/^(rime:[a-zA-Z0-9_.-]+\.tsv|personal-dictionary|personal-english)$/.test(key))||typeof text!=='string'))throw Error('词库附加数据无效');
    return {...value,legacy};
  }
  const entries=[];
  for(const line of text.split(/\r?\n/)){
    if(!line.trim()||line.startsWith('#'))continue;
    const parts=line.split('\t'),code=parts[1]?.trim(),hits=parts.length===3?Number(parts[2]):2;
    if(parts.length<2||parts.length>3||!parts[2]?.trim()&&parts.length===3)throw Error('文本词库须为：词语、空格分隔拼音、可选词频（Tab分隔）');
    entries.push({key:'p:'+code.replaceAll(' ',''),word:parts[0],code,hits,pinned:true});
  }
  return {format:'voxfix-personal-lexicon',version:1,entries:validateLearning(entries),legacy:{}};
}
export function lexiconTsv(records){
  const merged=new Map();
  for(const row of validateLearning(records))if(row.code){const id=lexiconId(row);if(!merged.has(id)||merged.get(id).hits<row.hits)merged.set(id,row);}
  return '# VoxFix personal lexicon: word\tpinyin\tfrequency\n'+[...merged.values()].sort((a,b)=>a.word.localeCompare(b.word)||a.code.localeCompare(b.code)).map(row=>`${row.word}\t${row.code}\t${row.hits}\n`).join('');
}

export function createPersonalLexicon(nativeIme,onError){
  const native=typeof nativeIme?.lexiconLookup==='function';
  const entries=new Map(),buckets=new Map(),words=new Map();
  let db,legacy={},revision=0,ready=false,cacheRevision='',cache=new Map(),pending=Promise.resolve();
  const reply=(method,...args)=>{const value=JSON.parse(nativeIme[method](...args));if(value.error)throw Error(value.error);return value;};
  function put(row,increment=false){
    const id=lexiconId(row),old=entries.get(id),next={...old,...row,key:id.split('\0')[0],hits:Math.min(1000000,increment?(old?.hits||0)+row.hits:Math.max(old?.hits||0,row.hits))};
    if(old?.pinned)next.pinned=true;
    entries.set(id,next);
    const bucket=next.key.slice(0,3),word=next.word[0];
    if(!buckets.has(bucket))buckets.set(bucket,new Map());buckets.get(bucket).set(id,next);
    if(next.code){const nine='9:'+'22233344455566677778889999'[next.code.charCodeAt(0)-97];if(!buckets.has(nine))buckets.set(nine,new Map());buckets.get(nine).set(id,next);}
    if(!words.has(word))words.set(word,new Map());words.get(word).set(id,next);
    return next;
  }
  const transaction=(stores,mode,run)=>new Promise((resolve,reject)=>{
    const tx=db.transaction(stores,mode);tx.oncomplete=()=>resolve();tx.onabort=tx.onerror=()=>reject(tx.error||Error('词库保存失败'));
    try{run(tx);}catch(error){tx.abort();reject(error);}
  });
  function queueSave(rows,meta){
    pending=pending.catch(()=>{}).then(()=>transaction(['entries','metadata'],'readwrite',tx=>{
      const store=tx.objectStore('entries');for(const row of rows)store.put(row,lexiconId(row));
      if(meta)tx.objectStore('metadata').put(meta,'legacy');
    }));
    pending.catch(onError);return pending;
  }
  const initialized=(async()=>{
    let old=[];
    const saved=localStorage.getItem('voxfix-learning-v1');if(saved)old=validateLearning(JSON.parse(saved));
    if(native){if(!reply('lexiconMigrate',JSON.stringify(old)).ok)throw Error('旧记忆尚未迁移');try{localStorage.removeItem('voxfix-learning-v1');}catch(error){onError(error);}ready=true;return;}
    db=await new Promise((resolve,reject)=>{const request=indexedDB.open('voxfix-personal-lexicon',1);request.onupgradeneeded=()=>{request.result.createObjectStore('entries');request.result.createObjectStore('metadata');};request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);});
    let migrated=false;
    await transaction(['entries','metadata'],'readonly',tx=>{
      tx.objectStore('entries').getAll().onsuccess=e=>{for(const row of e.target.result)put(row);};
      tx.objectStore('metadata').get('legacy').onsuccess=e=>{legacy=e.target.result||{};};
      tx.objectStore('metadata').get('web-v1').onsuccess=e=>{migrated=!!e.target.result;};
    });
    if(!migrated||old.length){
      await transaction(['entries','metadata'],'readwrite',tx=>{for(const row of old){const next=put(row);tx.objectStore('entries').put(next,lexiconId(next));}tx.objectStore('metadata').put(true,'web-v1');});
    }
    try{localStorage.removeItem('voxfix-learning-v1');}catch(error){onError(error);}
    ready=true;
  })();
  function nativeRows(kind,value){
    try{
      const current=nativeIme.lexiconRevision();if(current!==cacheRevision){cacheRevision=current;cache.clear();}
      const key=kind+value;if(!cache.has(key))cache.set(key,validateLearning(reply(kind,value).rows));
      // Only live query buckets are cached; the database retains every entry.
      if(cache.size>32){const latest=cache.get(key);cache.clear();cache.set(key,latest);}
      return cache.get(key);
    }catch(error){onError(error);return [];}
  }
  return {
    initialized,
    lookup(raw){if(!ready)return [];return native?nativeRows('lexiconLookup',raw):[...(buckets.get((/^[2-9]/.test(raw)?'9:':'p:')+raw[0])?.values()||[])];},
    associations(context){if(!ready)return [];if(native)return nativeRows('lexiconAssociations',context);const tail=context.match(/[\u3400-\u9fff]+$/)?.[0]?.slice(-16)||'';return [...new Map([...tail].flatMap(c=>[...(words.get(c)||[])])).values()];},
    learn(rows){
      validateLearning(rows);if(!ready)throw Error('个人词库尚未就绪，请稍后重试');
      if(native){if(!reply('lexiconLearn',JSON.stringify(rows)).ok)throw Error('个人词库未保存');cache.clear();return;}
      const changed=rows.map(row=>put(row,true));revision++;queueSave(changed);
    },
    async import(text){await initialized;await pending;const value=parseLexicon(text);
      // Commit the complete merge before changing the active in-memory index.
      const unique=new Map();for(const row of value.entries){const id=lexiconId(row),old=unique.get(id)||entries.get(id);const next={...old,...row,key:id.split('\0')[0],hits:Math.max(old?.hits||0,row.hits)};if(old?.pinned)next.pinned=true;unique.set(id,next);}const merged=[...unique.values()];
      const extra={...value.legacy,...legacy};await queueSave(merged,extra);for(const row of merged)put(row);legacy=extra;revision++;
    },
    async backup(){await initialized;await pending;return JSON.stringify({format:'voxfix-personal-lexicon',version:1,entries:[...entries.values()],legacy});},
    async tsv(){await initialized;await pending;return lexiconTsv([...entries.values()]);},
    get ready(){return ready;},get revision(){return native?nativeIme.lexiconRevision():revision;},
  };
}
