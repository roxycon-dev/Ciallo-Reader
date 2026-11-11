/* Product pixels come only from the user's recordings. Timeline controls film packaging. */
(() => {
  'use strict';
  const A={picker:'assets/native-source-picker.webp',shelf:'assets/shelf.webp',glass:'assets/glass.webp',comic:'assets/comic-native.webp',page:'assets/comic-page.webp',first:'assets/comic-first.webp',sources:'assets/native-source-manage.webp',more:'assets/native-source-more.webp',search:'assets/native-search.webp',results:'assets/native-search-more.webp',detail:'assets/native-detail.webp',chapters:'assets/native-chapters.webp',down:'assets/native-downloading.webp',paused:'assets/native-download-paused.webp',done:'assets/native-download-done.webp',modes:'assets/native-comic-modes.webp',layout:'assets/native-comic-layout.webp',image:'assets/native-comic-image.webp',translate:'assets/native-comic-translate.webp',auto:'assets/native-comic-auto.webp',gesture:'assets/native-comic-gesture.webp',background:'assets/native-comic-theme.webp',type:'assets/native-novel-type.webp',novelLayout:'assets/native-novel-layout.webp',text:'assets/native-novel-text.webp',bookmark:'assets/native-bookmark.webp',green:'assets/native-theme-green.webp',orange:'assets/native-theme-orange.webp',glassControls:'assets/native-glass-controls.webp',progress:'assets/native-progress.webp',import:'assets/native-import.webp',stats:'assets/native-stats.webp',calendar:'assets/native-calendar.webp',trends:'assets/native-trends.webp',resume:'assets/native-resume.webp'};
  // duration, layout, palette, chapter, headline, description, feature labels, native frames
  const SHOTS=[
    [1.6,'intro','light','Ciallo 阅读','阅读，<br><span>自由展开。</span>','小说与漫画，一起随身。',['多源搜索','双阅读器'],['shelf','comic','glass']],
    [2.1,'focus','light','多元书源','更多世界。<br><span>一处发现。</span>','漫画、小说，汇集到同一个书库。',['漫画源','小说源','多语言'],['picker']],
    [1.8,'phone','light','书源管理','书源，<br><span>由你选择。</span>','自由启停，也支持 JSON 导入。',['源管理','启用 / 停用','JSON 导入'],['more']],
    [2.1,'phone','dark','多源搜索','一次搜索。<br><span>多处相遇。</span>','在不同书源的结果之间，找到想看的。',['聚合搜索','跨源结果'],['search','results']],
    [2,'details','light','在线阅读','找到。打开。<br><span>开始阅读。</span>','作品详情与章节列表，直接抵达内容。',['作品详情','章节选择','在线阅读'],['detail','chapters']],
    [2.2,'strips','dark','下载管理','想看的，<br><span>先存下来。</span>','进度、暂停、完成，一目了然。',['下载中','已暂停','已完成'],['down','paused','done']],
    [1.8,'details','light','书架与导入','你的收藏。<br><span>你的书架。</span>','分类整理，也能导入本地书籍。',['书架分类','本地导入'],['shelf','import']],
    [1.8,'phone','warm','漫画阅读器','一页入画。<br><span>沉浸其中。</span>','让漫画，占满此刻。',['全屏阅读','进度定位'],['comic']],
    [2.5,'panels','dark','漫画阅读模式','怎么读，<span>你说了算。</span>','',['单页 / 双页 / 条漫','阅读方向与翻页','图像调节'],['modes','layout','image']],
    [1.8,'focus','light','翻译设置','跨过语言。<br><span>走近故事。</span>','漫画翻译与 OCR，按需配置。',['翻译服务设置','OCR 配置'],['translate']],
    [2.2,'panels','light','漫画操控','每个习惯，<span>都有回应。</span>','',['自动翻页','手势设置','阅读背景'],['auto','gesture','background']],
    [2.6,'panels','warm','小说阅读器','文字，也有<span>自己的节奏。</span>','',['小说正文','字号 / 行距 / 页边距','阅读主题'],['text','type','novelLayout']],
    [1.5,'bookmark','warm','阅读书签','这一页。<br><span>记住了。</span>','真实的书签保存反馈。',['保存书签','Roxy 陪伴'],['text','bookmark']],
    [2,'phone','light','主题与强调色','喜欢的颜色。<br><span>就是主色。</span>','主题色与强调色，自由搭配。',['主题切换','强调色'],['green','orange']],
    [1.8,'phone','dark','玻璃质感','轻透之间。<br><span>自有层次。</span>','把通透质感，调到恰到好处。',['玻璃效果','细节调节'],['glassControls']],
    [1.8,'duet','light','继续阅读','上次读到哪。<br><span>接着就好。</span>','从书架的正在阅读，继续故事。',['继续阅读','阅读进度'],['resume','progress']],
    [2.8,'stats','light','阅读统计','每次沉浸，<span>都有迹可循。</span>','',['时长与目标','阅读日历','趋势 / 时段 / 周统计'],['stats','calendar','trends']],
    [1.8,'end','dark','Ciallo 阅读','把世界，<span>翻开。</span>','Ciallo 阅读 · 公益开源',['多源搜索','小说与漫画','为阅读而来'],['shelf','comic']]
  ].map((s,i)=>({duration:s[0],layout:s[1],palette:s[2],label:s[3],title:s[4],description:s[5],features:s[6],frames:s[7],index:i}));
  let total=0;SHOTS.forEach(s=>{s.start=total;total+=s.duration});
  const $=id=>document.getElementById(id),stage=$('stage'),music=$('music'),params=new URLSearchParams(location.search);
  const reduced=matchMedia('(prefers-reduced-motion: reduce)').matches;
  document.body.classList.toggle('reduced',reduced);document.body.classList.toggle('clean',params.has('record'));
  function card(key,i,phone){return `<div class="native-card ${phone?'phone-card':''}" style="--order:${i}"><img src="${A[key]}" alt="实机界面：${key}" draggable="false"></div>`}
  $('shots').innerHTML=SHOTS.map(s=>{
    const sequence=s.layout==='phone'&&s.frames.length>1;
    const frames=sequence?s.frames.slice(0,1):s.frames;
    return `<article class="shot ${s.layout}" data-index="${s.index}" aria-label="${s.label}" aria-hidden="true"><div class="copy"><p class="eyebrow">${String(s.index+1).padStart(2,'0')} / ${s.label}</p><h2>${s.title}</h2>${s.description?`<p class="description">${s.description}</p>`:''}<div class="feature-line">${s.features.map(x=>`<span>${x}</span>`).join('')}</div></div><div class="art">${frames.map((k,i)=>card(k,i,['intro','phone'].includes(s.layout))).join('')}</div>${s.layout==='strips'?'<div class="substate">实机下载状态</div>':''}</article>`;
  }).join('');
  const nodes=[...document.querySelectorAll('.shot')];
  let time=0,playing=false,speed=1,last=0,current=-1,sound=false,raf=0,ready=false;
  const clamp=(v,a,b)=>Math.max(a,Math.min(b,v)),fmt=v=>`00:${String(Math.floor(v)).padStart(2,'0')}`;
  $('seek').max=total;
  const canvas=$('particles'),ctx=canvas.getContext('2d');let w=0,h=0;
  const motes=Array.from({length:96},(_,i)=>({x:((i*617+37)%997)/997,y:((i*439+19)%991)/991,r:.5+(i%5)*.28,phase:i*2.399,velocity:.004+(i%9)*.0008}));
  function resize(){const box=stage.getBoundingClientRect();w=box.width;h=box.height;const d=Math.min(devicePixelRatio||1,2);canvas.width=w*d;canvas.height=h*d;ctx.setTransform(d,0,0,d,0,0)}
  new ResizeObserver(resize).observe(stage);
  function particles(t,dark){ctx.clearRect(0,0,w,h);if(reduced)return;ctx.fillStyle=dark?'#d3f7dd':'#368c63';for(const m of motes){const x=(m.x+t*m.velocity*.12)%1,y=(m.y-t*m.velocity*.2+10)%1;ctx.globalAlpha=(.08+.14*(.5+.5*Math.sin(t*.8+m.phase)))*(dark?1:.55);ctx.beginPath();ctx.arc(x*w+Math.sin(t*.4+m.phase)*8,y*h,m.r*w/1400,0,Math.PI*2);ctx.fill()}ctx.globalAlpha=1}
  function render(){
    const n=SHOTS.findIndex(s=>time<s.start+s.duration),i=n<0?SHOTS.length-1:n,s=SHOTS[i],node=nodes[i],p=clamp((time-s.start)/s.duration,0,1);
    if(i!==current){nodes.forEach((el,j)=>{el.classList.toggle('active',j===i);el.setAttribute('aria-hidden',String(j!==i))});current=i;stage.className='stage '+s.palette;$('player-label').textContent=s.label;$('chapter-marker').textContent=`${String(i+1).padStart(2,'0')} / ${SHOTS.length}`;$('status').textContent=s.label}
    const entry=1-Math.pow(1-clamp((time-s.start)/.36,0,1),3);node.style.setProperty('--entry',entry);node.style.setProperty('--p',p);
    const step=Math.min(s.features.length-1,Math.floor(p*s.features.length));node.querySelectorAll('.feature-line span').forEach((el,j)=>el.classList.toggle('current',j===step));
    if(s.layout==='phone'&&s.frames.length>1){const key=s.frames[Math.min(s.frames.length-1,Math.floor(p*s.frames.length))],im=node.querySelector('img');if(im.dataset.key!==key){im.src=A[key];im.dataset.key=key}}
    if(s.layout==='strips'){node.querySelectorAll('.native-card').forEach((el,j)=>el.classList.toggle('highlight',j===step));node.querySelector('.substate').textContent=s.features[step]}
    $('seek').value=time;$('time').textContent=`${fmt(time)} / ${fmt(total)}`;$('film-meter-fill').style.width=`${time/total*100}%`;particles(time,s.palette==='dark');
  }
  function syncAudio(){if(Number.isFinite(music.duration)&&music.duration>0)music.currentTime=time%music.duration;music.playbackRate=speed;if(sound&&playing)music.play().catch(()=>{sound=false;$('sound').setAttribute('aria-pressed','false')});else music.pause()}
  function pause(){playing=false;cancelAnimationFrame(raf);music.pause();$('play').textContent=time>=total?'↺':'▶';$('play').setAttribute('aria-label',time>=total?'重播':'播放');$('play').setAttribute('aria-pressed','false')}
  function tick(now){if(!playing)return;time=clamp(time+(now-last)/1000*speed,0,total);last=now;render();if(time>=total)pause();else raf=requestAnimationFrame(tick)}
  function play(){if(!ready)return;if(time>=total)time=0;cancelAnimationFrame(raf);playing=true;last=performance.now();$('play').textContent='Ⅱ';$('play').setAttribute('aria-label','暂停');$('play').setAttribute('aria-pressed','true');syncAudio();raf=requestAnimationFrame(tick)}
  function seek(v){time=clamp(Number(v)||0,0,total);last=performance.now();render();syncAudio();if(time>=total)pause()}
  function toggle(){playing?pause():play()}
  $('play').onclick=toggle;$('previous').onclick=()=>seek(SHOTS[Math.max(0,current-1)].start+.01);$('next').onclick=()=>seek(SHOTS[Math.min(SHOTS.length-1,current+1)].start+.01);
  $('seek').oninput=e=>seek(e.target.value);
  $('speed').onclick=()=>{speed=speed===1?1.25:speed===1.25?1.5:1;$('speed').textContent=speed+'×';$('speed').setAttribute('aria-label',`播放速度，当前 ${speed} 倍`);syncAudio()};
  music.volume=.28;$('sound').onclick=()=>{sound=!sound;$('sound').setAttribute('aria-pressed',String(sound));$('sound').setAttribute('aria-label',sound?'关闭音乐':'开启音乐');syncAudio()};
  $('clean').onclick=()=>document.body.classList.toggle('clean');
  $('fullscreen').onclick=async()=>{try{if(document.fullscreenElement)await document.exitFullscreen();else await document.documentElement.requestFullscreen()}catch{$('status').textContent='当前浏览器未允许全屏，请使用浏览器全屏功能'}};
  document.addEventListener('keydown',e=>{if(/INPUT|TEXTAREA/.test(e.target.tagName))return;if(e.code==='Space'){e.preventDefault();toggle()}if(e.key==='ArrowRight')$('next').click();if(e.key==='ArrowLeft')$('previous').click();if(e.key.toLowerCase()==='h')$('clean').click()});
  stage.onclick=toggle;document.addEventListener('visibilitychange',()=>{if(document.hidden)pause()});
  window.CialloPromo={seek,play,pause,duration:total,shots:SHOTS.map(s=>({label:s.label,start:s.start,duration:s.duration})),get time(){return time},get ready(){return ready}};
  render();
  Promise.all(Object.values(A).map(src=>new Promise(resolve=>{const im=new Image();im.onload=im.onerror=resolve;im.src=src}))).then(()=>{ready=true;resize();if(params.has('frame')){seek(params.get('frame'));pause()}else{seek(0);if(reduced)pause();else play()}});
})();
