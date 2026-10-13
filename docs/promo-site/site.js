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
  let raf=0,dirty=true,glassDirty=true,lastFlow=0,visible=!document.hidden,heroVisible=true;
  let pointerX=innerWidth*.7,pointerY=innerHeight*.38,hasPointer=false;
  const glassElements=all('.glass');
  const nav=$('.nav'),links=all('.nav-links a'),slider=$('.nav-slider');
  let lastScroll=scrollY;
  function updateNav(){
    if(hasPointer&&!reduced.matches){nav.style.setProperty('--nav-y',(pointerX/innerWidth-.5)*CONFIG.nav.y+'deg');nav.style.setProperty('--nav-x',-(pointerY/innerHeight-.5)*CONFIG.nav.x+'deg')}
    const down=scrollY>lastScroll+1; if(Math.abs(scrollY-lastScroll)>1){$('.nav-wrap').classList.toggle('compact',down&&scrollY>40);lastScroll=scrollY}
    let active=null;
    links.forEach(link=>{const target=$(link.getAttribute('href'));if(target&&target.getBoundingClientRect().top<innerHeight*.42)active=link});
    links.forEach(link=>{link.classList.toggle('active',link===active);if(link===active)link.setAttribute('aria-current','location');else link.removeAttribute('aria-current')});
    if(active){slider.style.opacity='1';slider.style.width=active.offsetWidth+'px';slider.style.transform='translateX('+active.offsetLeft+'px)'}else slider.style.opacity='0';
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
  matchMedia('(min-width: 700px)').addEventListener('change',e=>{if(e.matches)setMenu(false)});

  // 展台由真实滚动位置驱动；减少动态效果时退回普通标签。
  const experience=$('#experience'),track=$('#readingJourney'),pin=$('.journey-pin');
  const tabs=all('[data-tab]'),panels=all('[data-panel]');
  let chapter=0,journeyEnabled=false,journeyHeight=0,journeyRange=0;
  function activate(index){
    if(index!==chapter){chapter=index;$('.journey-word').textContent=[label('从书架开始','Start with your library'),label('在文字里停留','Settle into the words'),label('沉浸每一个分镜','Get lost in every panel')][index]}
    tabs.forEach((tab,i)=>{const active=i===index;tab.setAttribute('aria-selected',String(active));tab.tabIndex=active?0:-1;panels[i].hidden=journeyEnabled?false:!active;panels[i].classList.toggle('is-active',active);panels[i].inert=!active;panels[i].tabIndex=active?0:-1;panels[i].querySelectorAll('button,a').forEach(control=>control.tabIndex=active?0:-1);panels[i].setAttribute('aria-hidden',String(!active))});
  }
  function layoutJourney(){
    journeyEnabled=!reduced.matches&&innerHeight>=650;
    experience.classList.toggle('journey-enabled',journeyEnabled);
    journeyHeight=Math.min(CONFIG.journey.maxHeight,innerHeight-(innerWidth<700?108:135));
    journeyRange=innerHeight*CONFIG.journey.distance;
    track.style.setProperty('--journey-height',journeyHeight+'px');track.style.setProperty('--journey-distance',journeyRange+'px');
    panels.forEach(panel=>{panel.setAttribute('role','tabpanel');panel.tabIndex=0});
    activate(chapter);dirty=true;glassDirty=true;schedule();
  }
  function updateJourney(){
    if(!journeyEnabled)return;
    const start=track.getBoundingClientRect().top-(innerWidth<700?83:102);
    const p=clamp(-start/journeyRange,0,.99999), index=Math.floor(p*3), local=p*3-index;
    activate(index);
    pin.style.setProperty('--scene-depth',((local-.5)*2).toFixed(4));
    tabs.forEach((tab,i)=>tab.style.setProperty('--tab-progress',i<index?'1':i===index?local.toFixed(4):'0'));
    track.dataset.chapter=tabs[index].dataset.tab;
  }
  function goChapter(index,focus=false){
    if(journeyEnabled){const top=scrollY+track.getBoundingClientRect().top-(innerWidth<700?83:102)+journeyRange*(index+.4)/3;window.scrollTo({top,behavior:reduced.matches?'auto':'smooth'})}else activate(index);
    if(focus)tabs[index].focus({preventScroll:true});
  }
  tabs.forEach((tab,index)=>{tab.addEventListener('click',()=>goChapter(index));tab.addEventListener('keydown',e=>{let next=index;if(e.key==='ArrowRight')next=(index+1)%3;else if(e.key==='ArrowLeft')next=(index+2)%3;else if(e.key==='Home')next=0;else if(e.key==='End')next=2;else return;e.preventDefault();goChapter(next,true)})});

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
  dialog.addEventListener('close',()=>{document.body.style.overflow=previousOverflow;if(dialogTrigger)dialogTrigger.focus({preventScroll:true});dirty=glassDirty=true;schedule()});

  // 光源分别指向每块玻璃；屏外材质不计算边缘光。
  function updateGlass(){
    if(!glassDirty)return;glassDirty=false;
    glassElements.forEach(el=>{const b=el.getBoundingClientRect();if(b.bottom<0||b.top>innerHeight||b.width===0)return;const x=hasPointer?pointerX:-innerWidth*.3,y=hasPointer?pointerY:-innerHeight*.3;el.style.setProperty('--a',(Math.atan2(y-b.top-b.height/2,x-b.left-b.width/2)*180/Math.PI+30)+'deg');el.style.setProperty('--light-x',x-b.left+'px');el.style.setProperty('--light-y',y-b.top+'px')});
  }
  all('[data-tilt]').forEach(card=>{card.addEventListener('pointermove',e=>{if(reduced.matches||e.pointerType==='touch')return;const b=card.getBoundingClientRect();card.style.setProperty('--card-y',((e.clientX-b.left)/b.width-.5)*CONFIG.cardTilt*2+'deg');card.style.setProperty('--card-x',-((e.clientY-b.top)/b.height-.5)*CONFIG.cardTilt*2+'deg')});card.addEventListener('pointerleave',()=>{card.style.setProperty('--card-x','0deg');card.style.setProperty('--card-y','0deg')})});
  all('.format-dock').forEach(dock=>{const caps=[...dock.children];dock.addEventListener('pointermove',e=>{if(reduced.matches||e.pointerType==='touch')return;caps.forEach(cap=>{const b=cap.getBoundingClientRect(),d=Math.hypot(e.clientX-b.left-b.width/2,e.clientY-b.top-b.height/2);cap.style.setProperty('--dock-scale',(1+CONFIG.dock.boost*Math.max(0,1-d/CONFIG.dock.radius)).toFixed(3))})});dock.addEventListener('pointerleave',()=>caps.forEach(cap=>cap.style.setProperty('--dock-scale','1')))});

  // SVG 折射贴图，仅 Chromium；相同尺寸共用滤镜，进入视野后再生成。
  const ua=navigator.userAgent,ios=/iP(hone|ad|od)/.test(ua)||(navigator.platform==='MacIntel'&&navigator.maxTouchPoints>1);
  if(/Chrome\/|Chromium\/|Edg\//.test(ua)&&!/Firefox\/|CriOS|FxiOS|EdgiOS/.test(ua)&&!ios){
    const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg');svg.setAttribute('aria-hidden','true');svg.setAttribute('width','0');svg.setAttribute('height','0');svg.style.cssText='position:absolute;pointer-events:none';const defs=document.createElementNS(ns,'defs');svg.appendChild(defs);document.body.appendChild(svg);
    const cache=new Map(),queued=new Set();let idle=0,uid=0;
    function signed(x,y,bx,by,r){const qx=Math.abs(x)-bx,qy=Math.abs(y)-by;return Math.hypot(Math.max(qx,0),Math.max(qy,0))+Math.min(Math.max(qx,qy),0)-r}
    function lens(el){
      const ew=el.offsetWidth,eh=el.offsetHeight;if(ew<4||eh<4)return;
      const cfg=el.matches('.nav,.mobile-panel')?CONFIG.lens.nav:el.matches('.core-card,.experience-display,.feature-card,.personal-panel,.translation-wide,.formats-inner,.detail-dialog')?CONFIG.lens.card:CONFIG.lens.pill;
      const k=Math.min(1,CONFIG.lens.maxMap/Math.max(ew,eh)),w=Math.max(8,Math.round(ew*k)),h=Math.max(8,Math.round(eh*k));
      const r=Math.min((parseFloat(getComputedStyle(el).borderRadius)||999)*k,w/2,h/2),bezel=cfg.bezel*k,S=cfg.scale;
      const key=[w,h,Math.round(r),cfg.scale,cfg.bezel].join(':');let id=cache.get(key);
      if(!id){
        id='ciallo-lens-'+uid++;const cv=document.createElement('canvas');cv.width=w;cv.height=h;const ctx=cv.getContext('2d'),map=ctx.createImageData(w,h),d=map.data,bx=w/2-r,by=h/2-r;
        for(let y=0;y<h;y++)for(let x=0;x<w;x++){
          const px=x+.5-w/2,py=y+.5-h/2,dep=-signed(px,py,bx,by,r),o=(y*w+x)*4;let R=128,B=128;
          if(dep>0&&dep<bezel){const m=(1-dep/bezel)**2.2,gx=signed(px+1,py,bx,by,r)-signed(px-1,py,bx,by,r),gy=signed(px,py+1,bx,by,r)-signed(px,py-1,bx,by,r),g=Math.hypot(gx,gy)||1;R=128+127*gx/g*m;B=128+127*gy/g*m}
          d[o]=R;d[o+1]=128;d[o+2]=B;d[o+3]=255;
        }
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

  // 原创丝带曲线：三组连续曲面，共享弧势、渐变与缓慢呼吸。
  const field=$('#flowField'),layers=[$('#flowHeavy'),$('#flowSoft'),$('#flowSharp')],source=document.createElement('canvas'),ctx=source.getContext('2d');
  if(!ctx)return;
  let W=innerWidth,H=innerHeight,seeds=[],mx=W*.72,my=H*.38,tx=mx,ty=my,speed=0,phase=0,lastInput=-10000,touchEnd=0,virtualFade=0;
  let pointerEnergy=0,pressureAmount=0,reduceSettle=0;
  const lowPower=(navigator.hardwareConcurrency&&navigator.hardwareConcurrency<=4)||(navigator.deviceMemory&&navigator.deviceMemory<=4);
  field.classList.toggle('flow-low-power',!!lowPower);
  function resizeFlow(){
    W=innerWidth;H=innerHeight;const mobile=W<700&&coarse.matches,dpr=Math.min(devicePixelRatio||1,mobile?1.35:CONFIG.flow.dpr);
    source.width=Math.round(W*dpr);source.height=Math.round(H*dpr);ctx.setTransform(dpr,0,0,dpr,0,0);
    layers.forEach((canvas,i)=>{const scale=i<2?(mobile?1/3:1/2):1;canvas.width=Math.round(W*dpr*scale);canvas.height=Math.round(H*dpr*scale)});
    const count=mobile?CONFIG.flow.mobileLines:W<1100?CONFIG.flow.tabletLines:CONFIG.flow.lines;field.dataset.renderer=mobile?'touch':'pointer';
    field.dataset.artwork='silk-ribbons';
    const perBand=Math.ceil(count/3);
    seeds=Array.from({length:count},(_,i)=>({band:Math.floor(i/perBand),q:(i%perBand)/(perBand-1),id:i}));
    if(mx>W||my>H){mx=tx=W*.72;my=ty=H*.38}drawFlow(performance.now());dirty=glassDirty=true;schedule();
  }
  // ART_GEOMETRY_BEGIN — 纯几何，可独立导出静态构图。
  const RIBBONS=[
    [[[-.18,.72],[-.30,.14],[0,0],[.39,.01],[0,0],[.61,.61],[1.22,.18]],[[-.18,1.18],[-.03,.42],[0,0],[.28,.16],[0,0],[.94,-.13],[1.19,-.09]]],
    [[[-.12,.91],[.26,.57],[0,0],[.48,.84],[0,0],[.79,.34],[1.20,.45]],[[-.14,1.17],[.18,.74],[0,0],[.55,.58],[0,0],[.60,.04],[1.22,-.03]]],
    [[[-.14,.42],[.23,-.02],[0,0],[.59,.71],[0,0],[1.06,.64],[1.20,.67]],[[-.14,.24],[.32,-.12],[0,0],[.55,.95],[0,0],[1.12,.94],[1.20,.89]]]
  ];
  function ribbonPoints(seed,t,steps,width,height){
    const z=seed.q,q=.9*z+.1*z*z*(3-2*z),[a,b]=RIBBONS[seed.band],artWidth=Math.max(width,height*.9),offset=(width-artWidth)/2;
    const knots=a.map((p,i)=>({x:p[0]*(1-q)+b[i][0]*q+.012*Math.sin(t*.7+i*.83+seed.band*1.4),y:p[1]*(1-q)+b[i][1]*q+.018*Math.sin(t+i*.61+seed.band*1.2)}));
    const dx=(knots[5].x-knots[1].x)*.28,dy=(knots[5].y-knots[1].y)*.28;
    knots[2]={x:knots[3].x-dx,y:knots[3].y-dy};knots[4]={x:knots[3].x+dx,y:knots[3].y+dy};
    const points=[];
    for(let part=0;part<2;part++){const p=knots.slice(part*3,part*3+4);for(let j=part?1:0;j<=steps;j++){const u=j/steps,v=1-u,A=v*v*v,B=3*v*v*u,C=3*v*u*u,D=u*u*u;points.push({x:(A*p[0].x+B*p[1].x+C*p[2].x+D*p[3].x)*artWidth+offset,y:(A*p[0].y+B*p[1].y+C*p[2].y+D*p[3].y)*height})}}
    return points;
  }
  // ART_GEOMETRY_END
  function path(points){ctx.beginPath();ctx.moveTo(points[0].x,points[0].y);for(let i=1;i<points.length-1;i++){const a=points[i],b=points[i+1];ctx.quadraticCurveTo(a.x,a.y,(a.x+b.x)/2,(a.y+b.y)/2)}const last=points[points.length-1];ctx.lineTo(last.x,last.y)}
  function masks(radius,fade){
    const floor=CONFIG.flow.ambientSharp,stops=[0,.20,.38,.56,.74,.88,1],sharp=[1,.94,.72,.42,.18,.05,0].map(a=>floor+(1-floor)*a*fade),heavy=sharp.map(a=>1-a),soft=[0,0,.38,.62,.30,0,0].map(a=>a*fade);
    const gradient=alpha=>'radial-gradient(circle '+radius.toFixed(1)+'px at '+mx.toFixed(1)+'px '+my.toFixed(1)+'px,'+alpha.map((a,i)=>'rgba(0,0,0,'+a.toFixed(3)+') '+(radius*stops[i]).toFixed(1)+'px').join(',')+')';
    field.style.setProperty('--flow-mask-sharp',gradient(sharp));field.style.setProperty('--flow-mask-heavy',gradient(heavy));field.style.setProperty('--flow-mask-soft',gradient(soft));field.style.setProperty('--mx',mx+'px');field.style.setProperty('--my',my+'px');field.style.setProperty('--r',radius+'px');
  }
  function drawFlow(now){
    const mobile=W<700&&coarse.matches,dark=root.classList.contains('theme-dark'),sigma=clamp(W*CONFIG.flow.sigmaRatio,CONFIG.flow.sigmaMin,CONFIG.flow.sigmaMax),steps=mobile?CONFIG.flow.mobileSteps:CONFIG.flow.steps;
    const t=reduced.matches?0:phase*CONFIG.flow.timeScale+scrollY*.000015,energy=pointerEnergy;
    ctx.clearRect(0,0,W,H);ctx.globalCompositeOperation='source-over';ctx.lineCap='round';
    const colors=dark?[
      ['151,130,242','174,145,231','121,157,237'],['100,186,203','134,166,238','167,143,239'],['202,145,184','174,153,231','156,130,218']
    ]:[['117,92,206','157,126,210','109,139,216'],['66,156,178','103,135,207','139,117,207'],['181,112,157','151,121,196','144,111,193']];
    seeds.forEach(seed=>{
      let points=ribbonPoints(seed,t,steps,W,H),near=0;
      points=points.map(p=>{const dx=p.x-mx,dy=p.y-my,w=Math.exp(-(dx*dx+dy*dy)/(2*sigma*sigma));near=Math.max(near,w);const pressure=reduced.matches?0:pressureAmount,pull=w*CONFIG.flow.collapse*(.7+energy*.3)*pressure,twist=w*CONFIG.flow.vortex*CONFIG.flow.pressureSpin*(.55+energy*.45)*pressure;return{x:p.x-dx*pull-dy*twist,y:p.y-dy*pull+dx*twist}});
      near*=reduced.matches?0:pressureAmount;
      const pair=colors[seed.band],grad=ctx.createLinearGradient(-W*.12,H*.3,W*1.1,H*.75);
      grad.addColorStop(0,'rgba('+pair[0]+',0)');grad.addColorStop(.13,'rgba('+pair[0]+',.72)');grad.addColorStop(.45,'rgba('+pair[1]+',1)');grad.addColorStop(.82,'rgba('+pair[2]+',.86)');grad.addColorStop(1,'rgba('+pair[2]+',0)');
      const depth=.48+.52*Math.sin(Math.PI*seed.q),width=.78+.3*depth+(seed.id%12===0?.18:0);ctx.strokeStyle=grad;ctx.globalAlpha=(dark?.68:.62)*depth*(1+.10*near);ctx.lineWidth=width*(1+.16*near);path(points);ctx.stroke();
    });
    ctx.globalAlpha=1;ctx.globalCompositeOperation='source-over';
    layers.forEach((canvas,i)=>{if(lowPower&&i===1)return;const c=canvas.getContext('2d');c.clearRect(0,0,canvas.width,canvas.height);c.drawImage(source,0,0,canvas.width,canvas.height)});
    const fade=touchEnd?clamp(1-(now-touchEnd)/1200,0,1):virtualFade?clamp((now-virtualFade)/500,0,1):1;
    const radius=(CONFIG.flow.clearRadius+Math.min(CONFIG.flow.clearMax-CONFIG.flow.clearRadius,speed*2.2))*Math.max(.01,fade);
    masks(radius,fade);field.dataset.input=hasPointer||touchEnd?'pointer':'virtual';field.dataset.pressure=pressureAmount.toFixed(3);
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
  function frame(now){
    raf=0;if(!visible)return;
    if(dirty){dirty=false;updateNav();updateJourney()}updateGlass();
    if(now-lastFlow>=1000/CONFIG.flow.fps){updateFlow(now);lastFlow=now}
    if(!reduced.matches||dirty||glassDirty||reduceSettle)schedule();
  }
  addEventListener('pointermove',e=>{pointerX=e.clientX;pointerY=e.clientY;hasPointer=true;tx=e.clientX;ty=e.clientY;lastInput=performance.now();touchEnd=virtualFade=0;dirty=glassDirty=true;reduceSettle=1;schedule()},{passive:true});
  addEventListener('pointerup',e=>{if(e.pointerType==='touch'){hasPointer=false;touchEnd=lastInput=performance.now();schedule()}},{passive:true});
  addEventListener('pointercancel',()=>{hasPointer=false;touchEnd=lastInput=performance.now();dirty=glassDirty=true;schedule()},{passive:true});
  addEventListener('pointerout',e=>{if(e.relatedTarget===null){hasPointer=false;lastInput=performance.now();dirty=glassDirty=true;schedule()}},{passive:true});
  addEventListener('scroll',()=>{dirty=glassDirty=true;schedule()},{passive:true});
  addEventListener('resize',()=>{clearTimeout(resizeTimer);resizeTimer=setTimeout(()=>{layoutJourney();resizeFlow()},100)},{passive:true});let resizeTimer=0;
  document.addEventListener('visibilitychange',()=>{visible=!document.hidden;$('.ambient-blobs').style.animationPlayState=visible?'running':'paused';all('.ambient-blobs span').forEach(s=>s.style.animationPlayState=visible?'running':'paused');if(!visible&&raf){cancelAnimationFrame(raf);raf=0}else if(visible){lastFlow=0;schedule()}});
  reduced.addEventListener('change',()=>{nav.style.setProperty('--nav-x','0deg');nav.style.setProperty('--nav-y','0deg');layoutJourney();dirty=glassDirty=true;lastFlow=0;drawFlow(performance.now());schedule()});
  root.addEventListener('themechange',()=>{lastFlow=0;dirty=glassDirty=true;drawFlow(performance.now());schedule()});
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
  layoutJourney();resizeFlow();dirty=glassDirty=true;schedule();
})();
