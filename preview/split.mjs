let clipboardSplitterPromise;

export function createClipboardSplitter(data){
  const weights=new Map();let total=0,maxLength=1;
  for(const [word,frequency] of data.entries||[]){
    if(!/^\p{Script=Han}+$/u.test(word)||!Number.isFinite(frequency)||frequency<=0)continue;
    weights.set(word,Math.max(weights.get(word)||0,frequency));maxLength=Math.max(maxLength,[...word].length);
  }
  for(const frequency of weights.values())total+=frequency;
  const logTotal=Math.log(total||1);
  function chinese(text){
    const chars=[...text],scores=new Float64Array(chars.length+1),next=new Uint32Array(chars.length);
    for(let i=chars.length-1;i>=0;i--){
      scores[i]=scores[i+1]-logTotal;next[i]=i+1;let word='';
      for(let j=i;j<Math.min(chars.length,i+maxLength);j++){
        word+=chars[j];const frequency=weights.get(word);if(!frequency)continue;
        const score=Math.log(frequency)-logTotal+scores[j+1];if(score>=scores[i]){scores[i]=score;next[i]=j+1;}
      }
    }
    const words=[];for(let i=0;i<chars.length;i=next[i])words.push(chars.slice(i,next[i]).join(''));return words;
  }
  return text=>{
    const words=[];
    for(const part of text.match(/https?:\/\/[^\s]+|\p{Script=Han}+|[A-Za-z0-9]+|\s+|[^\s]/gu)||[]){
      if(/^\p{Script=Han}/u.test(part)){words.push(...chinese(part));continue;}
      const url=part.match(/^(https?:\/\/)([A-Za-z0-9.-]+)(.*)$/su);
      if(url){const host=url[2].split('.');words.push(url[1],...host.slice(0,-2).map(p=>p+'.'),host.slice(-2).join('.'),...(url[3].match(/[A-Za-z0-9]+|[^\s]/gu)||[]));}
      else words.push(part);
    }
    return words;
  };
}

export function loadClipboardSplitter(){
  return clipboardSplitterPromise??=Promise.resolve().then(async()=>{
    const data=typeof embeddedPinyinData!=='undefined'?embeddedPinyinData:await (await fetch('pinyin-data.json')).json();
    if(data.schema!==2||!Array.isArray(data.entries))throw Error('拆词词库不可用');
    return createClipboardSplitter(data);
  }).catch(error=>{clipboardSplitterPromise=null;throw error;});
}
