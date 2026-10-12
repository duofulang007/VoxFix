let words=[];
export function setEnglishWords(values){words=[...new Set(values.filter(word=>/^[a-z][a-z'-]{1,59}$/.test(word)))];}
export async function loadEnglish(){const text=typeof embeddedEnglishWords!=='undefined'?embeddedEnglishWords:await (await fetch('english-words.txt')).text();setEnglishWords(text.split(/\r?\n/));}
export async function loadPersonalEnglish(){const response=await fetch('personal-english.txt');if(response.ok)setEnglishWords([...words,...(await response.text()).split(/\r?\n/).map(word=>word.toLowerCase())]);}
export function hasEnglishWord(input){return words.includes(input.toLowerCase());}
export function englishCandidates(prefix,limit=30){
  if(!/^[a-z]{1,60}$/i.test(prefix))return [];
  const raw=prefix.toLowerCase(),matches=words.filter(word=>word.startsWith(raw)).sort((a,b)=>a.length-b.length||a.localeCompare(b));
  const upper=prefix.length>1&&prefix===prefix.toUpperCase();
  return [...new Set([prefix,...matches.map(word=>upper?word.toUpperCase():/^[A-Z]/.test(prefix)?word[0].toUpperCase()+word.slice(1):word)])].slice(0,limit);
}
