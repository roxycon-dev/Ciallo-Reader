/* Ciallo / director's cut. UI images are authentic recording frames. */
(() => {
  'use strict';
  const A={sourceRows:'assets/cinema-source-rows.webp',sourceImport:'assets/cinema-source-import.webp',picker:'assets/composed-picker.webp',shelf:'assets/shelf.webp',glass:'assets/glass.webp',comic:'assets/comic-native.webp',page:'assets/comic-page.webp',first:'assets/comic-first.webp',panelA:'assets/comic-detail-a.webp',panelB:'assets/comic-detail-b.webp',panelC:'assets/comic-detail-c.webp',sources:'assets/native-source-manage.webp',more:'assets/native-source-more.webp',searchBar:'assets/search.webp',search:'assets/native-search.webp',results:'assets/native-search-more.webp',detail:'assets/native-detail.webp',chapters:'assets/composed-chapters.webp',down:'assets/composed-downloading.webp',paused:'assets/native-download-paused.webp',done:'assets/native-download-done.webp',modes:'assets/native-comic-modes.webp',image:'assets/native-comic-image.webp',translate:'assets/native-comic-translate.webp',auto:'assets/native-comic-auto.webp',gesture:'assets/native-comic-gesture.webp',background:'assets/native-comic-theme.webp',type:'assets/native-novel-type.webp',novelLayout:'assets/native-novel-layout.webp',text:'assets/native-novel-text.webp',bookmark:'assets/native-bookmark.webp',green:'assets/native-theme-green.webp',orange:'assets/native-theme-orange.webp',glassControls:'assets/native-glass-controls.webp',progress:'assets/native-progress.webp',import:'assets/kinetic-import.webp',stats:'assets/native-stats.webp',calendar:'assets/native-calendar.webp',trends:'assets/native-trends.webp',resume:'assets/native-resume.webp'};
  const $=id=>document.getElementById(id),stage=$('stage'),music=$('music');
  const clamp=(v,a=0,b=1)=>Math.max(a,Math.min(b,v)),ease=x=>{x=clamp(x);return x*x*x*(x*(6*x-15)+10)},smooth=x=>{x=clamp(x);return x*x*(3-2*x)};
  const img=(k,cls='',extra='')=>{
    const picture=`<img src="${A[k]}" alt="Ciallo 实机界面" draggable="false">`;
    const framed=!['panelA','panelB','panelC'].includes(k);
    return `<figure class="texture ${framed?'framed':''} ${cls}" ${extra}>${framed?`<div class="surface">${picture}</div>`:picture}</figure>`;
  };
  const label=(a,b='')=>`<div class="scene-label"><span>${a}</span>${b?`<span>${b}</span>`:''}</div>`;
  const heading=(text,cls='')=>`<h2 class="headline ${cls}">${text}</h2>`;
  const featureRail=[['modes','单页。双页。条漫。','模式 / 方向 / 翻页'],['image','画面，细细调。','图像调节'],['translate','翻译，按需配置。','翻译 / OCR 设置'],['auto','阅读，自在向前。','自动翻页'],['gesture','顺着你的习惯。','手势配置'],['background','给故事，一点底色。','阅读背景']];
  const SHOTS=[
    {id:'opening',duration:3.1,tone:'ink',transition:'cut',label:'给自己，一页的时间',html:`<div class="book-light"></div><div class="opening-book"><div class="book-spine"></div>${Array.from({length:5},(_,i)=>`<i class="book-leaf" style="--leaf:${i}"></i>`).join('')}${img('shelf','book-left')}${img('comic','book-right')}</div><div class="opening-copy"><p>给自己，</p><h1>一页的时间。</h1></div><p class="opening-whisper">CIALLO READER · A WORLD BETWEEN PAGES</p>`},
    {id:'worlds',duration:3.3,tone:'ink',transition:'dissolve',label:'多源聚合 / 多语言',html:`${heading('世界很大。<em>入口，很轻。</em>')}<div class="world-wall">${['shelf','search','more','results','glass'].map((k,i)=>img(k,'world-tile',`style="--i:${i};--offset:${i-2}"`)).join('')}</div>${img('picker','world-picker')}${label('聚合漫画 · 聚合小说','多语言搜索')}`},
    {id:'sources',duration:1.5,tone:'ink',transition:'cut',label:'书源管理 / JSON',html:`${heading('每一种喜欢，<em>都有来处。</em>')}${img('sourceRows','source-macro')}${img('sourceImport','source-import')}<p class="source-foot">源管理 · 自由启停 · JSON 导入</p>`},
    {id:'search',duration:2.5,tone:'ink',transition:'cut',label:'多源搜索',html:`<div class="search-heading"><span>多 元 搜 索</span>${heading('找一个名字。<br><em>遇见很多可能。</em>')}</div><div class="search-track">${['search','results'].map((k,i)=>img(k,'search-tile',`style="--i:${i}"`)).join('')}</div><div class="search-caption">一次搜索 · 跨源发现</div>`},
    {id:'chapters',duration:1.75,tone:'mint',transition:'dissolve',label:'详情 / 章节 / 在线阅读',html:`${img('detail','chapter-phone')}${img('chapters','chapter-zoom')}${heading('就是它。<em>现在，开读。</em>')}${label('作品详情 → 章节选择','在线阅读')}`},
    {id:'comic-hit',duration:.55,tone:'ink',transition:'cut',label:'漫画',html:`${img('panelC','hit-image')}${heading('走进去。','hit-word')}`},
    {id:'comic',duration:2.7,tone:'ink',transition:'cut',label:'漫画阅读 / 进度定位',html:`<div class="comic-panels">${img('panelB','comic-panel panel-one')}${img('panelA','comic-panel panel-three')}</div>${img('comic','comic-reader')}${heading('每一格，<em>都是另一个世界。</em>')}${img('progress','comic-progress')}${label('漫画阅读器','沉浸阅读 · 进度定位')}`},
    {id:'controls',duration:4.8,tone:'ink',transition:'cut',label:'漫画模式 / 翻译 / 手势',html:`<div class="rail-heading"><p class="rail-kicker">让阅读，顺着你的习惯。</p><h2 class="rail-title"></h2></div><div class="settings-track">${featureRail.map(([k,title,sub],i)=>`<div class="settings-item" style="--i:${i}">${img(k)}<p>${sub}</p></div>`).join('')}</div><div class="rail-dots">${featureRail.map(()=>'<i></i>').join('')}</div>`},
    {id:'novel-hit',duration:.75,tone:'paper',transition:'cut',label:'小说',html:`<div class="paper-rule"></div>${heading('静下来。','hit-word')}<p class="quiet-word">听见字里行间。</p>`},
    {id:'novel',duration:3,tone:'paper',transition:'dissolve',label:'小说阅读 / 排版 / 主题',html:`<div class="novel-fold"></div><div class="novel-margin">字<br>里<br>行<br>间<span>NOVEL READER</span></div>${img('text','novel-paper')}${img('type','novel-type')}${img('novelLayout','novel-layout')}<div class="novel-caption"><h2>让文字，<em>慢慢住进心里。</em></h2><p>小说阅读器 · 字号 · 行距 · 页边距 · 阅读主题</p></div>`},
    {id:'bookmark',duration:1,tone:'paper',transition:'cut',label:'书签 / Roxy',html:`${heading('舍不得的，<br><em>留在这一页。</em>')}${img('bookmark','bookmark-stamp')}<p class="bookmark-note">添加书签 · 下次再见</p>`},
    {id:'download',duration:2.4,tone:'ink',transition:'cut',label:'下载 / 暂停 / 完成',html:`<div class="download-title"><span>喜欢的故事，带在身边。</span><h2 class="download-state">正在下载。</h2></div><div class="download-states">${['down','paused','done'].map((k,i)=>img(k,'download-frame',`data-state="${i}"`)).join('')}</div><div class="download-steps"><span>下载中</span><i></i><span>已暂停</span><i></i><span>已完成</span></div>`},
    {id:'shelf',duration:1.6,tone:'ink',transition:'dissolve',label:'书架分类 / 本地导入',html:`<div class="shelf-word">把喜欢，<br><em>放在一起。</em></div>${img('shelf','shelf-plane')}${img('import','shelf-import')}${label('你的书架','分类整理 · 本地导入')}`},
    {id:'color',duration:2.3,tone:'mint',transition:'cut',label:'主题 / 强调色',html:`<div class="color-field"></div><div class="color-sculpture"><i></i><i></i><i></i></div><div class="color-type"><span>每一次打开，都像自己。</span><h2>随心</h2></div><p class="color-echo">由你</p><div class="color-device">${img('green','color-green')}${img('orange','color-orange')}</div><div class="color-swatches"><i></i><i></i><i></i><i></i></div>${label('主题色 × 强调色','自由定制')}`},
    {id:'glass',duration:2.2,tone:'ink',transition:'dissolve',label:'玻璃质感',html:`<div class="glass-halo"></div><div class="glass-prism"><i></i><i></i><i></i></div>${img('glassControls','glass-device')}${heading('光经过。<br><em>细节留下。</em>')}${label('玻璃质感','透明度 · 模糊 · 细节调节')}`},
    {id:'stats',duration:2.7,tone:'ink',transition:'dissolve',label:'日历 / 时长 / 趋势',html:`<div class="stats-heading"><span>每一页，都留下了足迹。</span><h2>原来，<em>已走过这么多故事。</em></h2></div><svg class="stats-path" viewBox="0 0 1000 200" preserveAspectRatio="none" aria-hidden="true"><path d="M0 145 C100 145 130 40 240 65 S390 160 500 95 S650 25 740 55 S900 145 1000 20"/></svg><div class="stats-cards">${['stats','calendar','trends'].map((k,i)=>`${img(k,'stats-card',`style="--i:${i}"`)}<p class="stats-name name-${i}">${['阅读时长与目标','阅读日历','趋势与时段'][i]}</p>`).join('')}</div>`},
    {id:'resume',duration:2.4,tone:'ink',transition:'dissolve',label:'继续阅读',html:`<div class="return-halo"></div>${heading('下次回来，')}<p class="resume-word">故事还在。</p>${img('resume','resume-card')}<div class="resume-rule"></div>${label('从上次停下的地方','继续阅读')}`},
    {id:'end',duration:2.8,tone:'ink',transition:'dissolve',label:'Ciallo阅读 / 获取',html:`<div class="end-page"><i></i><i></i></div><p class="end-signature">Ciallo<span>阅读</span></p>${heading('把时间，<br><em>交给好故事。</em>')}<p class="end-features">多源搜索 · 小说与漫画 · 自由定制</p><a class="download-link" href="https://github.com/a1553846342-dotcom/EASYREADER/releases" target="_blank" rel="noopener noreferrer">开启下一页 <span>↗</span></a><p class="end-note">ANDROID · 公益开源</p>`}
  ];
  let total=0;SHOTS.forEach((s,i)=>{s.start=total;s.index=i;total+=s.duration});
  const FX={sweep:new Audio('assets/swoosh-quick.mp3'),paper:new Audio('assets/paper-page-turn.mp3'),glass:new Audio('assets/glass-plate-slide.mp3')};
  const CUES=[['worlds','sweep',.10],['search','sweep',.09],['comic-hit','paper',.13],['novel-hit','paper',.09],['color','sweep',.10],['glass','glass',.12],['end','sweep',.07]].map(([id,key,volume])=>({at:SHOTS.find(s=>s.id===id).start,key,volume}));
  function stopFX(){Object.values(FX).forEach(a=>a.pause())}
  function sounds(before,after){if(!sound||after-before>.3)return;for(const c of CUES)if(before<c.at&&after>=c.at){const a=FX[c.key];a.currentTime=0;a.volume=c.volume;a.playbackRate=speed;a.play().catch(()=>{})}}
  const ACTS={opening:'序 · 给自己一页时间',worlds:'一 · 寻找',comic:'二 · 沉浸',color:'三 · 留住'};
  let act='';
  function atmosphere(s){return `<div class="scene-atmosphere" aria-hidden="true"><svg class="cinema-ribbon" viewBox="0 0 1600 900" preserveAspectRatio="none"><defs><linearGradient id="ribbon-${s.id}" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#688d77" stop-opacity="0"/><stop offset=".43" stop-color="#bcebd8" stop-opacity=".35"/><stop offset=".62" stop-color="#f4d9b0" stop-opacity=".65"/><stop offset="1" stop-color="#658f74" stop-opacity="0"/></linearGradient></defs><path class="ribbon-wide" stroke="url(#ribbon-${s.id})"/><path class="ribbon-fine" stroke="url(#ribbon-${s.id})"/></svg><div class="light-pool"></div></div>`}
  $('shots').innerHTML=SHOTS.map(s=>{act=ACTS[s.id]||act;return `<article class="scene ${s.id} ${s.tone}" aria-label="${s.label}" aria-hidden="true">${atmosphere(s)}<div class="mise">${s.html}</div><p class="act-caption">${act}</p></article>`}).join('');
  const nodes=[...document.querySelectorAll('.scene')],params=new URLSearchParams(location.search);
  const reduced=matchMedia('(prefers-reduced-motion: reduce)').matches;
  document.body.classList.toggle('reduced',reduced);document.body.classList.toggle('clean',params.has('record'));
  let time=0,playing=false,speed=1,last=0,current=-1,sound=false,raf=0,ready=false,awaitingFirstView=false;
  const fmt=v=>`00:${String(Math.floor(v)).padStart(2,'0')}`;
  $('seek').max=total;
  const cache=nodes.map(n=>({flow:n.querySelector('.ribbon-wide'),edge:n.querySelector('.ribbon-fine'),title:n.querySelector('.rail-title'),items:[...n.querySelectorAll('.settings-item')],dots:[...n.querySelectorAll('.rail-dots i')],states:[...n.querySelectorAll('.download-frame')],stateTitle:n.querySelector('.download-state'),steps:[...n.querySelectorAll('.download-steps span')]}));
  function animate(node,s,local){
    const p=clamp(local/s.duration),e=reduced?1:ease(local/.7),q=reduced?1:smooth(local/.55);
    node.style.setProperty('--p',p);node.style.setProperty('--book',reduced?1:ease((local-.18)/1.75));node.style.setProperty('--text-in',reduced?1:ease((local-.6)/.75));node.style.setProperty('--departure',reduced?0:ease((local-s.duration+.42)/.42));node.style.setProperty('--drift',smooth(p));node.style.setProperty('--e',e);node.style.setProperty('--q',q);node.style.setProperty('--t',local);
    node.style.setProperty('--arrival',reduced?1:ease((local-.55)/.8));
    const flow=cache[s.index];
    if(flow.flow){
      const t=reduced?0:(s.start+local),phase=t*.065;
      const a=620+Math.sin(phase)*100,b=390+Math.cos(phase)*70,c=760+Math.sin(phase*.7)*80;
      const edge=`M -180 ${a} C 220 ${b} 570 ${b-320} 900 390 S 1250 ${c} 1760 240`;
      flow.flow.setAttribute('d',edge);flow.edge.setAttribute('d',edge);
    }
    if(s.id==='worlds')node.style.setProperty('--reveal',reduced?1:ease((local-1.2)/.9));
    if(s.id==='controls'){
      const beat=s.duration/featureRail.length,drift=clamp(local/beat,0,5);
      // Stable central view for 0.512s, smooth travel for 0.288s; no linear velocity jumps.
      const base=Math.floor(drift),slide=reduced?base:base+ease((drift-base-.64)/.36),index=Math.round(slide);
      node.style.setProperty('--rail',slide);cache[s.index].title.textContent=featureRail[index][1];
      cache[s.index].items.forEach((el,j)=>el.style.setProperty('--distance',Math.abs(j-slide)));
      cache[s.index].dots.forEach((el,j)=>el.classList.toggle('on',j===index));
    }
    if(s.id==='download'){
      const k=local<.95?0:local<1.55?1:2,c=cache[s.index];
      c.states.forEach((el,j)=>el.classList.toggle('on',k===j));c.steps.forEach((el,j)=>el.classList.toggle('on',k===j));c.stateTitle.textContent=['正在下载。','随时暂停。','已存好。'][k];
      node.style.setProperty('--drop',reduced?1:ease(local/.75));
    }
    if(s.id==='color')node.style.setProperty('--color',reduced?1:smooth((local-1.2)/.5));
  }
  const canvas=$('particles'),ctx=canvas.getContext('2d');let w=0,h=0;
  const framedImages=[...document.querySelectorAll('.framed img')];
  function fitSurfaces(){for(const im of framedImages){const figure=im.closest('.texture');if(!im.naturalWidth)continue;const scale=Math.min(figure.clientWidth/im.naturalWidth,figure.clientHeight/im.naturalHeight);figure.style.setProperty('--surface-w',`${im.naturalWidth*scale}px`);figure.style.setProperty('--surface-h',`${im.naturalHeight*scale}px`)}}
  const pointer={x:.5,y:.5,active:false};
  const motes=Array.from({length:72},(_,i)=>({x:((i*617+37)%997)/997,y:((i*439+19)%991)/991,r:.45+(i%5)*.18,phase:i*2.399}));
  function resize(){const b=stage.getBoundingClientRect();w=b.width;h=b.height;const d=Math.min(devicePixelRatio||1,2);canvas.width=w*d;canvas.height=h*d;ctx.setTransform(d,0,0,d,0,0);fitSurfaces()}
  new ResizeObserver(resize).observe(stage);
  stage.addEventListener('pointermove',ev=>{const r=stage.getBoundingClientRect();pointer.x=(ev.clientX-r.left)/r.width;pointer.y=(ev.clientY-r.top)/r.height;pointer.active=true;if(!playing)render()});
  stage.addEventListener('pointerleave',()=>{pointer.active=false;if(!playing)render()});
  function particles(s,t){
    ctx.clearRect(0,0,w,h);if(reduced)return;
    const dark=s.tone==='ink',orbital=['opening','worlds','glass','end'].includes(s.id);
    if(['comic','comic-hit','novel','novel-hit','controls','download'].includes(s.id))return;
    ctx.fillStyle=dark?'#ceeedb':'#278269';ctx.strokeStyle=dark?'#bee7cf':'#76b6a1';
    const cx=w*(pointer.active?pointer.x:.5),cy=h*(pointer.active?pointer.y:.5);
    motes.forEach((m,i)=>{
      const theta=m.phase+t*.08;let x,y;
      if(orbital){const r=(.21+m.x*.3)*w;x=w*.5+Math.cos(theta)*r;y=h*.53+Math.sin(theta)*r*.42}
      else{x=((m.x+t*.0018)%1)*w;y=((m.y-t*.003+10)%1)*h}
      const dx=x-cx,dy=y-cy,dist=Math.hypot(dx,dy);if(pointer.active&&dist<w*.12){x+=dx*.16;y+=dy*.16}
      ctx.globalAlpha=(dark?.17:.1)*(.4+.6*(.5+.5*Math.sin(t+m.phase)));ctx.beginPath();ctx.arc(x,y,m.r*w/1400,0,Math.PI*2);ctx.fill();
      if(orbital&&i%9===0){ctx.globalAlpha=dark?.07:.045;ctx.beginPath();ctx.ellipse(w*.5,h*.53,(.21+m.x*.3)*w,(.21+m.x*.3)*w*.42,0,theta,theta+.13);ctx.stroke()}
    });ctx.globalAlpha=1;
  }
  function render(){
    const found=SHOTS.findIndex(s=>time<s.start+s.duration),i=found<0?SHOTS.length-1:found,s=SHOTS[i],local=time-s.start;
    const blend=['resume','end'].includes(s.id)?.55:.34;
    const prev=i>0&&local<blend&&s.transition!=='cut'&&!reduced?i-1:-1;
    nodes.forEach((n,j)=>{const active=j===i||j===prev;n.classList.toggle('active',active);n.setAttribute('aria-hidden',String(j!==i));if(active){
      n.style.zIndex=j===i?'2':'1';n.style.clipPath='none';
      const quietCut=prev>=0&&['resume','end'].includes(s.id);
      // At the coda, let the last image go before the next arrives. No stacked UI ghosts.
      n.style.opacity=quietCut?String(j===i?smooth((local/blend-.48)/.52):1-smooth(local/blend/.48)):(j===i&&prev>=0?String(smooth(local/blend)):'1');
    }});
    animate(nodes[i],s,local);if(prev>=0)animate(nodes[prev],SHOTS[prev],SHOTS[prev].duration);
    if(i!==current){current=i;stage.className='stage '+s.tone;$('player-label').textContent=s.label;$('chapter-marker').textContent=`${String(i+1).padStart(2,'0')} / ${SHOTS.length}`;$('status').textContent=s.label}
    $('seek').value=time;$('time').textContent=`${fmt(time)} / ${fmt(total)}`;$('film-meter-fill').style.width=`${time/total*100}%`;particles(s,time);
    if(sound)music.volume=.72*clamp((total-time)/1.6);
  }
  function syncAudio(){if(Number.isFinite(music.duration)&&music.duration>0)music.currentTime=time%music.duration;music.playbackRate=speed;if(sound&&playing)music.play().catch(()=>{sound=false;$('sound').setAttribute('aria-pressed','false')});else music.pause()}
  function pause(){playing=false;cancelAnimationFrame(raf);music.pause();stopFX();$('play').textContent=time>=total?'↺':'▶';$('play').setAttribute('aria-label',time>=total?'重播':'播放');$('play').setAttribute('aria-pressed','false')}
  function tick(now){if(!playing)return;const before=time;time=clamp(time+(now-last)/1000*speed,0,total);last=now;render();sounds(before,time);if(time>=total)pause();else raf=requestAnimationFrame(tick)}
  function play(){if(!ready)return;awaitingFirstView=false;if(time>=total)time=0;cancelAnimationFrame(raf);playing=true;last=performance.now();$('play').textContent='Ⅱ';$('play').setAttribute('aria-label','暂停');$('play').setAttribute('aria-pressed','true');syncAudio();raf=requestAnimationFrame(tick)}
  function seek(v){stopFX();time=clamp(Number(v)||0,0,total);last=performance.now();render();syncAudio();if(time>=total)pause()}
  function toggle(){playing?pause():play()}
  $('play').onclick=toggle;$('previous').onclick=()=>seek(SHOTS[Math.max(0,current-1)].start+.01);$('next').onclick=()=>seek(SHOTS[Math.min(SHOTS.length-1,current+1)].start+.01);
  $('seek').oninput=e=>seek(e.target.value);
  $('speed').onclick=()=>{speed=speed===1?1.25:speed===1.25?1.5:1;$('speed').textContent=speed+'×';$('speed').setAttribute('aria-label',`播放速度，当前 ${speed} 倍`);syncAudio()};
  music.volume=.72;$('sound').onclick=()=>{sound=!sound;if(!sound)stopFX();$('sound').setAttribute('aria-pressed',String(sound));$('sound').setAttribute('aria-label',sound?'关闭声音':'开启声音');syncAudio()};
  $('clean').onclick=()=>document.body.classList.toggle('clean');
  $('fullscreen').onclick=async()=>{try{if(document.fullscreenElement)await document.exitFullscreen();else await document.documentElement.requestFullscreen()}catch{$('status').textContent='请使用浏览器全屏功能'}};
  document.addEventListener('keydown',e=>{if(/INPUT|TEXTAREA/.test(e.target.tagName))return;if(e.code==='Space'){e.preventDefault();toggle()}if(e.key==='ArrowRight')$('next').click();if(e.key==='ArrowLeft')$('previous').click();if(e.key.toLowerCase()==='h')$('clean').click()});
  stage.onclick=e=>{if(!e.target.closest('a,button'))toggle()};document.addEventListener('visibilitychange',()=>{if(document.hidden)pause();else if(ready&&awaitingFirstView)play()});
  window.CialloPromo={seek,play,pause,duration:total,shots:SHOTS.map(s=>({label:s.label,id:s.id,start:s.start,duration:s.duration})),get time(){return time},get ready(){return ready}};
  render();
  Promise.all([document.fonts.ready,...Object.values(A).map(src=>new Promise(resolve=>{const im=new Image();im.onload=im.onerror=resolve;im.src=src}))]).then(()=>{ready=true;resize();if(params.has('frame')){seek(params.get('frame'));pause()}else{seek(0);if(reduced)pause();else if(document.hidden){awaitingFirstView=true;pause()}else play()}});
})();
