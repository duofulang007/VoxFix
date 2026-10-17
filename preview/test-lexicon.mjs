// Browser fixtures use the real persistent store; old localStorage is migration input only.
export const lexiconRecords=page=>page.evaluate(async()=>{
  if(!(await indexedDB.databases()).some(db=>db.name==='voxfix-personal-lexicon'))return [];
  return new Promise((resolve,reject)=>{
    const request=indexedDB.open('voxfix-personal-lexicon',1);
    request.onerror=()=>reject(request.error);
    request.onsuccess=()=>{const db=request.result,tx=db.transaction('entries');tx.objectStore('entries').getAll().onsuccess=e=>resolve(e.target.result);tx.oncomplete=()=>db.close();tx.onerror=()=>reject(tx.error);};
  });
});
