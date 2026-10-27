const escape = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

export function createMemeSearch({update, insert, request = (url, options) => fetch(url, options)}) {
  const state={query:'',items:[],page:0,more:false,loading:false,error:'',preview:null};
  let active=false,controller=null,generation=0,timer=null;
  const image=(id,thumb=false)=>`/meme/image/${encodeURIComponent(id)}${thumb?'?thumb=1':''}`;
  function cancel(){clearTimeout(timer);timer=null;controller?.abort();generation++;}
  async function load(append=false) {
    cancel();if(!active||!state.query)return;
    controller=new AbortController();const current=generation;
    state.loading=true;state.error='';state.preview=null;update();
    try {
      const page=append?state.page+1:1;
      const response=await request(`/meme/search?q=${encodeURIComponent(state.query)}&page=${page}`,{signal:controller.signal});
      const data=await response.json();
      if(current!==generation||!active)return;
      if(!response.ok||data.error)throw new Error(data.error||'搜索暂时不可用，请重试');
      const items=(data.items||[]).filter(item=>/^[a-f0-9]{24}$/.test(item.id));
      state.items=[...new Map([...(append?state.items:[]),...items].map(item=>[item.id,item])).values()];
      state.page=page;state.more=Boolean(data.more)&&page<5;
    } catch(error) {
      if(current===generation&&error.name!=='AbortError')state.error=error.message||'网络连接失败，请重试';
    } finally {if(current===generation){state.loading=false;update();}}
  }
  function change(query,delay) {
    query=String(query).trim().slice(0,80);
    if(query===state.query&&(delay||state.page>0&&!state.loading))return;
    cancel();state.query=query;state.items=[];state.page=0;state.more=false;state.error='';state.preview=null;
    if(!query){state.loading=false;update();return;}
    state.loading=active;
    if(active){if(delay)timer=setTimeout(()=>void load(),300);else void load();}
  }
  const panel={
    get query(){return state.query;},
    search:query=>change(query,false),
    schedule:query=>change(query,true),
    activate(value){if(active===value)return;active=value;if(!active){cancel();state.loading=false;}else if(state.query&&!state.page&&!state.error)void load();},
    reset(){cancel();active=false;Object.assign(state,{query:'',items:[],page:0,more:false,loading:false,error:'',preview:null});},
    action(action,value){
      if(action==='meme-more'&&!state.loading)void load(true);
      else if(action==='meme-retry')void load(state.page>0);
      else if(action==='meme-preview'){state.preview=state.items.find(item=>item.id===value)||null;update();}
      else if(action==='meme-close'){state.preview=null;update();}
      else if(action==='meme-insert'&&state.preview)insert(state.preview.id);
    },
    render(typing=false){
      const button=(text,action,value='')=>`<button data-action="${action}" data-value="${escape(value)}"${action==='meme-clear'?' aria-label="清空搜索"':action==='return'?' aria-label="返回键盘"':''}>${escape(text)}</button>`;
      const preview=state.preview;
      return `<div class="meme-panel"><div class="meme-search-row"><span aria-hidden="true">⌕</span><div class="meme-input-slot"></div>${button('×','meme-clear')}${typing?button('收起','meme-done'):button('←','return')}</div><div class="meme-results" data-query="${escape(state.query)}"><div class="meme-grid">${state.items.map(item=>`<button data-action="meme-preview" data-value="${item.id}" aria-label="预览：${escape(item.title)}"><img src="${image(item.id,true)}" alt="${escape(item.title)}" loading="lazy" referrerpolicy="no-referrer"></button>`).join('')}</div><div class="meme-status" role="status">${state.loading?'正在搜索…':state.error?`${escape(state.error)} ${button('重试','meme-retry')}`:!state.query?'输入关键词搜索表情包':!state.items.length?'没有找到表情包，换个词试试':state.more?button('加载更多','meme-more'):'已显示本次搜索结果'}<small>搜狗图片</small></div></div>${preview?`<div class="meme-preview" role="dialog" aria-label="表情包预览"><img src="${image(preview.id)}" alt="${escape(preview.title)}"><div>${button('返回','meme-close')}${button('插入聊天','meme-insert')}</div></div>`:''}</div>`;
    },
    paint(host){
      // Keep the focused search input mounted while only the results change.
      if(!host.querySelector('.meme-panel')){host.innerHTML=panel.render(true);return;}
      const fresh=host.ownerDocument.createElement('template');fresh.innerHTML=panel.render(true);
      const old=host.querySelector('.meme-results'),next=fresh.content.querySelector('.meme-results');
      if(old.dataset.query===next.dataset.query)next.scrollTop=old.scrollTop;
      const top=old.dataset.query===next.dataset.query?old.scrollTop:0;
      old.replaceWith(next);next.scrollTop=top;
      host.querySelector('.meme-preview')?.remove();
      const preview=fresh.content.querySelector('.meme-preview');if(preview)host.querySelector('.meme-panel').append(preview);
    }
  };
  return panel;
}
