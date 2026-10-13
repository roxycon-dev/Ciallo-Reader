/* 原创丝带曲线、六层玻璃、原生滚动展台。参数集中修改。 */
(() => {
  'use strict';
  const CONFIG = {
    flow: { lines:108, tabletLines:84, mobileLines:60, steps:64, mobileSteps:40, dpr:2, fps:30, sigmaRatio:.13, sigmaMin:170, sigmaMax:280, vortex:3.2, pressureSpin:.075, collapse:.42, ambientSharp:.68, timeScale:.000035, idleX:.00004, idleY:.000059, clearRadius:240, clearMax:340, idle:2500, follow:.14, heavyBlur:18, softBlur:5 },
    lens: { nav:{bezel:26,scale:64}, pill:{bezel:16,scale:36}, card:{bezel:38,scale:74}, maxMap:420 },
    nav: { x:8,y:12 }, cardTilt:10, dock:{radius:120,boost:.12}, journey:{distance:2.1,maxHeight:850}
  };
  const root=document.documentElement, $=s=>document.querySelector(s), all=s=>[...document.querySelectorAll(s)];
  const clamp=(n,a,b)=>Math.max(a,Math.min(b,n));
  const reduced=matchMedia('(prefers-reduced-motion: reduce)'), systemTheme=matchMedia('(prefers-color-scheme: dark)'),coarse=matchMedia('(pointer: coarse)');
  const en=root.lang==='en', label=(zh,english)=>en?english:zh;
  root.classList.add('has-js');
  let userTheme=null;
  try{userTheme=localStorage.getItem('ciallo-theme')}catch(_){}
  if(userTheme!=='light'&&userTheme!=='dark')userTheme=null;
  function syncTheme(){
    const dark=root.dataset.theme?root.dataset.theme==='dark':systemTheme.matches;
    root.classList.toggle('theme-dark',dark);
    $('meta[name="theme-color"]').content=dark?'#090a12':'#f4f3f8';
    $('#themeBtn').setAttribute('aria-pressed',String(dark));
    $('#themeBtn').setAttribute('aria-label',dark?label('切换为浅色主题','Switch to light theme'):label('切换为深色主题','Switch to dark theme'));
    root.dispatchEvent(new Event('themechange'));
  }
  $('#themeBtn').addEventListener('click',()=>{userTheme=root.classList.contains('theme-dark')?'light':'dark';root.dataset.theme=userTheme;try{localStorage.setItem('ciallo-theme',userTheme)}catch(_){}syncTheme()});
  systemTheme.addEventListener('change',()=>{if(!userTheme)syncTheme()});
  syncTheme();

  // 公共渲染循环：流场持续演化，导航、展台、高光只在输入后更新。
  let raf=0,dirty=true,layoutDirty=true,glassDirty=true,lastFlow=0,visible=!document.hidden,heroVisible=true;
  let pointerX=innerWidth*.7,pointerY=innerHeight*.38,hasPointer=false;
  const glassElements=all('.glass');
  const visibleGlass=new Set(glassElements);
  const glassVisibility=new IntersectionObserver(entries=>{entries.forEach(entry=>{if(entry.isIntersecting)visibleGlass.add(entry.target);else visibleGlass.delete(entry.target)});glassDirty=true;schedule()},{rootMargin:'60px'});
  glassElements.forEach(el=>glassVisibility.observe(el));
  const setStyle=(el,name,value)=>{if(el.style.getPropertyValue(name)!==value)el.style.setProperty(name,value)};
  const nav=$('.nav'),links=all('.nav-links a'),slider=$('.nav-slider');
  let lastScroll=scrollY;
  function updateNav(){
    if(hasPointer&&!reduced.matches){nav.style.setProperty('--nav-y',(pointerX/innerWidth-.5)*CONFIG.nav.y+'deg');nav.style.setProperty('--nav-x',-(pointerY/innerHeight-.5)*CONFIG.nav.x+'deg')}
    if(!layoutDirty)return;
    const down=scrollY>lastScroll+1; if(Math.abs(scrollY-lastScroll)>1){$('.nav-wrap').classList.toggle('compact',down&&scrollY>40);lastScroll=scrollY}
    let active=null;
    links.forEach(link=>{const target=$(link.getAttribute('href'));if(target&&target.getBoundingClientRect().top<innerHeight*.42)active=link});
    const width=active?active.offsetWidth+'px':'',position=active?'translateX('+active.offsetLeft+'px)':'';
    links.forEach(link=>{link.classList.toggle('active',link===active);if(link===active){if(link.getAttribute('aria-current')!=='location')link.setAttribute('aria-current','location')}else if(link.hasAttribute('aria-current'))link.removeAttribute('aria-current')});
    setStyle(slider,'opacity',active?'1':'0');if(active){setStyle(slider,'width',width);setStyle(slider,'transform',position)}
  }
  const menu=$('#mobilePanel'),menuButton=$('#menuBtn');
  function setMenu(open,focus=false){
    menu.hidden=!open;menu.classList.remove('open');menuButton.setAttribute('aria-expanded',String(open));menuButton.setAttribute('aria-label',open?label('关闭菜单','Close menu'):label('打开菜单','Open menu'));
    all('#mobilePanel a').forEach((a,i)=>a.style.transitionDelay=open?(60+i*45)+'ms':'0ms');
    if(open){void menu.offsetHeight;menu.classList.add('open')}if(focus)menuButton.focus();
  }
  menuButton.addEventListener('click',()=>setMenu(menu.hidden));
  menu.addEventListener('click',e=>{if(e.target.closest('a'))setMenu(false)});
  document.addEventListener('click',e=>{if(!menu.hidden&&!e.target.closest('.nav-wrap'))setMenu(false)});
  document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!menu.hidden)setMenu(false,true)});
  matchMedia('(min-width: 761px)').addEventListener('change',e=>{if(e.matches)setMenu(false)});

  // 展台由真实滚动位置驱动；减少动态效果时退回普通标签。
  const experience=$('#experience'),track=$('#readingJourney'),pin=$('.journey-pin');
  const tabs=all('[data-tab]'),panels=all('[data-panel]');
  let chapter=0,journeyEnabled=false,journeyHeight=0,journeyRange=0,journeyOffset=102;
  function activate(index,force=false){
    if(index===chapter&&!force)return;
    if(index!==chapter){chapter=index;$('.journey-word').textContent=[label('从书架开始','Start with your library'),label('在文字里停留','Settle into the words'),label('沉浸每一个分镜','Get lost in every panel')][index]}
    tabs.forEach((tab,i)=>{const active=i===index;tab.setAttribute('aria-selected',String(active));tab.tabIndex=active?0:-1;panels[i].hidden=journeyEnabled?false:!active;panels[i].classList.toggle('is-active',active);panels[i].inert=!active;panels[i].tabIndex=active?0:-1;panels[i].querySelectorAll('button,a').forEach(control=>control.tabIndex=active?0:-1);panels[i].setAttribute('aria-hidden',String(!active))});
  }
  function layoutJourney(){
    journeyEnabled=!reduced.matches&&innerHeight>=650;
    experience.classList.toggle('journey-enabled',journeyEnabled);
    const style=getComputedStyle(pin),mobile=document.documentElement.clientWidth<=760;
    journeyOffset=parseFloat(style.top)||(mobile?83:102);
    journeyHeight=Math.min(CONFIG.journey.maxHeight,innerHeight-journeyOffset-(parseFloat(style.scrollMarginBottom)||(mobile?25:33)));
    journeyRange=innerHeight*CONFIG.journey.distance;
    track.style.setProperty('--journey-height',journeyHeight+'px');track.style.setProperty('--journey-distance',journeyRange+'px');
    panels.forEach(panel=>{panel.setAttribute('role','tabpanel');panel.tabIndex=0});
    activate(chapter,true);dirty=layoutDirty=true;glassDirty=true;schedule();
  }
  function updateJourney(){
    if(!journeyEnabled)return;
    const start=track.getBoundingClientRect().top-journeyOffset;
    const p=clamp(-start/journeyRange,0,.99999), index=Math.floor(p*3), local=p*3-index;
    activate(index);
    setStyle(pin,'--scene-depth',((local-.5)*2).toFixed(4));
    tabs.forEach((tab,i)=>setStyle(tab,'--tab-progress',i<index?'1':i===index?local.toFixed(4):'0'));
    if(track.dataset.chapter!==tabs[index].dataset.tab)track.dataset.chapter=tabs[index].dataset.tab;
  }
  function goChapter(index,focus=false){
    if(journeyEnabled){const top=scrollY+track.getBoundingClientRect().top-journeyOffset+journeyRange*(index+.4)/3;window.scrollTo({top,behavior:reduced.matches?'auto':'smooth'})}else activate(index);
    if(focus)tabs[index].focus({preventScroll:true});
  }
  tabs.forEach((tab,index)=>{tab.addEventListener('click',()=>goChapter(index));tab.addEventListener('keydown',e=>{let next=index;if(e.key==='ArrowRight')next=(index+1)%3;else if(e.key==='ArrowLeft')next=(index+2)%3;else if(e.key==='Home')next=0;else if(e.key==='End')next=2;else return;e.preventDefault();goChapter(next,true)})});

  // 截图保持原比例；只在展台尺寸变化时计算，给原有倾斜动画留出空间。
  const stageSizes=new ResizeObserver(entries=>{
    entries.forEach(({target,contentRect})=>{
      const device=target.querySelector('.device');
      if(!journeyEnabled){device.style.removeProperty('--stage-device-width');return}
      const style=getComputedStyle(device),padding=parseFloat(style.paddingTop)*2;
      const maximum=parseFloat(style.getPropertyValue('--stage-device-max'))||243;
      const width=Math.min(maximum,contentRect.width-8,(contentRect.height-32-padding)*1224/2700+padding);
      setStyle(device,'--stage-device-width',Math.max(1,width).toFixed(2)+'px');
    });
  });
  all('.experience-visual').forEach(el=>stageSizes.observe(el));

  // 点击画面上的注释打开真实截图；原生 dialog 负责焦点约束与 Escape。
  const dialog=$('#detailDialog');let dialogTrigger=null,previousOverflow='';
  function openDetails(key,trigger){
    const translation=key==='translate',panel=panels.find(p=>p.dataset.panel===key)||panels[chapter];
    const image=translation?$('.translate-preview img'):panel.querySelector('.device img');
    $('#detailTitle').textContent=translation?label('漫画翻译，按你的方式。','Manga translation, your way.'):panel.querySelector('h3').innerText;
    $('#detailDescription').textContent=translation?label('OCR 与气泡检测在本机完成，译文通过所选在线服务或自定义 API 生成。自动翻译、模型准备与服务配置可在阅读设置里管理。','OCR and bubble detection run locally. Your chosen online service or custom API generates the translation. Manage automatic translation, model preparation, and service configuration in the reader settings.'):panel.querySelector('.experience-copy>p').textContent;
    $('#detailBenefits').innerHTML=translation?'<li>'+label('设备端识别与气泡定位','On-device recognition and bubble detection')+'</li><li>'+label('在线翻译服务或自定义 API','Your translation service or custom API')+'</li><li>'+label('发送请求前，查看所选服务的隐私条款','Review your service’s privacy terms before sending requests')+'</li>':panel.querySelector('.benefits').innerHTML;
    const target=$('#detailImage');target.src=image.src;target.alt=image.alt;target.width=image.getAttribute('width');target.height=image.getAttribute('height');
    dialogTrigger=trigger;previousOverflow=document.body.style.overflow;document.body.style.overflow='hidden';dialog.showModal();dialog.scrollTop=0;$('.dialog-close').focus({preventScroll:true});
    if(!reduced.matches)dialog.animate([{opacity:0,transform:'translateY(20px) scale(.97)'},{opacity:1,transform:'none'}],{duration:300,easing:'cubic-bezier(.2,.8,.2,1)'});
  }
  all('[data-detail]').forEach(button=>{button.setAttribute('aria-haspopup','dialog');button.addEventListener('click',()=>openDetails(button.dataset.detail,button))});
  all('[data-settings]').forEach(button=>{button.setAttribute('aria-haspopup','dialog');button.addEventListener('click',()=>openDetails(button.dataset.settings,button))});
  $('.dialog-close').addEventListener('click',()=>dialog.close());
  dialog.addEventListener('click',e=>{if(e.target===dialog){const b=dialog.getBoundingClientRect();if(e.clientX<b.left||e.clientX>b.right||e.clientY<b.top||e.clientY>b.bottom)dialog.close()}});
  dialog.addEventListener('close',()=>{document.body.style.overflow=previousOverflow;if(dialogTrigger)dialogTrigger.focus({preventScroll:true});dirty=layoutDirty=glassDirty=true;schedule()});

  // 光源分别指向每块玻璃；屏外材质不计算边缘光。
  function updateGlass(){
    if(!glassDirty)return;glassDirty=false;
    const boxes=[];visibleGlass.forEach(el=>{const b=el.getBoundingClientRect();if(b.bottom>=0&&b.top<=innerHeight&&b.width)boxes.push({el,b})});
    const x=hasPointer?pointerX:-innerWidth*.3,y=hasPointer?pointerY:-innerHeight*.3;
    boxes.forEach(({el,b})=>{setStyle(el,'--a',(Math.atan2(y-b.top-b.height/2,x-b.left-b.width/2)*180/Math.PI+30)+'deg');setStyle(el,'--light-x',x-b.left+'px');setStyle(el,'--light-y',y-b.top+'px')});
  }
  all('[data-tilt]').forEach(card=>{card.addEventListener('pointermove',e=>{if(reduced.matches||e.pointerType==='touch')return;const b=card.getBoundingClientRect();card.style.setProperty('--card-y',((e.clientX-b.left)/b.width-.5)*CONFIG.cardTilt*2+'deg');card.style.setProperty('--card-x',-((e.clientY-b.top)/b.height-.5)*CONFIG.cardTilt*2+'deg')});card.addEventListener('pointerleave',()=>{card.style.setProperty('--card-x','0deg');card.style.setProperty('--card-y','0deg')})});
  all('.format-dock').forEach(dock=>{const caps=[...dock.children];dock.addEventListener('pointermove',e=>{if(reduced.matches||e.pointerType==='touch')return;caps.forEach(cap=>{const b=cap.getBoundingClientRect(),d=Math.hypot(e.clientX-b.left-b.width/2,e.clientY-b.top-b.height/2);cap.style.setProperty('--dock-scale',(1+CONFIG.dock.boost*Math.max(0,1-d/CONFIG.dock.radius)).toFixed(3))})});dock.addEventListener('pointerleave',()=>caps.forEach(cap=>cap.style.setProperty('--dock-scale','1')))});

  // SVG 折射贴图，仅 Chromium；相同尺寸共用滤镜，进入视野后再生成。
  const ua=navigator.userAgent,ios=/iP(hone|ad|od)/.test(ua)||(navigator.platform==='MacIntel'&&navigator.maxTouchPoints>1);
  if(/Chrome\/|Chromium\/|Edg\//.test(ua)&&!/Firefox\/|CriOS|FxiOS|EdgiOS/.test(ua)&&!ios){
    const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg');svg.setAttribute('aria-hidden','true');svg.setAttribute('width','0');svg.setAttribute('height','0');svg.style.cssText='position:absolute;pointer-events:none';const defs=document.createElementNS(ns,'defs');svg.appendChild(defs);document.body.appendChild(svg);
    const cache=new Map(),queued=new Set();let idle=0,uid=0;
    // LENS_MAP_BEGIN
    function signed(x,y,bx,by,r){const qx=Math.abs(x)-bx,qy=Math.abs(y)-by,ax=Math.max(qx,0),ay=Math.max(qy,0);return(ax||ay?Math.hypot(ax,ay):0)+Math.min(Math.max(qx,qy),0)-r}
    function fillLensMap(d,w,h,r,bezel){
      const bx=w/2-r,by=h/2-r;
      const put=(x,y,R,B)=>{const o=(y*w+x)*4;d[o]=R;d[o+1]=128;d[o+2]=B;d[o+3]=255};
      for(let y=0;y<Math.ceil(h/2);y++)for(let x=0;x<Math.ceil(w/2);x++){
        const px=x+.5-w/2,py=y+.5-h/2,dep=-signed(px,py,bx,by,r);let R=128,B=128;
        if(dep>0&&dep<bezel){const m=(1-dep/bezel)**2.2,gx=signed(px+1,py,bx,by,r)-signed(px-1,py,bx,by,r),gy=signed(px,py+1,bx,by,r)-signed(px,py-1,bx,by,r),g=Math.hypot(gx,gy)||1;R=128+127*gx/g*m;B=128+127*gy/g*m}
        put(x,y,R,B);const o=(y*w+x)*4;R=d[o];B=d[o+2];
        put(w-1-x,y,256-R,B);put(x,h-1-y,R,256-B);put(w-1-x,h-1-y,256-R,256-B);
      }
    }
    // LENS_MAP_END
    function lens(el){
      const ew=el.offsetWidth,eh=el.offsetHeight;if(ew<4||eh<4)return;
      const cfg=el.matches('.nav,.mobile-panel')?CONFIG.lens.nav:el.matches('.core-card,.experience-display,.feature-card,.personal-panel,.translation-wide,.formats-inner,.detail-dialog')?CONFIG.lens.card:CONFIG.lens.pill;
      const k=Math.min(1,CONFIG.lens.maxMap/Math.max(ew,eh)),w=Math.max(8,Math.round(ew*k)),h=Math.max(8,Math.round(eh*k));
      const r=Math.min((parseFloat(getComputedStyle(el).borderRadius)||999)*k,w/2,h/2),bezel=cfg.bezel*k,S=cfg.scale;
      const key=[w,h,Math.round(r),cfg.scale,cfg.bezel].join(':');let id=cache.get(key);
      if(!id){
        id='ciallo-lens-'+uid++;const cv=document.createElement('canvas');cv.width=w;cv.height=h;const ctx=cv.getContext('2d'),map=ctx.createImageData(w,h);
        fillLensMap(map.data,w,h,r,bezel);
        ctx.putImageData(map,0,0);const filter=document.createElementNS(ns,'filter');filter.id=id;filter.setAttribute('color-interpolation-filters','sRGB');filter.setAttribute('x','-5%');filter.setAttribute('y','-5%');filter.setAttribute('width','110%');filter.setAttribute('height','110%');
        let content='<feImage href="'+cv.toDataURL('image/png')+'" x="0" y="0" width="100%" height="100%" preserveAspectRatio="none" result="map"/>';
        [['R','1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1 0'],['G','0 0 0 0 0 0 1 0 0 0 0 0 0 0 0 0 0 0 1 0'],['B','0 0 0 0 0 0 0 0 0 0 0 0 1 0 0 0 0 0 1 0']].forEach(([ch,m],i)=>{content+='<feDisplacementMap in="SourceGraphic" in2="map" scale="'+Math.max(1,S-i*9)+'" xChannelSelector="R" yChannelSelector="B" result="d'+ch+'"/><feColorMatrix in="d'+ch+'" type="matrix" values="'+m+'" result="c'+ch+'"/>'});
        content+='<feBlend in="cR" in2="cG" mode="screen" result="rg"/><feBlend in="rg" in2="cB" mode="screen"/>';filter.innerHTML=content;defs.appendChild(filter);cache.set(key,id);
      }
      const value='url(#'+id+') blur(var(--lens-blur,2px)) saturate(1.7) brightness(1.08)';el.style.backdropFilter=value;el.style.webkitBackdropFilter=value;el.dataset.lensId=id;
    }
    function process(deadline){idle=0;while(queued.size){const el=queued.values().next().value;queued.delete(el);lens(el);if(deadline&&deadline.timeRemaining()<2)break}if(queued.size)queue()}
    function queue(){if(!idle)idle='requestIdleCallback'in window?requestIdleCallback(process,{timeout:700}):setTimeout(()=>process(null),80)}
    const inView=new IntersectionObserver(entries=>{entries.forEach(entry=>{if(entry.isIntersecting){queued.add(entry.target);queue()}})},{rootMargin:'100px'});
    const sizes=new ResizeObserver(entries=>{entries.forEach(({target})=>{if(target.dataset.lensId){queued.add(target);queue()}})});
    glassElements.forEach(el=>{inView.observe(el);sizes.observe(el)});
  }

  // 绘制与页面输入分离；工作线程和兼容路径使用完全相同的绘制器。
  const field=$('#flowField'),layers=[$('#flowHeavy'),$('#flowSoft'),$('#flowSharp')];
  let W=innerWidth,H=innerHeight,mx=W*.72,my=H*.38,tx=mx,ty=my,speed=0,phase=0,lastInput=-10000,touchEnd=0,virtualFade=0;
  let pointerEnergy=0,pressureAmount=0,reduceSettle=0,options=null,version=0;
  let worker=null,painter=null,ready=false,busy=false,transferred=false,pending=null,sent=null,latest=null,maskKey='',maskFade=-1,startingFallback=false;
  const localMasks=window.CSS&&CSS.supports('mask-composite','add')&&CSS.supports('mask-composite','subtract');
  field.classList.toggle('flow-local-masks',!!localMasks);
  field.style.setProperty('--flow-sharp-base','linear-gradient(rgba(0,0,0,'+CONFIG.flow.ambientSharp+'),rgba(0,0,0,'+CONFIG.flow.ambientSharp+'))');
  field.style.setProperty('--flow-heavy-base','linear-gradient(rgba(0,0,0,'+(1-CONFIG.flow.ambientSharp)+'),rgba(0,0,0,'+(1-CONFIG.flow.ambientSharp)+'))');
  const lowPower=(navigator.hardwareConcurrency&&navigator.hardwareConcurrency<=4)||(navigator.deviceMemory&&navigator.deviceMemory<=4);
  const script=document.currentScript,base=script?script.src:document.baseURI;
  const engineURL=new URL(script&&script.dataset.flowEngine||'./curve-engine.js',base).href;
  const workerURL=new URL(script&&script.dataset.flowWorker||'./flow-worker.js',base).href;
  const profiling=new URLSearchParams(location.search).has('profile');
  const stats=profiling?{start:performance.now(),frames:0,total:0,values:[],main:0,mainFrames:0,max:0}:null;
  field.classList.toggle('flow-low-power',!!lowPower);
  function applyFrame(state,duration){
    const {mx:x,my:y,radius,fade}=state,key=[x.toFixed(1),y.toFixed(1),radius.toFixed(1),fade].join(':');
    if(key!==maskKey){
      maskKey=key;const floor=CONFIG.flow.ambientSharp,stops=[0,.20,.38,.56,.74,.88,1];
      if(!localMasks||fade!==maskFade){
        maskFade=fade;const sharp=[1,.94,.72,.42,.18,.05,0].map(a=>floor+(1-floor)*a*fade),heavy=sharp.map(a=>1-a),soft=[0,0,.38,.62,.30,0,0].map(a=>a*fade);
        const gradient=alpha=>'radial-gradient(circle '+radius.toFixed(1)+'px at '+x.toFixed(1)+'px '+y.toFixed(1)+'px,'+alpha.map((a,i)=>'rgba(0,0,0,'+a.toFixed(3)+') '+(radius*stops[i]).toFixed(1)+'px').join(',')+')';
        const local=alpha=>'radial-gradient(circle closest-side at center,'+alpha.map((a,i)=>'rgba(0,0,0,'+clamp(a,0,1).toFixed(9)+') '+(stops[i]*100)+'%').join(',')+')';
        field.style.setProperty('--flow-mask-sharp',localMasks?local(sharp.map(a=>(Number(a.toFixed(3))-floor)/(1-floor))):gradient(sharp));
        field.style.setProperty('--flow-mask-heavy',localMasks?local(heavy.map(a=>1-Number(a.toFixed(3))/(1-floor))):gradient(heavy));
        field.style.setProperty('--flow-mask-soft',localMasks?local(soft.map(a=>Number(a.toFixed(3)))):gradient(soft));
      }
      if(localMasks){const r=Number(radius.toFixed(1));setStyle(field,'--flow-local-size',(r*2).toFixed(1)+'px '+(r*2).toFixed(1)+'px');setStyle(field,'--flow-local-position',(Number(x.toFixed(1))-r).toFixed(1)+'px '+(Number(y.toFixed(1))-r).toFixed(1)+'px')}
      field.style.setProperty('--mx',x.toFixed(1)+'px');field.style.setProperty('--my',y.toFixed(1)+'px');field.style.setProperty('--r',radius.toFixed(1)+'px');
    }
    const pressure=state.pressure.toFixed(3);if(field.dataset.pressure!==pressure)field.dataset.pressure=pressure;
    if(field.dataset.input!==state.input)field.dataset.input=state.input;
    if(stats){
      stats.frames++;stats.total+=duration;stats.values.push(duration);stats.max=Math.max(stats.max,duration);
      if(stats.frames%30===0){const sorted=[...stats.values].sort((a,b)=>a-b);field.dataset.profile=JSON.stringify({frames:stats.frames,fps:stats.frames*1000/(performance.now()-stats.start),mean:stats.total/stats.frames,p95:sorted[Math.floor(sorted.length*.95)],max:stats.max,mainMean:stats.main/stats.mainFrames,backend:field.dataset.backend})}
    }
  }
  function flush(){
    if(!ready||busy||!pending||!visible)return;
    sent={state:pending,version};pending=null;busy=true;
    worker.postMessage({type:'draw',state:sent.state,version:sent.version});
  }
  function render(state){
    latest=state;
    if(worker){pending=state;flush()}
    else if(painter){const started=profiling?performance.now():0;painter.draw(state);applyFrame(state,profiling?performance.now()-started:0)}
  }
  async function fallbackRenderer(){
    if(startingFallback)return;startingFallback=true;
    if(worker)worker.terminate();worker=null;ready=busy=false;pending=null;
    if(transferred){layers.forEach((canvas,i)=>{const next=canvas.cloneNode(false);canvas.replaceWith(next);layers[i]=next});transferred=false}
    try{
      if(!window.CialloFlow)await new Promise((resolve,reject)=>{const loader=document.createElement('script');loader.src=engineURL;loader.onload=resolve;loader.onerror=reject;document.head.appendChild(loader)});
      painter=new window.CialloFlow.Painter(layers,CONFIG.flow,()=>document.createElement('canvas'));
      painter.resize(options);field.dataset.backend='main';if(latest)render(latest);
    }catch(error){console.error('Curve renderer could not start',error)}
  }
  function startRenderer(){
    if(!window.Worker||!window.OffscreenCanvas||!HTMLCanvasElement.prototype.transferControlToOffscreen){fallbackRenderer();return}
    try{
      worker=new Worker(workerURL);worker.onerror=()=>fallbackRenderer();
      worker.onmessage=event=>{
        const data=event.data;
        if(data.type==='ready'){ready=true;field.dataset.backend='worker';flush()}
        else if(data.type==='done'){if(sent&&data.version===version)applyFrame(sent.state,data.duration);busy=false;flush()}
        else if(data.type==='error')fallbackRenderer();
      };
      const canvases=layers.map(canvas=>{const offscreen=canvas.transferControlToOffscreen();transferred=true;return offscreen});
      worker.postMessage({type:'init',engine:engineURL,canvases,config:CONFIG.flow,options},canvases);
      setTimeout(()=>{if(worker&&!ready)fallbackRenderer()},2500);
    }catch(error){fallbackRenderer()}
  }
  function resizeFlow(){
    W=innerWidth;H=innerHeight;const mobile=W<700&&coarse.matches;
    if(options&&options.width===W&&options.height===H&&options.mobile===mobile&&options.dpr===Math.min(devicePixelRatio||1,mobile?1.35:CONFIG.flow.dpr))return;
    options={width:W,height:H,mobile,dpr:Math.min(devicePixelRatio||1,mobile?1.35:CONFIG.flow.dpr),count:mobile?CONFIG.flow.mobileLines:W<1100?CONFIG.flow.tabletLines:CONFIG.flow.lines,steps:mobile?CONFIG.flow.mobileSteps:CONFIG.flow.steps,lowPower:!!lowPower};
    version++;maskKey='';field.dataset.viewport=W+'x'+H;field.dataset.renderer=mobile?'touch':'pointer';field.dataset.artwork='silk-ribbons';
    if(worker)worker.postMessage({type:'resize',options});else if(painter)painter.resize(options);
    if(mx>W||my>H){mx=tx=W*.72;my=ty=H*.38}drawFlow(performance.now());dirty=layoutDirty=glassDirty=true;schedule();
  }
  function drawFlow(now){
    const fade=touchEnd?clamp(1-(now-touchEnd)/1200,0,1):virtualFade?clamp((now-virtualFade)/500,0,1):1;
    render({time:reduced.matches?0:phase*CONFIG.flow.timeScale+scrollY*.000015,mx,my,pressure:reduced.matches?0:pressureAmount,energy:pointerEnergy,dark:root.classList.contains('theme-dark'),radius:(CONFIG.flow.clearRadius+Math.min(CONFIG.flow.clearMax-CONFIG.flow.clearRadius,speed*2.2))*Math.max(.01,fade),fade,input:hasPointer||touchEnd?'pointer':'virtual'});
  }
  function updateFlow(now){
    if(!reduced.matches&&!hasPointer&&now-lastInput>CONFIG.flow.idle&&!touchEnd){tx=W*(.5+.22*Math.sin(now*CONFIG.flow.idleX));ty=H*(.45+.15*Math.sin(now*CONFIG.flow.idleY+1))}
    if(touchEnd&&now-touchEnd>=1200){touchEnd=0;lastInput=-10000;virtualFade=now}
    if(virtualFade&&now-virtualFade>500)virtualFade=0;
    const distance=Math.hypot(tx-mx,ty-my),ease=reduced.matches?CONFIG.flow.follow*.5:CONFIG.flow.follow;
    mx+=(tx-mx)*ease;my+=(ty-my)*ease;speed=speed*.84+distance*.16;pointerEnergy=pointerEnergy*.86+clamp(speed/50,0,1)*.14;pressureAmount+=((hasPointer?1:0)-pressureAmount)*.12;
    if(!reduced.matches)phase+=Math.min(now-lastFlow,100);
    drawFlow(now);reduceSettle=reduced.matches&&distance>1?1:0;
  }
  function schedule(){if(!raf&&visible)raf=requestAnimationFrame(frame)}
  let nextFlow=0;
  function frame(now){
    raf=0;if(!visible)return;const started=profiling?performance.now():0;
    if(dirty){dirty=false;updateNav();if(layoutDirty){updateJourney();layoutDirty=false}}updateGlass();
    if(now>=nextFlow){updateFlow(now);lastFlow=now;const period=1000/CONFIG.flow.fps;nextFlow=now+period-(now-nextFlow)%period}
    if(stats){stats.mainFrames++;stats.main+=performance.now()-started}
    if(!reduced.matches||dirty||glassDirty||reduceSettle)schedule();
  }
  addEventListener('pointermove',e=>{pointerX=e.clientX;pointerY=e.clientY;hasPointer=true;tx=e.clientX;ty=e.clientY;lastInput=performance.now();touchEnd=virtualFade=0;dirty=glassDirty=true;reduceSettle=1;schedule()},{passive:true});
  addEventListener('pointerup',e=>{if(e.pointerType==='touch'){hasPointer=false;touchEnd=lastInput=performance.now();schedule()}},{passive:true});
  addEventListener('pointercancel',()=>{hasPointer=false;touchEnd=lastInput=performance.now();dirty=glassDirty=true;schedule()},{passive:true});
  addEventListener('pointerout',e=>{if(e.relatedTarget===null){hasPointer=false;lastInput=performance.now();dirty=glassDirty=true;schedule()}},{passive:true});
  addEventListener('scroll',()=>{dirty=layoutDirty=glassDirty=true;schedule()},{passive:true});
  addEventListener('resize',()=>{clearTimeout(resizeTimer);resizeTimer=setTimeout(()=>{layoutJourney();resizeFlow()},100)},{passive:true});let resizeTimer=0;
  document.addEventListener('visibilitychange',()=>{visible=!document.hidden;$('.ambient-blobs').style.animationPlayState=visible?'running':'paused';all('.ambient-blobs span').forEach(s=>s.style.animationPlayState=visible?'running':'paused');if(!visible&&raf){cancelAnimationFrame(raf);raf=0}else if(visible){lastFlow=nextFlow=0;schedule();flush()}});
  reduced.addEventListener('change',()=>{nav.style.setProperty('--nav-x','0deg');nav.style.setProperty('--nav-y','0deg');layoutJourney();dirty=glassDirty=true;lastFlow=nextFlow=0;drawFlow(performance.now());schedule()});
  root.addEventListener('themechange',()=>{lastFlow=nextFlow=0;dirty=glassDirty=true;drawFlow(performance.now());schedule()});
  new IntersectionObserver(entries=>{heroVisible=entries[0].isIntersecting;field.style.setProperty('--flow-opacity',heroVisible?'1':'.7')}).observe($('.hero'));
  const translation=$('.translation-wide');
  new IntersectionObserver((entries,observer)=>{if(entries[0].isIntersecting){translation.classList.add('drawn');observer.disconnect()}},{threshold:.25}).observe(translation);


  // 原生 details 的展开与收起都平滑，取消重复动画避免卡住。
  all('.faq-list details').forEach(details=>{
    const summary=details.querySelector('summary');let animation=null,closing=false;
    summary.addEventListener('click',e=>{
      if(reduced.matches)return;e.preventDefault();const start=details.getBoundingClientRect().height;if(animation){animation.cancel();animation=null}
      const open=!details.open||closing;closing=!open;
      details.open=true;details.style.height='auto';const end=open?details.getBoundingClientRect().height:summary.getBoundingClientRect().height;
      animation=details.animate([{height:start+'px'},{height:end+'px'}],{duration:280,easing:'cubic-bezier(.2,.8,.2,1)'});
      animation.onfinish=()=>{details.open=open;closing=false;details.style.height='';animation=null};
    });
  });
  coarse.addEventListener('change',resizeFlow);
  layoutJourney();resizeFlow();startRenderer();dirty=glassDirty=true;schedule();
})();
