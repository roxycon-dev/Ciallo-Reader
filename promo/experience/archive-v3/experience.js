(() => {
  'use strict';
  const $ = (s, root = document) => root.querySelector(s);
  const $$ = (s, root = document) => [...root.querySelectorAll(s)];
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const icon = name => `<svg class="icon" aria-hidden="true"><use href="#i-${name}"/></svg>`;
  const clamp = (n, lo, hi) => Math.max(lo, Math.min(hi, n));
  const escape = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const books = {
    comic: { title: '无职转生', kind: '漫画节选', units: '页', pages: ['assets/comic-first.webp', 'assets/comic-page.webp'] },
    novel: { title: '星灯来信', kind: '原创示例小说', units: '章', pages: [
      {title:'第一章 · 雨停之前', text:[
        '雨从傍晚落到深夜，沿着窗框缓缓滑下。林澈把台灯调暗，桌上的信封便像一小片安静的月光。它没有邮戳，也没有寄件人的名字，只在封口处留了一枚银色的星。',
        '她认得那枚星。十年前，外婆教她辨认冬天的天空时，总会指着最亮的一颗说：“如果有一天你找不到路，就先找它。”那时她以为所有的路都能在地图上画出来。后来她离开海边的小城，才知道有些路要走很久，才会忽然在心里出现。',
        '信纸很薄，展开时几乎透出灯光。上面只有一句话：明天日出前，到旧天文台来。',
        '窗外的街道仍浸在雨里，远处最后一班电车亮着暖黄色的灯。林澈把信重新折好，放进大衣口袋。她并不知道天文台里有什么；但那枚星让她想起一个很久没有问出口的问题。']},
      {title:'第二章 · 城市的另一面', text:[
        '清晨五点，街上只剩送面包的车和扫地的声音。雨后的空气很凉，屋檐滴落的水在石阶上敲出极轻的节拍。林澈穿过空荡的广场，朝山坡上的圆顶走去。',
        '天文台已经关闭多年。铁门上的漆剥落了，指示牌也被风吹得微微倾斜。她正想寻找别的入口，门却从里面开了一道缝。守门人没有问她的名字，只把一盏小灯交到她手里，说：“沿着光走。”',
        '走廊很长，两侧挂着旧照片。照片里的城市比现在矮，河岸没有高楼，渡口还有一排帆船。林澈停在一张泛黄的夜景前：外婆年轻时站在镜头边缘，身后是一架指向天空的望远镜。',
        '她第一次发现，自己熟悉的城市还有另一面。那些被新路覆盖的旧路，那些消失在楼群后的星空，并没有真正离开；它们只是等待有人再看一眼。']},
      {title:'第三章 · 星图', text:[
        '圆顶下没有仪器，只有一张摊开的星图。纸张边缘卷起，笔迹却像刚刚写下。每一颗星都用细线连到一个地方：旧书店的后院、海边的灯塔、外婆曾带她走过的长桥。',
        '林澈把灯放低，看到星图角落有一行小字：光抵达我们之前，已经走过很远的路。',
        '她忽然明白那封信没有要求她寻找什么。它只是邀请她再出发一次。日出从圆顶的缝隙里落下，细小的尘埃在光里缓慢漂浮。她伸手碰了碰纸上的长桥，仿佛还能听见那年夏天，外婆在桥头喊她的名字。',
        '新的白昼开始了。林澈收起星图，推开通向山坡的门。远处的海面亮成一条线，城市的轮廓正在晨光中一点点清晰。她知道还有许多故事没有读完，而脚下这条路，正好通往它们。']}
    ] }
  };
  const catalogue = [
    {id:'comic', source:'comic', label:'示例漫画源 01', alternate:false},
    {id:'novel', source:'novel', label:'示例小说源 01'},
    {id:'comic', source:'comic', label:'示例漫画源 02', alternate:true},
    {id:'novel', source:'custom', label:'我的示例书源'}
  ];
  const captions = {
    sources: ['漫画、小说，多源聚合。','支持自定义书源导入，喜欢的世界，由你来扩展。','shelf'],
    reader: ['在线打开，直接入戏。','小说排版与漫画阅读，都能找到舒服的方式。','comic'],
    downloads: ['把喜欢，留在身边。','应用支持章节下载与任务管理，离线也能接着读。','download'],
    history: ['你读过的每一页，都算数。','阅读记录、阅读时长与统计，让投入的时间留下足迹。',null]
  };
  const storageKey = 'ciallo-feature-experience-v2';
  let persisted = {}, storageAvailable = true;
  try { persisted = JSON.parse(localStorage.getItem(storageKey) || '{}') || {}; }
  catch { storageAvailable = false; }
  const state = { tab:'sources', book:'comic', page:0, filter:'all', query:'', offline:false, sources:{comic:true,novel:true,custom:false}, history:{}, downloads:{}, paper:'light', font:18 };
  for (const id of Object.keys(books)) {
    const record = persisted.history?.[id];
    if (record && Number.isInteger(record.page) && Number.isFinite(record.time)) state.history[id] = {page:clamp(record.page,0,books[id].pages.length-1),time:record.time};
    const job = persisted.downloads?.[id];
    if (job && Number.isFinite(job.progress)) state.downloads[id] = {progress:clamp(job.progress,0,100),status:job.progress>=100?'done':'paused'};
  }
  function save() {
    try { localStorage.setItem(storageKey, JSON.stringify({history:state.history,downloads:state.downloads})); }
    catch { storageAvailable = false; }
  }
  let toastTimer;
  function toast(message) {
    clearTimeout(toastTimer); $('#toast').textContent = message; $('#toast').classList.add('show');
    toastTimer = setTimeout(() => $('#toast').classList.remove('show'), 3300);
  }
  function cover(id, alternate=false) {
    if (id === 'novel') return '<span class="cover novel-cover"><strong>星灯<br>来信</strong><b>✦</b><small>LETTERS<br>OF LIGHT</small></span>';
    return `<span class="cover ${alternate?'cover-alternate':''}"><img src="${alternate?'assets/comic-detail-b.webp':'assets/comic-detail-a.webp'}" alt="来自实机的漫画节选"></span>`;
  }
  function renderSources() {
    $$('[data-source]').forEach(b => b.setAttribute('aria-pressed', String(state.sources[b.dataset.source])));
    $$('[data-filter]').forEach(b => b.setAttribute('aria-pressed', String(state.filter===b.dataset.filter)));
    const results = state.offline ? [] : catalogue.filter(entry => state.sources[entry.source] && (state.filter==='all'||entry.id===state.filter) && (books[entry.id].title+books[entry.id].kind).toLowerCase().includes(state.query.toLowerCase().trim()));
    $('#book-query').disabled = state.offline;
    $('#search-form button').disabled = state.offline;
    $('#result-count').textContent = state.offline?'模拟离线中':`${results.length} 个示例结果`;
    $('#source-state').textContent = state.offline?'离线书籍仍可阅读':`${Object.values(state.sources).filter(Boolean).length} 类示例书源已启用`;
    $('#add-source').textContent = state.sources.custom?'示例书源已加入 ✓':'+ 添加示例书源';
    if (!results.length) {
      $('#search-results').innerHTML = state.offline
        ? `<div class="empty-results">${icon('wifi')}<strong>没有网络，也有故事。</strong><p>切到离线列表，打开已经下载的书。</p><button class="text-button" data-go="downloads">去离线列表 ${icon('arrow')}</button></div>`
        : `<div class="empty-results">${icon('search')}<strong>换个关键词，继续发现。</strong><p>试试「星灯」或「无职转生」，也可以启用更多示例书源。</p><button class="text-button" id="reset-search">显示全部示例</button></div>`;
    } else $('#search-results').innerHTML = results.map(entry => `<button class="book-result" data-open-book="${entry.id}" aria-label="在线阅读 ${books[entry.id].title}，来自${entry.label}">${cover(entry.id,entry.alternate)}<span class="book-open">${icon('arrow')}</span><strong>${books[entry.id].title}</strong><small>${entry.label} · ${entry.id==='comic'?'漫画':'小说'}</small></button>`).join('');
  }
  function canRead(id) { return !state.offline || state.downloads[id]?.status==='done'; }
  function recordPage() {
    state.history[state.book] = {page:state.page,time:Date.now()}; save();
    $('.record-dot').hidden = false;
  }
  function renderReader(record = state.tab==='reader') {
    const book = books[state.book], available = canRead(state.book);
    $('#reading-title').textContent = book.title;
    $('#reading-kind').textContent = `${state.offline?'离线 · ':''}${book.kind}`;
    $('#reader-canvas').dataset.paper = state.book==='novel'?state.paper:'light';
    $('#reading-page').style.setProperty('--font-size', `${state.font}px`);
    $('#reader-settings').hidden = state.book!=='novel';
    $$('[data-book]').forEach(b => b.setAttribute('aria-pressed',String(b.dataset.book===state.book)));
    if (!available) {
      $('#reading-page').innerHTML = `<div class="empty-results">${icon('down')}<strong>这本还没加入离线。</strong><p>返回在线状态下载，或阅读已完成下载的作品。</p><button class="text-button" data-go="downloads">查看离线列表 ${icon('arrow')}</button></div>`;
    } else if (state.book==='comic') {
      $('#reading-page').innerHTML = `<img class="comic-sheet" src="${book.pages[state.page]}" alt="实机录屏中的漫画节选，第 ${state.page+1} 幅">`;
    } else {
      const page = book.pages[state.page];
      $('#reading-page').innerHTML = `<article class="novel-sheet" tabindex="0" aria-label="小说正文，可向下滚动"><small>星灯来信 / 原创示例</small><h4>${page.title}</h4>${page.text.map(p=>`<p>${p}</p>`).join('')}</article>`;
    }
    $('#page-number').textContent = available?state.page+1:'—';
    $('#page-total').textContent = book.pages.length;
    $('#prev-page').disabled = !available||state.page===0;
    $('#next-page').disabled = !available||state.page===book.pages.length-1;
    const job = state.downloads[state.book];
    $('#reader-download span').textContent = job?.status==='done'?'已可离线 · 查看':job?'查看下载进度':'加入离线列表';
    $('#reader-save-status').hidden = !available;
    $('#reader-save-status').innerHTML = `${icon('check')}${storageAvailable?'进度已记下':'本次进度已记下'}`;
    if (available && record) recordPage();
  }
  function setTab(tab, focus=false) {
    if (!captions[tab]) return;
    state.tab = tab; $('.experience-shell').dataset.active = tab;
    $$('[data-tab]').forEach(b => { const selected = b.dataset.tab===tab; b.setAttribute('aria-selected',String(selected)); b.tabIndex=selected?0:-1; });
    $$('.feature-panel').forEach(p => p.hidden = p.id!==`panel-${tab}`);
    const [lead, text, proof] = captions[tab];
    $('#feature-caption').innerHTML = `<strong>${lead}</strong>${text}`;
    $('#feature-proof').hidden = !proof;
    if (proof) $('#feature-proof').dataset.gallery = proof;
    if (tab==='sources') renderSources();
    if (tab==='reader') renderReader();
    if (tab==='downloads') renderDownloads();
    if (tab==='history') renderHistory();
    if (focus) $(`#tab-${tab}`).focus({preventScroll:true});
  }
  function openBook(id, page) {
    if (!books[id]) return;
    if (!canRead(id)) { setTab('downloads'); toast('这本尚未下载，先切回在线状态加入离线列表。'); return; }
    state.book=id; state.page=clamp(page ?? state.history[id]?.page ?? 0,0,books[id].pages.length-1);
    setTab('reader');
  }
  function turnPage(delta) {
    if (!canRead(state.book)) return;
    const next = clamp(state.page+delta,0,books[state.book].pages.length-1);
    if (next===state.page) return;
    state.page=next; renderReader();
  }

  function downloadLabel(job) {
    return job.status==='done'?'已完成 · 离线可读':job.status==='paused'?`已暂停 · ${Math.floor(job.progress)}%`:`下载演示中 · ${Math.floor(job.progress)}%`;
  }
  function updateDownloadArt() {
    const jobs=Object.values(state.downloads), progress=jobs.length?jobs.reduce((sum,j)=>sum+j.progress,0)/jobs.length:0;
    $('.ring-progress').style.strokeDashoffset=270.18*(1-progress/100);
    const complete=jobs.length>0&&jobs.every(j=>j.status==='done');
    $('#download-orb-icon').innerHTML=icon(complete?'check':'down');
    $('#download-art-caption').textContent=complete?'喜欢的故事，已经在身边。':jobs.some(j=>j.status==='downloading')?'一点一点，把喜欢留下。':'把喜欢，带在身边。';
    $('#queue-badge').hidden=!jobs.length;
    $('#queue-badge').textContent=jobs.length;
  }
  function renderDownloads() {
    const entries=Object.entries(state.downloads);
    $('#download-list').setAttribute('aria-live','off');
    $('#download-list').innerHTML=entries.length?entries.map(([id,job])=>`<div class="download-item" data-job="${id}"><div class="download-item-top"><div><strong>${books[id].title}</strong><small data-job-status>${downloadLabel(job)}</small></div><div class="download-item-actions">${job.status==='done'?`<button data-offline-read="${id}">打开 ${icon('chevron')}</button>`:`<button data-pause-download="${id}" ${state.offline?'disabled':''}>${job.status==='downloading'?'暂停':'继续'}</button>`}<button class="cancel-download" data-cancel-download="${id}" aria-label="${job.status==='done'?'移除离线示例':'取消下载演示'} ${books[id].title}">${icon('close')}</button></div></div><div class="download-track" role="progressbar" aria-label="${books[id].title} 下载演示进度" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${Math.floor(job.progress)}"><i style="width:${job.progress}%"></i></div></div>`).join(''):`<div class="download-empty">${icon('down')}<span>离线列表，等一本你喜欢的书。</span></div>`;
    const next = !state.downloads.comic?'comic':!state.downloads.novel?'novel':null;
    $('#sample-download').hidden=!next;
    $('#sample-download').dataset.id=next||'comic';
    $('#sample-download').innerHTML=`${icon('plus')}加入示例${next==='novel'?'小说':'漫画'}`;
    $('#sample-download').disabled=state.offline;
    $('#offline-action').innerHTML=`${state.offline?'返回在线':'体验离线'} ${icon('arrow')}`;
    updateDownloadArt();
  }
  function addDownload(id) {
    if (!books[id]) return;
    if (state.downloads[id]) { setTab('downloads'); return; }
    if (state.offline) { toast('当前为模拟离线。切回在线后，即可添加下载。'); return; }
    state.downloads[id]={progress:0,status:'downloading'}; save(); setTab('downloads');
    toast(`「${books[id].title}」已加入下载演示，可随时暂停。`);
  }
  function setOffline(offline) {
    state.offline=offline;
    if (offline) Object.values(state.downloads).forEach(job=>{if(job.status==='downloading')job.status='paused';});
    $('#network-toggle').setAttribute('aria-pressed',String(offline));
    $('#network-toggle span').textContent=offline?'模拟离线':'在线';
    renderSources(); renderDownloads();
    if (state.tab==='reader') renderReader(false);
    save();
    toast(offline?'已切换模拟离线，完成下载的作品仍能阅读。':'已回到在线，可继续搜索或恢复下载。');
  }
  let lastDownloadTick=performance.now();
  setInterval(()=>{
    const now=performance.now(), delta=Math.min(now-lastDownloadTick,500); lastDownloadTick=now;
    if(document.hidden||state.offline)return;
    let completed=false;
    for(const [id,job]of Object.entries(state.downloads)) {
      if(job.status!=='downloading')continue;
      job.progress=Math.min(100,job.progress+delta/85);
      if(job.progress>=100){job.status='done';completed=true;toast(`「${books[id].title}」离线演示已就绪。试试切到模拟离线。`);}
      const row=$(`[data-job="${id}"]`);
      if(row){$('[data-job-status]',row).textContent=downloadLabel(job);$('.download-track i',row).style.width=`${job.progress}%`;$('.download-track',row).setAttribute('aria-valuenow',String(Math.floor(job.progress)));}
    }
    updateDownloadArt();
    if(completed){save();renderDownloads();if(state.tab==='reader')renderReader(false);}
  },200);
  function renderHistory() {
    const entries=Object.entries(state.history).sort((a,b)=>b[1].time-a[1].time);
    $('#history-count').textContent=entries.length;
    $('#clear-history').hidden=!entries.length;
    $('.record-dot').hidden=!entries.length;
    $('#history-list').innerHTML=entries.length?entries.map(([id,record])=>{
      const book=books[id], recently=Date.now()-record.time<3600000;
      const time=recently?'刚刚读过':new Date(record.time).toLocaleDateString('zh-CN',{month:'long',day:'numeric'});
      return `<article class="history-card">${cover(id)}<div class="history-card-main"><small>${escape(time)} · ${book.kind}</small><strong>${book.title}</strong><div class="history-progress"><span style="width:${(record.page+1)/book.pages.length*100}%"></span></div><div class="history-card-bottom"><span>读到第 ${record.page+1} ${book.units} / ${book.pages.length} ${book.units}</span><button class="text-button" data-resume-book="${id}">继续阅读 ${icon('chevron')}</button></div></div></article>`;
    }).join(''):`<div class="history-empty"><img src="assets/roxy.webp" alt="Roxy 在等你开始阅读"><h4>你的故事，从这一页开始。</h4><p>打开一本书，翻过的页码就会留在这里。</p><button class="text-button" data-open-book="comic">去读一页 ${icon('arrow')}</button></div>`;
  }
  const shots={
    shelf:['assets/shelf.webp','我的书架。','喜欢的小说和漫画，放在一起。'],
    comic:['assets/comic-native.webp','故事，正在发生。','来自用户实机的漫画阅读画面。'],
    download:['assets/download-native.webp','喜欢的，留下来。','实机录制中的章节下载与任务管理。'],
    glass:['assets/glass.webp','光，透过界面。','应用里的玻璃层次与阅读设置。'],
    layout:['assets/novel-controls.webp','读得更合心意。','实机小说阅读设置面板的原始裁切。']
  };
  const gallery=$('#screen-gallery');
  let galleryTrigger;
  function showShot(name) {
    if(!shots[name])return;
    const [src,title,description]=shots[name];
    $('#gallery-title').textContent=title;$('#gallery-description').textContent=description;
    $('#gallery-image').src=src;$('#gallery-image').alt=`Ciallo实机画面：${title}`;
    $('.gallery-visual').classList.toggle('layout-shot',name==='layout');
    $$('[data-shot]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.shot===name)));
  }
  function openGallery(name,trigger) {
    galleryTrigger=trigger;showShot(name);gallery.showModal();document.body.style.overflow='hidden';
    $('#gallery-close').focus();
  }
  gallery.addEventListener('close',()=>{document.body.style.overflow='';galleryTrigger?.focus({preventScroll:true});});
  gallery.addEventListener('click',e=>{if(e.target===gallery){const r=gallery.getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)gallery.close();}});
  $('#gallery-close').addEventListener('click',()=>gallery.close());

  document.addEventListener('click',e=>{
    const b=e.target.closest('button,a');if(!b)return;
    if(b.dataset.tab)setTab(b.dataset.tab);
    if(b.dataset.go)setTab(b.dataset.go,true);
    if(b.dataset.featureLink)setTab(b.dataset.featureLink);
    if(b.dataset.source){const id=b.dataset.source;state.sources[id]=!state.sources[id];renderSources();}
    if(b.dataset.filter){state.filter=b.dataset.filter;renderSources();}
    if(b.dataset.openBook)openBook(b.dataset.openBook);
    if(b.dataset.resumeBook)openBook(b.dataset.resumeBook,state.history[b.dataset.resumeBook]?.page);
    if(b.dataset.offlineRead)openBook(b.dataset.offlineRead);
    if(b.dataset.book)openBook(b.dataset.book);
    if(b.dataset.paper){state.paper=b.dataset.paper;$$('[data-paper]').forEach(el=>el.setAttribute('aria-pressed',String(el.dataset.paper===state.paper)));$('#reader-canvas').dataset.paper=state.paper;}
    if(b.dataset.pauseDownload){const job=state.downloads[b.dataset.pauseDownload];if(job&&!state.offline){job.status=job.status==='downloading'?'paused':'downloading';save();renderDownloads();$(`[data-pause-download="${b.dataset.pauseDownload}"]`)?.focus({preventScroll:true});}}
    if(b.dataset.cancelDownload){delete state.downloads[b.dataset.cancelDownload];save();renderDownloads();$('#sample-download').focus({preventScroll:true});toast('已从本页离线列表移除。');}
    if(b.dataset.gallery)openGallery(b.dataset.gallery,b);
    if(b.dataset.shot)showShot(b.dataset.shot);
    if(b.dataset.format){const format=b.dataset.format;$$('[data-format]').forEach(el=>el.setAttribute('aria-pressed',String(el===b)));$('#format-front').innerHTML=`${format}<span>THE NEXT<br>CHAPTER.</span>`;const descriptions={TXT:'导入 TXT 文本，从一段文字开启一个世界。',EPUB:'导入 EPUB 电子书，把藏书放进自己的书架。',CBZ:'导入 CBZ 漫画，喜欢的每一格都能随身带。',ZIP:'导入 ZIP 漫画压缩包，让收藏变成自己的书架。',PDF:'导入 PDF 漫画，在画面里发现更多故事。'};$('#format-description').textContent=descriptions[format];if(!reduced.matches)$('#format-front').animate([{transform:'translateX(-50%) rotate(-5deg) translateZ(25px)'},{transform:'translateX(-50%) rotate(0deg) translateZ(25px)'}],{duration:440,easing:'cubic-bezier(.2,.75,.2,1)'});}
    if(b.id==='reset-search'){state.query='';state.filter='all';state.sources.comic=true;state.sources.novel=true;$('#book-query').value='';renderSources();}
  });
  $('.feature-tabs').addEventListener('keydown',e=>{
    const tabs=$$('[data-tab]'), index=tabs.indexOf(document.activeElement);
    if(index<0)return;
    const next=e.key==='ArrowRight'?(index+1)%tabs.length:e.key==='ArrowLeft'?(index+tabs.length-1)%tabs.length:e.key==='Home'?0:e.key==='End'?tabs.length-1:null;
    if(next!==null){e.preventDefault();setTab(tabs[next].dataset.tab,true);}
  });
  $('#search-form').addEventListener('submit',e=>{e.preventDefault();state.query=$('#book-query').value;renderSources();});
  $('#book-query').addEventListener('input',e=>{state.query=e.target.value;renderSources();});
  $('#add-source').addEventListener('click',()=>{if(state.sources.custom){toast('自定义示例源已启用，可用旁边的开关管理。');return;}state.sources.custom=true;state.filter='all';state.query='';$('#book-query').value='';renderSources();toast('示例书源已加入，聚合结果也随之更新。');});
  $('#prev-page').addEventListener('click',()=>turnPage(-1));
  $('#next-page').addEventListener('click',()=>turnPage(1));
  $('#reader-canvas').addEventListener('keydown',e=>{if(e.target.matches('input'))return;if(e.key==='ArrowRight'){e.preventDefault();turnPage(1);}if(e.key==='ArrowLeft'){e.preventDefault();turnPage(-1);}});
  let swipeStart=null;
  $('#reading-page').addEventListener('pointerdown',e=>{if(e.pointerType==='touch')swipeStart={x:e.clientX,y:e.clientY};});
  $('#reading-page').addEventListener('pointerup',e=>{if(!swipeStart)return;const dx=e.clientX-swipeStart.x,dy=e.clientY-swipeStart.y;swipeStart=null;if(Math.abs(dx)>55&&Math.abs(dx)>Math.abs(dy)*1.6)turnPage(dx<0?1:-1);});
  $('#reading-page').addEventListener('pointercancel',()=>swipeStart=null);
  $('#font-size').addEventListener('input',e=>{state.font=Number(e.target.value);$('#font-size-label').textContent=state.font;$('#reading-page').style.setProperty('--font-size',`${state.font}px`);});
  $('#reader-download').addEventListener('click',()=>addDownload(state.book));
  $('#sample-download').addEventListener('click',e=>addDownload(e.currentTarget.dataset.id));
  $('#network-toggle').addEventListener('click',()=>setOffline(!state.offline));
  $('#offline-action').addEventListener('click',()=>setOffline(!state.offline));
  $('#clear-history').addEventListener('click',()=>{state.history={};save();renderHistory();toast('本页的阅读记录已清除。');});

  // A genuine draggable CSS glass surface over original manga panels.
  const playground=$('#glass-playground'), card=$('#glass-card');
  const drag={x:0,y:0,startX:0,startY:0,originX:0,originY:0,active:false,id:null};
  function positionGlass(x,y){
    const halfW=card.offsetWidth/2,halfH=card.offsetHeight/2,baseX=card.offsetLeft,baseY=card.offsetTop;
    drag.x=clamp(x,halfW-baseX+12,playground.clientWidth-halfW-baseX-12);
    drag.y=clamp(y,halfH-baseY+12,playground.clientHeight-halfH-baseY-98);
    card.style.setProperty('--drag-x',`${drag.x}px`);card.style.setProperty('--drag-y',`${drag.y}px`);
  }
  card.addEventListener('pointerdown',e=>{
    if(e.button!==0||e.target.closest('#glass-continue'))return;
    drag.active=true;drag.id=e.pointerId;drag.startX=e.clientX;drag.startY=e.clientY;drag.originX=drag.x;drag.originY=drag.y;
    card.setPointerCapture(e.pointerId);card.classList.add('dragging');
  });
  card.addEventListener('pointermove',e=>{if(drag.active&&e.pointerId===drag.id)positionGlass(drag.originX+e.clientX-drag.startX,drag.originY+e.clientY-drag.startY);});
  const endDrag=()=>{drag.active=false;card.classList.remove('dragging');};
  card.addEventListener('pointerup',endDrag);card.addEventListener('pointercancel',endDrag);card.addEventListener('lostpointercapture',endDrag);
  $('#glass-handle').addEventListener('keydown',e=>{const directions={ArrowLeft:[-18,0],ArrowRight:[18,0],ArrowUp:[0,-18],ArrowDown:[0,18]};if(directions[e.key]){e.preventDefault();const [x,y]=directions[e.key];positionGlass(drag.x+x,drag.y+y);}});
  $('#reset-glass').addEventListener('click',()=>positionGlass(0,0));
  $('#glass-softness').addEventListener('input',e=>playground.style.setProperty('--glass-blur',`${e.target.value}px`));
  playground.addEventListener('pointermove',e=>{if(reduced.matches)return;const r=playground.getBoundingClientRect();playground.style.setProperty('--light-x',`${(e.clientX-r.left)/r.width*100}%`);playground.style.setProperty('--light-y',`${(e.clientY-r.top)/r.height*100}%`);});
  $('#glass-continue').addEventListener('click',()=>{openBook('novel');$('#experience').scrollIntoView({behavior:reduced.matches?'instant':'smooth'});});
  let roxyTimer;
  $('#say-hello').addEventListener('click',()=>{
    clearTimeout(roxyTimer);$('#roxy-character').src='assets/roxy-hello.webp';$('.roxy-card').classList.remove('greeting');
    requestAnimationFrame(()=>$('.roxy-card').classList.add('greeting'));
    $('#roxy-speech').textContent=Object.keys(state.history).length?'再读一页吧～':'Ciallo～ 一起读书吧！';
    roxyTimer=setTimeout(()=>{$('.roxy-card').classList.remove('greeting');$('#roxy-character').src='assets/roxy.webp';},3500);
  });
  const audio=$('#ambient-audio');audio.volume=.22;
  $('#sound-toggle').addEventListener('click',async()=>{
    if(audio.paused){try{await audio.play();$('#sound-toggle').setAttribute('aria-pressed','true');$('#sound-toggle').setAttribute('aria-label','暂停背景音乐');}catch{toast('音乐暂时无法播放，请再点一次。');}}
    else{audio.pause();$('#sound-toggle').setAttribute('aria-pressed','false');$('#sound-toggle').setAttribute('aria-label','播放背景音乐');}
  });
  document.addEventListener('visibilitychange',()=>{lastDownloadTick=performance.now();if(document.hidden){save();if(!audio.paused){audio.pause();$('#sound-toggle').setAttribute('aria-pressed','false');$('#sound-toggle').setAttribute('aria-label','播放背景音乐');}}});
  addEventListener('pagehide',save);

  const revealObserver=new IntersectionObserver(entries=>entries.forEach(entry=>{if(entry.isIntersecting){entry.target.classList.add('visible');revealObserver.unobserve(entry.target);}}),{threshold:.08});
  $$('.reveal').forEach(el=>revealObserver.observe(el));
  document.documentElement.classList.add('js-ready');
  let scheduled=false;
  function updateScroll(){
    scheduled=false;if(reduced.matches)return;
    const r=$('.hero-scene').getBoundingClientRect();
    if(r.bottom>0&&r.top<innerHeight)$('.phone-cluster').style.setProperty('--spread',clamp((innerHeight*.62-r.top)/600,0,1).toFixed(3));
  }
  addEventListener('scroll',()=>{if(!scheduled){scheduled=true;requestAnimationFrame(updateScroll);}},{passive:true});
  $('.hero-scene').addEventListener('pointermove',e=>{if(reduced.matches||e.pointerType!=='mouse')return;const r=e.currentTarget.getBoundingClientRect();$('.phone-cluster').style.setProperty('--tilt',`${(e.clientX-r.left-r.width/2)/r.width*8}deg`);});
  $('.hero-scene').addEventListener('pointerleave',()=>$('.phone-cluster').style.setProperty('--tilt','0deg'));
  addEventListener('resize',()=>{positionGlass(drag.x,drag.y);updateScroll();});
  renderSources();renderReader(false);renderDownloads();renderHistory();updateScroll();
  // The cinematic layer has its own timeline; it does not fabricate user reading history.
  const cinema=$('.cinema'), scenes=$$('[data-scene]'), chapterButtons=$$('[data-film-chapter]');
  const durations=[4.5,7,6,6,7,6,6,7.5], starts=durations.map((_,i)=>durations.slice(0,i).reduce((a,b)=>a+b,0));
  const totalDuration=durations.reduce((a,b)=>a+b,0);
  const film={elapsed:0,playing:!reduced.matches,scene:-1,last:performance.now(),visible:true,ready:false,comicPage:0,manualComic:false,manualType:false,font:16,warm:false};
  const darkScenes=new Set([1,4,6]);
  function playbackUI(){
    cinema.classList.toggle('is-paused',!film.playing);
    $('#film-play').setAttribute('aria-pressed',String(film.playing));
    $('#film-play').setAttribute('aria-label',film.playing?'暂停自动演示':film.elapsed>=totalDuration?'重播产品演示':'继续自动演示');
    $('.film-play-icon').textContent=film.playing?'Ⅱ':film.elapsed>=totalDuration?'↻':'▶';
  }
  function pauseFilm(){film.playing=false;playbackUI();}
  function playFilm(){if(film.elapsed>=totalDuration)film.elapsed=0;film.playing=true;film.last=performance.now();playbackUI();}
  function showFilmComic(page,animate=false){
    const img=$('#film-comic-image'),src=books.comic.pages[page];
    if(film.comicPage===page&&img.dataset.page===String(page))return;
    film.comicPage=page;img.dataset.page=String(page);img.src=src;$('#film-comic-number').textContent=String(page+1).padStart(2,'0');
    if(animate&&!reduced.matches){img.getAnimations().forEach(a=>a.cancel());img.animate([{opacity:.2,transform:'translateX(28px) rotateY(-13deg)'},{opacity:1,transform:'translateX(0) rotateY(0)'}],{duration:580,easing:'cubic-bezier(.2,.75,.2,1)'});}
  }
  function renderFilm(){
    let index=starts.findLastIndex(start=>film.elapsed>=start);index=Math.max(0,index);
    const local=clamp((film.elapsed-starts[index])/durations[index],0,1);
    if(index!==film.scene){
      film.scene=index;film.manualComic=false;film.manualType=false;
      cinema.classList.toggle('dark',darkScenes.has(index));cinema.dataset.scene=String(index);
      scenes.forEach((scene,i)=>{const current=i===index;scene.classList.toggle('is-active',current);scene.setAttribute('aria-hidden',String(!current));scene.inert=!current;});
      chapterButtons.forEach((b,i)=>{if(i===index)b.setAttribute('aria-current','step');else b.removeAttribute('aria-current');});
      if(index===2)showFilmComic(0);
    }
    scenes[index].style.setProperty('--t',local.toFixed(4));
    chapterButtons.forEach((b,i)=>b.style.setProperty('--fill',clamp((film.elapsed-starts[i])/durations[i],0,1).toFixed(4)));
    $('#film-time').textContent=`00:${String(Math.floor(film.elapsed)).padStart(2,'0')}`;
    if(index===1){const text='漫画 · 小说 · 一起发现';$('#film-query').textContent=text.slice(0,Math.floor(clamp(local*3.5,0,1)*text.length));}
    if(index===2&&!film.manualComic)showFilmComic(local<.53?0:1,true);
    if(index===3&&!film.manualType){$('.film-paper').style.setProperty('--film-font',`${local>.38?18:16}px`);$('.film-paper').classList.toggle('warm',local>.68);}
    if(index===4){const progress=Math.round(clamp((local-.12)/.69,0,1)*100);$('#film-download-percent').innerHTML=`${progress}<small>%</small>`;$('#film-download-ring').style.strokeDashoffset=477.52*(1-progress/100);$('#film-offline-status').textContent=progress===100?'离线就绪。故事，随时继续。':'正在留下喜欢的故事';$('.offline-vault .icon use').setAttribute('href',progress===100?'#i-check':'#i-down');}
  }
  $('#film-play').addEventListener('click',()=>film.playing?pauseFilm():playFilm());
  chapterButtons.forEach(b=>b.addEventListener('click',()=>{film.elapsed=starts[Number(b.dataset.filmChapter)]+.01;film.last=performance.now();playFilm();renderFilm();}));
  function leaveFilm(target='sources'){
    pauseFilm();
    if(target==='glass'){$('#material').scrollIntoView({behavior:reduced.matches?'instant':'smooth'});return;}
    if(target==='novel')openBook('novel');else setTab(target);
    $('#experience').scrollIntoView({behavior:reduced.matches?'instant':'smooth'});
  }
  $('#leave-film').addEventListener('click',()=>leaveFilm());
  $$('[data-try]').forEach(b=>b.addEventListener('click',()=>leaveFilm(b.dataset.try)));
  $('#film-turn-comic').addEventListener('click',()=>{pauseFilm();film.manualComic=true;showFilmComic(film.comicPage===0?1:0,true);});
  function editFilmType(delta){pauseFilm();film.manualType=true;film.font=clamp(film.font+delta,13,22);$('.film-paper').style.setProperty('--film-font',`${film.font}px`);}
  $('#film-type-smaller').addEventListener('click',()=>editFilmType(-2));
  $('#film-type-larger').addEventListener('click',()=>editFilmType(2));
  $('#film-paper-toggle').addEventListener('click',()=>{pauseFilm();film.manualType=true;film.warm=!$('.film-paper').classList.contains('warm');$('.film-paper').classList.toggle('warm',film.warm);});
  $('#film-fullscreen').addEventListener('click',async()=>{try{if(document.fullscreenElement)await document.exitFullscreen();else await cinema.requestFullscreen();}catch{toast('此浏览器暂不支持全屏，可以直接观看。');}});
  addEventListener('wheel',()=>{if(film.visible&&film.playing)pauseFilm();},{passive:true});
  let filmTouchStart;
  cinema.addEventListener('touchstart',e=>{filmTouchStart=e.touches[0]?.clientY;},{passive:true});
  cinema.addEventListener('touchmove',e=>{if(Math.abs((e.touches[0]?.clientY??0)-filmTouchStart)>15)pauseFilm();},{passive:true});
  $$('a[href^="#"]').forEach(a=>a.addEventListener('click',()=>{if(a.getAttribute('href')!=='#home')pauseFilm();}));
  const filmObserver=new IntersectionObserver(entries=>{film.visible=entries[0].isIntersecting;document.body.classList.toggle('film-visible',film.visible);if(!film.visible)pauseFilm();},{threshold:.12});filmObserver.observe(cinema);
  chapterButtons.forEach(b=>{b.setAttribute('aria-label',`播放${$('span',b).textContent}章节`);b.title=$('span',b).textContent;});
  document.addEventListener('visibilitychange',()=>{if(document.hidden)pauseFilm();film.last=performance.now();});
  reduced.addEventListener('change',()=>{if(reduced.matches)pauseFilm();});

  // A small deterministic particle field: source convergence, page dust, and light trails.
  const canvas=$('#cinema-particles'), ctx=canvas.getContext('2d');
  let width=0,height=0,ratio=1;
  const pointer={x:-999,y:-999}, particles=Array.from({length:150},(_,i)=>({x:((i*73+19)%997)/997,y:((i*131+47)%991)/991,speed:.18+((i*17)%31)/100,size:.4+((i*11)%17)/13,phase:i*2.39996}));
  function sizeCanvas(){const r=cinema.getBoundingClientRect();width=r.width;height=r.height;ratio=Math.min(devicePixelRatio||1,1.7);canvas.width=Math.round(width*ratio);canvas.height=Math.round(height*ratio);ctx?.setTransform(ratio,0,0,ratio,0,0);}
  cinema.addEventListener('pointermove',e=>{const r=cinema.getBoundingClientRect();pointer.x=e.clientX-r.left;pointer.y=e.clientY-r.top;});
  cinema.addEventListener('pointerleave',()=>{pointer.x=-999;pointer.y=-999;});
  const canvasObserver=new ResizeObserver(sizeCanvas);canvasObserver.observe(cinema);sizeCanvas();
  function drawParticles(){
    if(!ctx)return;ctx.clearRect(0,0,width,height);if(reduced.matches)return;
    const dark=darkScenes.has(film.scene), count=width<760?76:150, time=film.elapsed;
    const centerX=width*(width<760?.53:.735),centerY=height*(width<760?.6:.48);
    ctx.globalCompositeOperation=dark?'lighter':'source-over';
    for(let i=0;i<count;i++){
      const p=particles[i];let x,y,alpha;
      if(film.scene===1){const phase=(p.x+time*.075*p.speed)%1,radius=(1-phase)*(width<760?260:480);const a=p.phase+time*.04;x=centerX+Math.cos(a)*radius;y=centerY+Math.sin(a)*radius*.62;alpha=Math.sin(phase*Math.PI)*.46;}
      else if(film.scene===4){const a=p.phase+time*.16,r=(width<760?85:175)+p.x*100;x=centerX+Math.cos(a)*r;y=centerY+Math.sin(a)*r*.68;alpha=.1+p.y*.24;}
      else{ x=(p.x*width+Math.sin(time*.24+p.phase)*23+time*p.speed*4)%width;y=(p.y*height-time*(2+p.speed*3)+height*2)%height;alpha=dark?.08+p.y*.3:.07+p.y*.2;}
      const dx=x-pointer.x,dy=y-pointer.y,d=Math.hypot(dx,dy);if(d<130){x+=dx/Math.max(d,1)*(1-d/130)*22;y+=dy/Math.max(d,1)*(1-d/130)*22;alpha=Math.min(.7,alpha+.2);}
      ctx.fillStyle=dark?`rgba(167,225,192,${alpha})`:`rgba(68,125,97,${alpha*.65})`;
      ctx.beginPath();ctx.arc(x,y,p.size*(dark?1:.7),0,Math.PI*2);ctx.fill();
      if(dark&&i%6===0){ctx.strokeStyle=`rgba(195,239,207,${alpha*.38})`;ctx.lineWidth=.6;ctx.beginPath();ctx.moveTo(x-4,y);ctx.lineTo(x+4,y);ctx.moveTo(x,y-4);ctx.lineTo(x,y+4);ctx.stroke();}
      if(film.scene===1&&i%3===0){const angle=Math.atan2(centerY-y,centerX-x);ctx.strokeStyle=`rgba(136,216,175,${alpha*.22})`;ctx.lineWidth=.7;ctx.beginPath();ctx.moveTo(x,y);ctx.lineTo(x+Math.cos(angle)*17,y+Math.sin(angle)*17);ctx.stroke();}
    }
    ctx.globalCompositeOperation='source-over';
  }
  function filmFrame(now){
    const delta=Math.min((now-film.last)/1000,.08);film.last=now;
    if(film.visible&&!document.hidden){
      if(film.playing&&film.ready){film.elapsed=Math.min(totalDuration,film.elapsed+delta);if(film.elapsed>=totalDuration)pauseFilm();renderFilm();}
      drawParticles();
    }
    requestAnimationFrame(filmFrame);
  }
  renderFilm();playbackUI();requestAnimationFrame(filmFrame);
  Promise.all([document.fonts.ready,...$$('.intro-product img').map(img=>img.decode?.().catch(()=>{}))]).then(()=>{film.ready=true;film.last=performance.now();});
})();
