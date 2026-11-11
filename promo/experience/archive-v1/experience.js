(() => {
  'use strict';
  const root = document.documentElement;
  const body = document.body;
  const $ = (selector) => document.querySelector(selector);
  const chapters = [...document.querySelectorAll('.chapter')];
  const chapterLinks = [...document.querySelectorAll('.chapter-nav a')];
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const finePointer = window.matchMedia('(pointer: fine)');
  const story = $('#stories');
  const panels = [...document.querySelectorAll('.comic-panel')];
  const audio = $('#ambient-audio');
  let metrics = [], pageHeight = 1, viewport = {w: innerWidth, h: innerHeight};
  let pointer = {x: 0, y: 0}, targetPointer = {x: 0, y: 0};
  let playing = false, autoStart = 0, lastFrame = 0, dirty = true, activeChapter = -1, cameraY = scrollY;
  let toastTimer, helloTimer, tabRequest = 0;
  let autoStops = [], autoView = '';
  const clamp = (v, a = 0, b = 1) => Math.min(b, Math.max(a, v));
  const lerp = (a, b, p) => a + (b - a) * p;
  const smooth = (p) => {const v = clamp(p); return v * v * (3 - 2 * v);};
  const range = (x, a, b) => smooth((x - a) / (b - a));
  const inverseSmooth = (value) => {let lo=0,hi=1;for(let i=0;i<18;i++){const mid=(lo+hi)/2;if(smooth(mid)<value)lo=mid;else hi=mid;}return (lo+hi)/2;};

  function measure() {
    viewport = {w: innerWidth, h: innerHeight};
    metrics = chapters.map((node) => ({top: node.offsetTop, height: node.offsetHeight}));
    pageHeight = Math.max(1, document.documentElement.scrollHeight - viewport.h);
    const storyEnd = metrics[1].top + metrics[1].height - viewport.h;
    autoStops = [
      {t: 0, y: 0}, {t: 4, y: 0},
      {t: 14, y: metrics[1].top}, {t: 33, y: storyEnd},
      {t: 39, y: metrics[2].top},
      {t: 52, y: Math.max(metrics[2].top, metrics[2].top + metrics[2].height - viewport.h)},
      {t: 60, y: metrics[3].top},
      {t: 69, y: metrics[3].top + Math.max(0, metrics[3].height - viewport.h)},
      {t: 78, y: metrics[4].top}, {t: 86, y: pageHeight}, {t: 89, y: pageHeight},
    ];
    dirty = true;
  }

  function renderStory(y) {
    const m = metrics[1];
    if (!m) return;
    const p = clamp((y - m.top) / Math.max(1, m.height - viewport.h));
    const mobile = viewport.w < 701;
    const spread = range(p, .07, .55);
    const settle = range(p, .62, .97);
    const w = viewport.w, h = viewport.h;
    const startX = mobile ? w * .08 : w * .19;
    const startY = mobile ? h * .03 : 0;
    const end = mobile
      ? [{x: -.32*w, y: -.16*h, r: -9}, {x: .04*w, y: .07*h, r: 3}, {x: .36*w, y: -.18*h, r: 12}, {x: -.2*w, y: .3*h, r: -5}]
      : [{x: -.32*w, y: -.03*h, r: -7}, {x: 0, y: .01*h, r: 2}, {x: .32*w, y: -.02*h, r: 7}, {x: .2*w, y: .36*h, r: -5}];
    const rotations = [-11, 4, 14, 21];
    panels.forEach((panel, i) => {
      const e = end[i];
      let x = lerp(startX + i * 10, e.x, spread);
      let dy = lerp(startY + i * 5, e.y, spread);
      let scale = lerp(mobile ? .82 : .86, 1, spread);
      if (i === 1) {x += settle * w * .07; scale += settle * (mobile ? .15 : .16); dy -= settle * h * .025;}
      if (i === 0) {x -= settle * w * .15; dy -= settle * h * .18;}
      if (i === 2) {x += settle * w * .09; dy -= settle * h * .12;}
      if (i === 3) {x += settle * w * .19; dy += settle * h * .1;}
      const r = lerp(rotations[i], e.r, spread) * (1 - settle * .6);
      panel.style.transform = `translate(-50%,-50%) translate3d(${x.toFixed(2)}px,${dy.toFixed(2)}px,0) rotate(${r.toFixed(2)}deg) scale(${scale.toFixed(4)})`;
      panel.style.opacity = String(i === 3 ? lerp(.45, .95, spread) : 1);
    });
    const titleP = range(p, .09, .36);
    $('.story-title').style.opacity = String(1 - titleP);
    $('.story-title').style.transform = `translate3d(${-titleP * 80}px,${-titleP * 20}px,0)`;
    $('.story-copy').style.opacity = String(range(p, .66, .9));
    $('.story-copy').style.transform = `translateY(${lerp(15, 0, range(p,.66,.9))}px)`;
    $('.story-page-counter').innerHTML = `0${Math.min(4, 1 + Math.floor(p * 4))} <i>/</i> 04`;
  }

  function renderScroll() {
    const y = scrollY;
    root.style.setProperty('--scroll', String(clamp(y / pageHeight)));
    root.style.setProperty('--hero-progress', String(clamp(y / viewport.h)));
    const focus = y + viewport.h * .4;
    let current = 0;
    metrics.forEach((m, i) => {if (focus >= m.top) current = i;});
    if (current !== activeChapter) {
      activeChapter = current;
      chapterLinks.forEach((a, i) => {
        a.classList.toggle('active', i === current);
        if (i === current) a.setAttribute('aria-current','location'); else a.removeAttribute('aria-current');
      });
      document.querySelectorAll('.top-nav a').forEach((a) => a.classList.toggle('active', a.hash === '#' + chapters[current].id));
    }
    let tone=0;metrics.forEach((m,i)=>{if(y+60>=m.top)tone=i;});
    body.classList.toggle('light-section',chapters[tone].dataset.tone==='light');
    dirty = false;
  }

  function showToast(message) {
    const el = $('.toast'); clearTimeout(toastTimer);
    el.textContent = message; el.classList.add('visible');
    toastTimer = setTimeout(() => el.classList.remove('visible'), 2600);
  }

  function setPlaying(value, announce = false) {
    playing = value;
    if(value)autoView='';
    $('#autoplay-toggle').setAttribute('aria-pressed', String(value));
    $('.play-icon').textContent = value ? 'Ⅱ' : '▶';
    $('.play-label').textContent = value ? '暂停演绎' : '自动演绎';
    root.style.scrollBehavior = value ? 'auto' : '';
    if (announce) showToast(value ? '演绎开始 · 滚动或触碰即可暂停' : '已暂停，可以自由探索');
  }

  $('#autoplay-toggle').addEventListener('click', () => {
    if (playing) return setPlaying(false, true);
    measure();
    let t = 0;
    if (scrollY > pageHeight - 80) window.scrollTo({top:0, behavior:'instant'});
    else if (scrollY > 20) {
      for (let i = 1; i < autoStops.length; i++) {
        if (autoStops[i].y >= scrollY && autoStops[i].y > autoStops[i-1].y) {
          t = lerp(autoStops[i-1].t, autoStops[i].t, inverseSmooth(clamp((scrollY-autoStops[i-1].y)/(autoStops[i].y-autoStops[i-1].y))));break;
        }
      }
    }
    autoStart = performance.now() - t * 1000;
    setPlaying(true, true);
  });

  function stopForInput() {if (playing) setPlaying(false);}
  addEventListener('wheel', stopForInput, {passive:true});
  addEventListener('touchstart', stopForInput, {passive:true});
  addEventListener('keydown', (e) => {
    if (['ArrowDown','ArrowUp','PageDown','PageUp','Home','End','Escape',' '].includes(e.key)) stopForInput();
  });
  document.querySelectorAll('a[href^="#"]').forEach((a) => a.addEventListener('click', stopForInput));

  const views = {
    shelf: {src:'assets/shelf.webp', alt:'Ciallo阅读真实书架界面', label:'属于你的书架'},
    reader: {src:'assets/reader.webp', alt:'漫画阅读的真实玻璃控制面板', label:'故事里的小小秩序'},
    glass: {src:'assets/glass.webp', alt:'Ciallo阅读真实玻璃设置界面', label:'光，在界面里流动'},
    novel: {src:'assets/novel-controls.webp', alt:'真实小说排版与显示设置', label:'文字，也有自己的节奏'},
  };
  Object.values(views).forEach(({src}) => {const im=new Image(); im.src=src;});
  const tabs=[...document.querySelectorAll('[data-view]')];
  async function selectView(key) {
    const view=views[key]; if (!view) return;
    const request=++tabRequest;
    const img=$('#display-image');
    tabs.forEach((tab) => {const active=tab.dataset.view===key;tab.setAttribute('aria-selected',String(active));tab.tabIndex=active?0:-1;});
    $('#display-panel').setAttribute('aria-labelledby','tab-'+key);
    $('#display-panel').setAttribute('aria-label',view.label);
    $('#exhibit-caption').textContent=view.label;
    const replacement=new Image(); replacement.src=view.src;
    try {await replacement.decode();} catch {showToast('画面暂时没有加载成功，请稍后重试');return;}
    if(request!==tabRequest)return;
    img.classList.add('changing');
    setTimeout(()=>{
      if(request!==tabRequest)return;
      img.src=view.src;img.alt=view.alt;img.classList.toggle('fit-contain',key==='novel');display.classList.toggle('panel-view',key==='novel');img.classList.remove('changing');
    }, reducedMotion.matches?0:170);
  }
  tabs.forEach((tab,index)=>{
    tab.addEventListener('click',()=>{stopForInput();selectView(tab.dataset.view);});
    tab.addEventListener('keydown',(e)=>{
      let next;
      if(e.key==='ArrowRight')next=(index+1)%tabs.length;
      if(e.key==='ArrowLeft')next=(index+tabs.length-1)%tabs.length;
      if(e.key==='Home')next=0;if(e.key==='End')next=tabs.length-1;
      if(next!==undefined){e.preventDefault();tabs[next].focus();selectView(tabs[next].dataset.view);}
    });
  });

  const display=$('.device-display');
  display.addEventListener('pointermove',(e)=>{
    if(!finePointer.matches||reducedMotion.matches)return;
    const box=display.getBoundingClientRect();
    const x=(e.clientX-box.left)/box.width-.5,y=(e.clientY-box.top)/box.height-.5;
    display.style.transform=`rotateX(${-y*7}deg) rotateY(${x*15}deg) rotate(-3deg)`;
  });
  display.addEventListener('pointerleave',()=>display.style.transform='');

  $('#say-hello').addEventListener('click',()=>{
    stopForInput();
    clearTimeout(helloTimer);
    const img=$('#roxy-character');
    img.src='assets/roxy-hello.webp';img.alt='Roxy 开心地回应招呼';
    img.classList.remove('hello');void img.offsetWidth;img.classList.add('hello');
    $('.roxy-response').textContent='Ciallo～ 今天也一起读书吧。';
    const field=$('.roxy-sparkles');field.replaceChildren();
    if(!reducedMotion.matches)for(let i=0;i<7;i++){
      const star=document.createElement('i');star.textContent=i%2?'✦':'✧';
      const angle=(i/7)*Math.PI*2;
      star.style.setProperty('--sx',`${Math.cos(angle)*(90+i*9)}px`);
      star.style.setProperty('--sy',`${Math.sin(angle)*120-50}px`);
      star.style.animationDelay=`${i*.04}s`;field.append(star);
    }
    helloTimer=setTimeout(()=>{img.src='assets/roxy.webp';img.alt='Roxy 抱着书陪伴阅读';img.classList.remove('hello');field.replaceChildren();},2400);
  });

  $('#sound-toggle').addEventListener('click',async()=>{
    if(!audio.paused){audio.pause();body.classList.remove('music-on');$('#sound-toggle').setAttribute('aria-pressed','false');$('#sound-toggle').setAttribute('aria-label','打开背景音乐');return;}
    audio.volume=.26;
    try{await audio.play();body.classList.add('music-on');$('#sound-toggle').setAttribute('aria-pressed','true');$('#sound-toggle').setAttribute('aria-label','关闭背景音乐');}
    catch{showToast('音乐暂时无法播放，其余体验可正常使用');}
  });
  $('#fullscreen-toggle').addEventListener('click',async()=>{
    try{
      if(document.fullscreenElement)await document.exitFullscreen();
      else if(root.requestFullscreen)await root.requestFullscreen();
      else showToast('当前浏览器暂不支持全屏');
    }catch{showToast('全屏未能打开，仍可正常浏览');}
  });
  document.addEventListener('fullscreenchange',()=>{
    $('#fullscreen-toggle').setAttribute('aria-label',document.fullscreenElement?'退出全屏':'全屏观看');measure();
  });
  addEventListener('pointermove',(e)=>{
    if(!finePointer.matches||reducedMotion.matches)return;
    targetPointer={x:clamp(e.clientX/viewport.w,0,1)*2-1,y:clamp(e.clientY/viewport.h,0,1)*2-1};
  },{passive:true});
  addEventListener('scroll',()=>{dirty=true;},{passive:true});
  addEventListener('resize',measure,{passive:true});
  reducedMotion.addEventListener('change',measure);
  document.addEventListener('visibilitychange',()=>{if(document.hidden){setPlaying(false);audio.pause();body.classList.remove('music-on');$('#sound-toggle').setAttribute('aria-pressed','false');$('#sound-toggle').setAttribute('aria-label','打开背景音乐');}});

  function tick(now){
    if(!document.hidden){
      if(playing){
        const elapsed=(now-autoStart)/1000;
        if(elapsed>=40&&elapsed<54){
          const key=elapsed<43?'shelf':elapsed<47?'reader':elapsed<50?'glass':'novel';
          if(autoView!==key){autoView=key;selectView(key);}
        }
        if(elapsed>=89){setPlaying(false);showToast('故事暂告一段落，下一页由你开启。');}
        else{
          const i=autoStops.findIndex((stop)=>stop.t>elapsed);
          if(i>0){const a=autoStops[i-1],b=autoStops[i];const p=smooth((elapsed-a.t)/(b.t-a.t));window.scrollTo({top:lerp(a.y,b.y,p),behavior:'instant'});dirty=true;}
        }
      }
      const dt=Math.min(3,(now-lastFrame)/16.67||1),damp=1-Math.pow(.91,dt);
      const cameraMoving=Math.abs(cameraY-scrollY)>.08;
      if(cameraMoving||dirty){cameraY=reducedMotion.matches?scrollY:lerp(cameraY,scrollY,1-Math.pow(.82,dt));renderStory(cameraY);}
      const pointerActive=Math.abs(targetPointer.x-pointer.x)+Math.abs(targetPointer.y-pointer.y)>.001;
      if(pointerActive&&!reducedMotion.matches){pointer.x=lerp(pointer.x,targetPointer.x,damp);pointer.y=lerp(pointer.y,targetPointer.y,damp);root.style.setProperty('--px',pointer.x.toFixed(4));root.style.setProperty('--py',pointer.y.toFixed(4));}
      if(dirty)renderScroll();
    }
    lastFrame=now;requestAnimationFrame(tick);
  }
  measure();renderScroll();renderStory(scrollY);requestAnimationFrame(tick);
  document.fonts.ready.then(measure);
  addEventListener('load',measure,{once:true});
})();
