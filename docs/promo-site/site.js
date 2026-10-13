/* ============================================================
   Ciallo Reader 官网 — 交互脚本
   原生 JS，无依赖。所有可调参数集中在下方 CONFIG。
   ============================================================ */
'use strict';

/* ---------- 集中配置 ---------- */
const CONFIG = {
  curves: {
    countDesktop: 84,         // 桌面端目标线数
    countMobile: 40,          // 手机端目标线数
    stepsDesktop: 40,         // 每个方向积分步数
    stepsMobile: 28,
    stepSize: 8,
    bandTop: 0.13,
    bandHeight: 0.74,
    fieldX: 0.0014,
    fieldY: 0.0018,
    fieldTime: 0.00012,
    sigmaRatio: 0.13,
    sigmaMin: 170,
    sigmaMax: 280,
    ease: 0.14,
    idleDelay: 2500,
    clearRadius: 240,
    clearRadiusMax: 340,
    blurHeavy: 18,
    blurSoft: 5,
    scrollFps: 30
  },
  lens: {                    // 液态玻璃边缘折射参数（bezel 宽度 / 位移强度）
    nav:  { bezel: 26, scale: 64 },
    pill: { bezel: 16, scale: 34 },
    card: { bezel: 38, scale: 74 },
    cap:  { bezel: 14, scale: 28 }
  },
  nav: { tiltY: 12, tiltX: 8 },   // 导航 3D 倾斜角度
  card: { tilt: 10 },             // 卡片 hover 倾斜角度
  dock: { radius: 120, boost: 0.12 }, // 格式胶囊磁吸半径 / 放大倍率
  lensMapMax: 320                  // 位移贴图最长边（px，越大越精细越耗时）
};

const RM = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
const $  = (s, c) => (c || document).querySelector(s);
const $$ = (s, c) => Array.from((c || document).querySelectorAll(s));
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));

/* ============================================================
   1. 主题：跟随系统 + 手动切换（localStorage 记忆）
   ============================================================ */
(function theme(){
  const root = document.documentElement;
  const meta = $('meta[name="theme-color"]');
  let saved = null;
  try { saved = localStorage.getItem('cr-theme'); } catch (e) {}
  if (saved === 'light' || saved === 'dark') root.dataset.theme = saved;
  const darkMQ = window.matchMedia('(prefers-color-scheme: dark)');
  const current = () => root.dataset.theme || (darkMQ.matches ? 'dark' : 'light');
  const syncMeta = () => { if (meta) meta.content = current() === 'dark' ? '#090a12' : '#f4f3f8'; };
  syncMeta();
  darkMQ.addEventListener('change', syncMeta);
  const btn = $('#themeBtn');
  if (btn) btn.addEventListener('click', () => {
    const next = current() === 'dark' ? 'light' : 'dark';
    root.dataset.theme = next;
    try { localStorage.setItem('cr-theme', next); } catch (e) {}
    syncMeta();
  });
})();

/* ============================================================
   2. 流场背景：向量场流线 + 三层渐隐显影
   ============================================================ */
(function flowField(){
  const field = $('#flowField');
  if (!field) return;
  const C = CONFIG.curves;
  const layers = [$('#flowHeavy'), $('#flowSoft'), $('#flowSharp')].filter(Boolean);
  const source = document.createElement('canvas');
  const sourceCtx = source.getContext('2d', { alpha: true });
  let W = 0, H = 0, dpr = 1, seeds = [], rafId = 0, lastFrame = 0;
  let visible = !document.hidden, heroVisible = true, touchEndAt = 0, virtualActive = false, virtualFadeAt = 0, lowPower = false;
  let mx = innerWidth * .5, my = innerHeight * .45;
  let tx = mx, ty = my, speed = 0, lastInput = performance.now();
  const reduceMotion = RM;
  field.style.setProperty('--flow-blur-heavy', C.blurHeavy + 'px');
  field.style.setProperty('--flow-blur-soft', C.blurSoft + 'px');
  field.style.setProperty('--flow-opacity', '1');

  const hash = (x, y) => {
    const n = Math.sin(x * 127.1 + y * 311.7) * 43758.5453;
    return n - Math.floor(n);
  };
  const densityAt = (x, y, mobile) => {
    const noise = .5 + .5 * Math.sin(x * .0022 + Math.cos(y * .0026));
    return (mobile ? .82 : .66) + noise * (mobile ? .18 : .34);
  };

  function buildSeeds(){
    const mobile = W < 700;
    const gap = mobile ? 110 : 90;
    const y0 = H * (mobile ? .04 : C.bandTop);
    const y1 = H * (mobile ? .96 : Math.min(.96, C.bandTop + C.bandHeight));
    const candidates = [];
    let index = 0;
    for (let y = y0; y <= y1; y += gap){
      for (let x = -gap * .4; x < W + gap * .4; x += gap){
        const jx = (hash(index + 11, 2) - .5) * gap * .38;
        const jy = (hash(index + 7, 13) - .5) * gap * .38;
        const px = x + gap * .45 + jx;
        const py = y + jy;
        if (hash(index + 31, 23) < densityAt(px, py, mobile)) candidates.push({ x: px, y: py, i: index });
        index++;
      }
    }
    const target = mobile ? C.countMobile : C.countDesktop;
    if (candidates.length > target){
      const stride = candidates.length / target;
      seeds = Array.from({ length: target }, (_, i) => candidates[Math.floor((i + .5) * stride)]);
    } else seeds = candidates;
  }

  function resize(){
    W = window.innerWidth; H = window.innerHeight;
    dpr = Math.min(window.devicePixelRatio || 1, 2);
    source.width = Math.max(1, Math.round(W * dpr));
    source.height = Math.max(1, Math.round(H * dpr));
    sourceCtx.setTransform(dpr, 0, 0, dpr, 0, 0);
    lowPower = W < 700 && navigator.hardwareConcurrency > 0 && navigator.hardwareConcurrency <= 4;
    field.classList.toggle('flow-low-power', lowPower);
    layers.forEach(canvas => {
      const factor = canvas.id === 'flowSharp' ? 1 : W < 700 ? 1 / 3 : canvas.id === 'flowHeavy' ? .5 : .66;
      canvas.width = Math.max(1, Math.round(W * dpr * factor));
      canvas.height = Math.max(1, Math.round(H * dpr * factor));
    });
    buildSeeds();
    tx = clamp(tx, 0, W); ty = clamp(ty, 0, H);
    mx = clamp(mx, 0, W); my = clamp(my, 0, H);
    draw(performance.now());
  }

  function fieldAngle(x, y, now, scroll){
    const t = reduceMotion ? 0 : now * C.fieldTime;
    const sy = y + (reduceMotion ? 0 : scroll * .25);
    return Math.sin(x * C.fieldX + t) * 1.05
      + Math.cos(sy * C.fieldY - t * 1.22) * .78
      + Math.sin((x + sy) * .0012 + t * .84) * .62
      + Math.cos((x - sy) * .00145 - t * .65) * .34;
  }

  function direction(x, y, now, sign, sigma, vortexBoost, scroll){
    const angle = fieldAngle(x, y, now, scroll);
    let vx = Math.cos(angle), vy = Math.sin(angle);
    const dx = x - mx, dy = y - my, r2 = dx * dx + dy * dy;
    const weight = Math.exp(-r2 / (2 * sigma * sigma));
    if (r2 > 1){
      const swirl = weight * 3.2 * vortexBoost;
      const r = Math.sqrt(r2);
      vx -= swirl * dy / r;
      vy += swirl * dx / r;
    }
    const length = Math.hypot(vx, vy) || 1;
    return { x: vx / length * sign, y: vy / length * sign };
  }

  function trace(seed, sign, steps, now, sigma, vortexBoost, scroll){
    const points = [{ x: seed.x, y: seed.y }];
    let x = seed.x, y = seed.y;
    for (let n = 0; n < steps; n++){
      const v = direction(x, y, now, sign, sigma, vortexBoost, scroll);
      const mid = direction(x + v.x * C.stepSize * .5, y + v.y * C.stepSize * .5,
        now, sign, sigma, vortexBoost, scroll);
      x += mid.x * C.stepSize;
      y += mid.y * C.stepSize;
      points.push({ x, y });
    }
    points.reverse();
    return points;
  }

  function smoothPath(ctx, points){
    ctx.beginPath();
    ctx.moveTo(points[0].x, points[0].y);
    for (let i = 1; i < points.length - 1; i++){
      const midX = (points[i].x + points[i + 1].x) * .5;
      const midY = (points[i].y + points[i + 1].y) * .5;
      ctx.quadraticCurveTo(points[i].x, points[i].y, midX, midY);
    }
    const last = points[points.length - 1];
    ctx.lineTo(last.x, last.y);
  }

  function maskAt(x, y, radius, fade){
    const r = Math.max(1, radius);
    const px = `${x.toFixed(1)}px`, py = `${y.toFixed(1)}px`;
    const stops = [0, .20, .38, .56, .74, .88, 1].map(v => (r * v).toFixed(1) + 'px');
    const sharp = [1, .94, .72, .42, .18, .05, 0].map(a => a * fade);
    const heavy = sharp.map(a => 1 - a);
    const gradient = values => `radial-gradient(circle ${r.toFixed(1)}px at ${px} ${py}, ${values.map((a, i) => `rgba(0,0,0,${a.toFixed(3)}) ${stops[i]}`).join(',')})`;
    const soft = [0, 0, .38, .62, .30, 0, 0].map(a => a * fade);
    field.style.setProperty('--mx', px);
    field.style.setProperty('--my', py);
    field.style.setProperty('--r', r.toFixed(1) + 'px');
    field.style.setProperty('--flow-mask-sharp', gradient(sharp));
    field.style.setProperty('--flow-mask-heavy', gradient(heavy));
    field.style.setProperty('--flow-mask-soft', gradient(soft));
  }

  function copyLayers(){
    layers.forEach(canvas => {
      if (lowPower && canvas.id === 'flowSoft') return;
      const ctx = canvas.getContext('2d');
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      ctx.imageSmoothingEnabled = true;
      ctx.imageSmoothingQuality = 'high';
      ctx.drawImage(source, 0, 0, canvas.width, canvas.height);
    });
  }

  function draw(now){
    const mobile = W < 700;
    const steps = mobile ? C.stepsMobile : C.stepsDesktop;
    const lightTheme = document.documentElement.dataset.theme === 'light'
      || (!document.documentElement.dataset.theme && !matchMedia('(prefers-color-scheme: dark)').matches);
    sourceCtx.clearRect(0, 0, W, H);
    sourceCtx.globalCompositeOperation = lightTheme ? 'source-over' : 'lighter';
    sourceCtx.lineCap = 'round';
    const sigma = clamp(W * C.sigmaRatio, C.sigmaMin, C.sigmaMax);
    const scroll = reduceMotion ? 0 : window.scrollY;
    const vortexBoost = reduceMotion ? 0 : 1 + Math.min(speed / 55, .6);
    const colors = [
      ['124,107,255', '53,194,255'],
      ['53,194,255', '76,240,176'],
      ['76,240,176', '255,122,184'],
      ['255,122,184', '124,107,255']
    ];
    for (const seed of seeds){
      const back = trace(seed, -1, steps, now, sigma, vortexBoost, scroll);
      const forward = trace(seed, 1, steps, now, sigma, vortexBoost, scroll);
      const points = back.concat(forward.slice(1));
      const first = points[0], last = points[points.length - 1];
      const colorIndex = (seed.i + Math.floor((seed.x / W + seed.y / H) * 2) + colors.length) % colors.length;
      const pair = colors[colorIndex];
      const grad = sourceCtx.createLinearGradient(first.x, first.y, last.x, last.y);
      grad.addColorStop(0, `rgba(${pair[0]},0)`);
      grad.addColorStop(.25, `rgba(${pair[0]},.8)`);
      grad.addColorStop(.60, `rgba(${pair[1]},.9)`);
      grad.addColorStop(1, `rgba(${pair[1]},0)`);
      let near = 0;
      for (let n = 0; n < points.length; n += 5){
        const dx = points[n].x - mx, dy = points[n].y - my;
        near = Math.max(near, Math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma)));
      }
      const rarity = hash(seed.i + 91, 7);
      const baseWidth = rarity > .985 ? 3 : 1 + rarity * 1.6;
      sourceCtx.strokeStyle = grad;
      sourceCtx.globalAlpha = (lightTheme ? .70 : .50) * (1 + .2 * near);
      sourceCtx.lineWidth = baseWidth * (1 + .35 * near);
      smoothPath(sourceCtx, points);
      sourceCtx.stroke();
    }
    sourceCtx.globalAlpha = 1;
    sourceCtx.globalCompositeOperation = 'source-over';
    copyLayers();
  }

  function frame(now){
    rafId = 0;
    if (!visible) return;
    if (!reduceMotion && !heroVisible && now - lastFrame < 1000 / C.scrollFps){ schedule(); return; }
    const touchReady = touchEndAt && now - touchEndAt >= 1200;
    const useVirtual = !reduceMotion && (virtualActive || now - lastInput > C.idleDelay || touchReady);
    if (useVirtual){
      virtualActive = true;
      tx = W * (.5 + .33 * Math.sin(now * .00031));
      ty = H * (.45 + .22 * Math.sin(now * .00047 + 1));
    }
    const dt = Math.hypot(tx - mx, ty - my);
    const ease = reduceMotion ? C.ease * .5 : C.ease;
    mx += (tx - mx) * ease;
    my += (ty - my) * ease;
    speed = speed * .84 + dt * .16;
    const touchFade = touchEndAt
      ? clamp(1 - (now - touchEndAt) / 1200, 0, 1)
      : virtualFadeAt ? clamp((now - virtualFadeAt) / 450, 0, 1) : 1;
    const radius = (C.clearRadius + Math.min(C.clearRadiusMax - C.clearRadius, speed * 2.2)) * touchFade;
    maskAt(mx, my, radius, touchFade);
    draw(now);
    if (touchReady){ touchEndAt = 0; virtualFadeAt = now; }
    if (virtualFadeAt && now - virtualFadeAt >= 450) virtualFadeAt = 0;
    lastFrame = now;
    if (!reduceMotion || (touchEndAt && now - touchEndAt < 1200)) schedule();
  }

  function schedule(){ if (!rafId && visible) rafId = requestAnimationFrame(frame); }
  function input(e){
    tx = e.clientX; ty = e.clientY;
    lastInput = performance.now();
    touchEndAt = 0;
    virtualActive = false;
    virtualFadeAt = 0;
    if (reduceMotion){
      mx += (tx - mx) * .5;
      my += (ty - my) * .5;
      maskAt(mx, my, C.clearRadius, 1);
      draw(performance.now());
    }
    else schedule();
  }

  window.addEventListener('pointermove', input, { passive: true });
  window.addEventListener('pointerup', e => {
    if (e.pointerType === 'touch'){
      touchEndAt = lastInput = performance.now();
      if (reduceMotion) schedule();
    }
  }, { passive: true });
  window.addEventListener('scroll', () => { if (reduceMotion) draw(performance.now()); else schedule(); }, { passive: true });
  window.addEventListener('resize', () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(resize, 140); }, { passive: true });
  let resizeTimer = 0;
  document.addEventListener('visibilitychange', () => {
    visible = !document.hidden;
    if (!visible && rafId) cancelAnimationFrame(rafId);
    if (visible){ lastFrame = 0; if (reduceMotion) draw(performance.now()); else schedule(); }
  });
  const hero = $('.hero');
  if (hero && 'IntersectionObserver' in window){
    new IntersectionObserver(entries => {
      heroVisible = entries[0].isIntersecting;
      field.style.setProperty('--flow-opacity', heroVisible ? '1' : '.7');
      if (!reduceMotion) schedule();
    }, { threshold: 0 }).observe(hero);
  }

  resize();
  maskAt(mx, my, C.clearRadius, 1);
  $('#themeBtn')?.addEventListener('click', () => { if (reduceMotion) draw(performance.now()); });
  if (!reduceMotion) schedule();
})();

/* ============================================================
   3. 液态玻璃：动态边缘光角度 + 光标高光位置
   ============================================================ */
(function glassLight(){
  const els = $$('.glass');
  if (!els.length || RM) return;
  let px = -9999, py = -9999, queued = false;
  function update(){
    queued = false;
    for (const el of els){
      const r = el.getBoundingClientRect();
      if (r.bottom < -80 || r.top > innerHeight + 80) continue;  // 屏外跳过
      const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
      const deg = Math.atan2(cy - py, cx - px) * 180 / Math.PI;
      el.style.setProperty('--a', deg.toFixed(1) + 'deg');       // 最亮边缘朝向光标
      el.style.setProperty('--mx', (px - r.left) + 'px');
      el.style.setProperty('--my', (py - r.top) + 'px');
    }
  }
  window.addEventListener('pointermove', (e) => {
    px = e.clientX; py = e.clientY;
    if (!queued){ queued = true; requestAnimationFrame(update); }
  }, { passive: true });
  window.addEventListener('scroll', () => {
    if (px < -999) return;
    if (!queued){ queued = true; requestAnimationFrame(update); }
  }, { passive: true });
})();

/* ============================================================
   4. 液态玻璃：边缘折射（SVG 位移贴图滤镜，仅 Chromium）
   ============================================================ */
(function glassLens(){
  const ua = navigator.userAgent;
  const isIOS = /iP(hone|ad|od)/.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const isChromium = /Chrome\/|Chromium\/|Edg\//.test(ua) && !/Firefox\/|CriOS|FxiOS|EdgiOS/.test(ua) && !isIOS;
  if (!isChromium) return;   // 其余浏览器保留 CSS 毛玻璃降级

  const NS = 'http://www.w3.org/2000/svg';
  const defs = document.createElementNS(NS, 'svg');
  defs.setAttribute('width', '0'); defs.setAttribute('height', '0');
  defs.setAttribute('aria-hidden', 'true');
  defs.style.position = 'absolute';
  defs.innerHTML = '<defs></defs>';
  document.body.appendChild(defs);
  const defsEl = defs.firstChild;
  let uid = 0;

  // 圆角矩形有符号距离场
  function sdRB(px, py, bx, by, r){
    const qx = Math.abs(px) - bx, qy = Math.abs(py) - by;
    const ax = Math.max(qx, 0), ay = Math.max(qy, 0);
    return Math.hypot(ax, ay) + Math.min(Math.max(qx, qy), 0) - r;
  }

  function makeMap(w, h, radius, bezel){
    const cv = document.createElement('canvas');
    cv.width = w; cv.height = h;
    const ctx = cv.getContext('2d');
    const img = ctx.createImageData(w, h);
    const d = img.data;
    const rr = Math.min(radius, Math.min(w, h) / 2);
    const bx = w / 2 - rr, by = h / 2 - rr;
    for (let y = 0; y < h; y++){
      for (let x = 0; x < w; x++){
        const px = x + 0.5 - w / 2, py = y + 0.5 - h / 2;
        const dep = -sdRB(px, py, bx, by, rr);   // 到边缘的向内距离
        const o = (y * w + x) * 4;
        let R = 128, B = 128;
        if (dep > 0 && dep < bezel){
          const m = Math.pow(1 - dep / bezel, 2.2);   // 只有边缘一圈弯折，中央平整
          const gx = sdRB(px + 1, py, bx, by, rr) - sdRB(px - 1, py, bx, by, rr);
          const gy = sdRB(px, py + 1, bx, by, rr) - sdRB(px, py - 1, bx, by, rr);
          const gl = Math.hypot(gx, gy) || 1;
          const nx = -gx / gl, ny = -gy / gl;         // 向内法线
          R = 128 - 127 * nx * m;
          B = 128 - 127 * ny * m;
        }
        d[o] = R; d[o + 1] = 128; d[o + 2] = B; d[o + 3] = 255;
      }
    }
    ctx.putImageData(img, 0, 0);
    return cv.toDataURL('image/png');
  }

  function buildFilter(el, cfg){
    const r = el.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return;
    const k = Math.min(1, CONFIG.lensMapMax / Math.max(r.width, r.height));
    const w = Math.max(8, Math.round(r.width * k));
    const h = Math.max(8, Math.round(r.height * k));
    const radius = (parseFloat(getComputedStyle(el).borderRadius) || 999) * k;
    const bezel = cfg.bezel * k;
    const S = cfg.scale * k;
    const map = makeMap(w, h, radius, bezel);
    const id = 'lens' + (uid++);
    const old = el.dataset.lensId && $('#' + el.dataset.lensId, defsEl);
    if (old) old.remove();
    // 三次位移（S / S-9 / S-18）→ 分通道保留 → screen 合成 = 边缘色散
    const f = document.createElementNS(NS, 'filter');
    f.setAttribute('id', id);
    f.setAttribute('color-interpolation-filters', 'sRGB');
    f.setAttribute('x', '-5%'); f.setAttribute('y', '-5%');
    f.setAttribute('width', '110%'); f.setAttribute('height', '110%');
    let inner = `<feImage href="${map}" x="0" y="0" width="100%" height="100%" preserveAspectRatio="none" result="map"/>`;
    const chans = [['R', S, '1 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 1 0'],
                   ['G', S - 9, '0 0 0 0 0  0 1 0 0 0  0 0 0 0 0  0 0 0 1 0'],
                   ['B', S - 18, '0 0 0 0 0  0 0 0 0 0  0 0 1 0 0  0 0 0 1 0']];
    for (const [ch, s, mtx] of chans){
      inner += `<feDisplacementMap in="SourceGraphic" in2="map" scale="${Math.max(s, 1)}" xChannelSelector="R" yChannelSelector="B" result="d${ch}"/>
        <feColorMatrix in="d${ch}" type="matrix" values="${mtx}" result="c${ch}"/>`;
    }
    inner += `<feBlend in="cR" in2="cG" mode="screen" result="rg"/><feBlend in="rg" in2="cB" mode="screen"/>`;
    f.innerHTML = inner;
    defsEl.appendChild(f);
    el.dataset.lensId = id;
    const bf = `url(#${id}) blur(2px) saturate(1.7) brightness(1.08)`;
    el.style.backdropFilter = bf;
    el.style.webkitBackdropFilter = bf;
  }

  function init(){
    $$('.glass[data-lens]').forEach((el) => {
      const cfg = CONFIG.lens[el.dataset.lens] || CONFIG.lens.pill;
      buildFilter(el, cfg);
      let t = 0;
      new ResizeObserver(() => {          // 尺寸变化时重建贴图
        clearTimeout(t);
        t = setTimeout(() => buildFilter(el, cfg), 220);
      }).observe(el);
    });
  }
  // 空闲时再生成，避免阻塞首屏
  if ('requestIdleCallback' in window) requestIdleCallback(init, { timeout: 1500 });
  else setTimeout(init, 600);
})();

/* ============================================================
   5. 导航：3D 倾斜 / 滚动收缩 / 章节滑块 / 移动面板
   ============================================================ */
(function nav(){
  const wrap = $('.nav-wrap'), nav = $('#nav');
  if (!wrap || !nav) return;

  // 3D 倾斜：随鼠标位置
  if (!RM) window.addEventListener('pointermove', (e) => {
    const ry = (e.clientX / innerWidth - 0.5) * CONFIG.nav.tiltY;
    const rx = -(e.clientY / innerHeight - 0.5) * CONFIG.nav.tiltX;
    nav.style.setProperty('--ry', ry.toFixed(2) + 'deg');
    nav.style.setProperty('--rx', rx.toFixed(2) + 'deg');
  }, { passive: true });

  // 滚动收缩
  let compact = false;
  window.addEventListener('scroll', () => {
    const c = window.scrollY > 40;
    if (c !== compact){ compact = c; wrap.classList.toggle('compact', c); }
  }, { passive: true });

  // 当前章节 → 滑动选中滑块
  const slider = $('#navSlider');
  const links = $$('.nav-links a');
  const map = new Map();
  links.forEach((a) => {
    const sec = $(a.getAttribute('href'));
    if (sec) map.set(sec, a);
  });
  function moveSlider(a){
    if (!slider) return;
    if (!a){ slider.style.opacity = '0'; return; }
    slider.style.opacity = '1';
    slider.style.width = a.offsetWidth + 'px';
    slider.style.transform = `translateX(${a.offsetLeft}px)`;
    links.forEach((l) => l.classList.toggle('active', l === a));
  }
  const io = new IntersectionObserver((ents) => {
    ents.forEach((en) => { if (en.isIntersecting) moveSlider(map.get(en.target)); });
  }, { rootMargin: '-40% 0px -55% 0px' });
  map.forEach((_, sec) => io.observe(sec));

  // 移动端汉堡面板
  const menuBtn = $('#menuBtn'), panel = $('#mobilePanel');
  if (menuBtn && panel){
    const isChinese = document.documentElement.lang.toLowerCase().startsWith('zh');
    function setOpen(open){
      panel.classList.toggle('open', open);
      menuBtn.setAttribute('aria-expanded', String(open));
      menuBtn.setAttribute('aria-label', open
        ? (isChinese ? '关闭菜单' : 'Close menu')
        : (isChinese ? '打开菜单' : 'Open menu'));
      $$('a', panel).forEach((a, i) => { a.style.transitionDelay = open ? (60 + i * 45) + 'ms' : '0ms'; });
    }
    menuBtn.addEventListener('click', () => {
      setOpen(!panel.classList.contains('open'));
    });
    panel.addEventListener('click', (e) => {
      if (e.target.closest('a')) setOpen(false);
    });
    document.addEventListener('keydown', e => { if (e.key === 'Escape') setOpen(false); });
  }
})();

/* ============================================================
   6. 产品展示：sticky 滚动切换三个场景
   ============================================================ */
(function showcase(){
  const sec = $('.showcase');
  if (!sec) return;
  const scenes = $$('.scene', sec);
  const screens = $$('.phone-screen', sec);
  const dots = $$('.showcase-dots i', sec);
  let cur = -1;
  function set(i){
    if (i === cur) return;
    cur = i;
    scenes.forEach((s, k) => s.classList.toggle('on', k === i));
    screens.forEach((s, k) => s.classList.toggle('on', k === i));
    dots.forEach((d, k) => d.classList.toggle('on', k === i));
  }
  function onScroll(){
    const r = sec.getBoundingClientRect();
    const total = sec.offsetHeight - innerHeight;
    const p = clamp(-r.top / total, 0, 0.999);
    set(Math.floor(p * scenes.length));
  }
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();
})();

/* ============================================================
   7. 特色卡片：hover 3D 倾斜
   ============================================================ */
(function cards(){
  if (RM) return;
  $$('.card').forEach((card) => {
    card.addEventListener('pointermove', (e) => {
      const r = card.getBoundingClientRect();
      const ry = ((e.clientX - r.left) / r.width - 0.5) * CONFIG.card.tilt;
      const rx = -((e.clientY - r.top) / r.height - 0.5) * CONFIG.card.tilt;
      card.style.setProperty('--cry', ry.toFixed(2) + 'deg');
      card.style.setProperty('--crx', rx.toFixed(2) + 'deg');
    });
    card.addEventListener('pointerleave', () => {
      card.style.setProperty('--cry', '0deg');
      card.style.setProperty('--crx', '0deg');
    });
  });
})();

/* ============================================================
   8. 格式胶囊：macOS Dock 式磁吸放大
   ============================================================ */
(function dock(){
  if (RM) return;
  const D = CONFIG.dock;
  $$('.caps').forEach((box) => {
    const caps = $$('.cap', box);
    box.addEventListener('pointermove', (e) => {
      for (const c of caps){
        const r = c.getBoundingClientRect();
        const d = Math.hypot(e.clientX - (r.left + r.width / 2), e.clientY - (r.top + r.height / 2));
        const s = 1 + D.boost * Math.max(0, 1 - d / D.radius);
        c.style.transform = `scale(${s.toFixed(3)})`;
      }
    });
    box.addEventListener('pointerleave', () => caps.forEach((c) => { c.style.transform = ''; }));
  });
})();

/* ============================================================
   9. 翻译流程：滚动入场时连线逐段画出
   ============================================================ */
(function flow(){
  const steps = $('.flow-steps');
  if (!steps) return;
  const path = $('.flow-line path');
  if (path){
    const len = path.getTotalLength();
    path.style.strokeDasharray = len;
    path.style.strokeDashoffset = len;
    path.style.transition = 'stroke-dashoffset 1.2s ' + getComputedStyle(document.documentElement).getPropertyValue('--ease') || 'ease';
  }
  const io = new IntersectionObserver((ents) => {
    ents.forEach((en) => {
      if (en.isIntersecting){
        steps.classList.add('drawn');
        if (path) path.style.strokeDashoffset = '0';
        io.disconnect();
      }
    });
  }, { threshold: 0.35 });
  io.observe(steps);
})();

/* ============================================================
   10. FAQ：高度平滑过渡的手风琴
   ============================================================ */
(function faq(){
  $$('.faq-item').forEach((d) => {
    const summary = $('summary', d);
    const body = $('.faq-body', d);
    if (!summary || !body) return;
    summary.addEventListener('click', (e) => {
      if (!d.open) return;                    // 展开交给浏览器默认 + CSS 过渡
      e.preventDefault();                     // 关闭：先动画再移除 open
      body.style.gridTemplateRows = '1fr';
      requestAnimationFrame(() => {
        body.style.gridTemplateRows = '0fr';
        body.addEventListener('transitionend', function h(){
          body.removeEventListener('transitionend', h);
          d.open = false;
          body.style.gridTemplateRows = '';
        });
      });
    });
  });
})();
