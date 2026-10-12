/* ============================================================
   Ciallo Reader 官网 — 交互脚本
   原生 JS，无依赖。所有可调参数集中在下方 CONFIG。
   ============================================================ */
'use strict';

/* ---------- 集中配置 ---------- */
const CONFIG = {
  curves: {
    countDesktop: 20,        // 桌面端曲线条数
    countMobile: 13,         // 手机端曲线条数
    pointsMobile: 7,         // 手机端控制点数（省性能）
    gapDesktop: 64,          // 桌面端控制点间距（px）
    bandHeight: 0.42,        // 曲线带占屏高度比例
    bandCenter: 0.6,         // 曲线带中心垂直位置
    sigmaXMin: 150, sigmaXMax: 300, // 鼠标影响半径 σx 上下限
    sigmaXRatio: 0.14,       // σx = 窗口宽 × 此值
    sigmaYRatio: 0.2,        // σy = 窗口高 × 此值
    attract: 0.78,           // 吸附强度
    ease: 0.12,              // 鼠标缓动系数（液体拖拽感）
    idleDelay: 2500,         // 无输入多久后进入自动游走（ms）
    flowDur: 22              // 渐变横向流动周期（s）
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
   2. 流动曲线背景：随鼠标局部变形（吸附 + 涟漪）
   ============================================================ */
(function curves(){
  const svg = $('#curves');
  if (!svg) return;
  const C = CONFIG.curves;
  const NS = 'http://www.w3.org/2000/svg';
  let W = 0, H = 0, lines = [], paths = [], nPts = 0, gap = 0;
  let grad, anim1, anim2;
  let mx = -9999, my = -9999, tx = -9999, ty = -9999, vel = 0, lastMove = 0;
  let running = true, rafId = 0;

  function build(){
    W = window.innerWidth; H = window.innerHeight;
    svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
    const mobile = W < 700;
    const count = mobile ? C.countMobile : C.countDesktop;
    nPts = mobile ? C.pointsMobile : Math.ceil(W / C.gapDesktop) + 2;
    gap = W / (nPts - 1);
    svg.innerHTML =
      `<defs><linearGradient id="curveGrad" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="${W}" y2="0">
        <stop offset="0" stop-color="#7c6bff"/><stop offset=".5" stop-color="#35c2ff"/><stop offset="1" stop-color="#ff7ab8"/>
        <animate id="cgA1" attributeName="x1" values="${-W};${W}" dur="${C.flowDur}s" repeatCount="indefinite"/>
        <animate id="cgA2" attributeName="x2" values="0;${W * 2}" dur="${C.flowDur}s" repeatCount="indefinite"/>
      </linearGradient></defs><g id="curveGroup" fill="none" stroke="url(#curveGrad)" stroke-linecap="round"></g>`;
    const g = $('#curveGroup', svg);
    const mid = (count - 1) / 2;
    lines = []; paths = [];
    for (let i = 0; i < count; i++){
      const t = 1 - Math.abs(i - mid) / (mid || 1);   // 中间 1 → 两端 0
      const p = document.createElementNS(NS, 'path');
      p.setAttribute('stroke-width', (0.8 + 1.3 * t).toFixed(2));  // 0.8 → 2.1
      p.setAttribute('opacity', (0.22 + 0.65 * t).toFixed(2));     // 0.22 → 0.87
      g.appendChild(p);
      paths.push(p);
      lines.push({ i, off: (i / (count - 1) - 0.5) * H * C.bandHeight });
    }
  }

  // Q 二次贝塞尔中点平滑：控制点=当前点，终点=与下一点的中点
  function pathFrom(ys){
    let d = `M 0 ${ys[0].toFixed(1)}`;
    for (let k = 1; k < ys.length - 1; k++){
      const cx = k * gap;
      const ex = (cx + (k + 1) * gap) / 2;
      const ey = (ys[k] + ys[k + 1]) / 2;
      d += ` Q ${cx.toFixed(1)} ${ys[k].toFixed(1)} ${ex.toFixed(1)} ${ey.toFixed(1)}`;
    }
    d += ` L ${((ys.length - 1) * gap).toFixed(1)} ${ys[ys.length - 1].toFixed(1)}`;
    return d;
  }

  function frame(now){
    if (!running) return;
    const t = now;
    // 无输入 2.5s 后：虚拟光点沿李萨如轨迹自动游走
    if (now - lastMove > C.idleDelay){
      tx = W / 2 + W * 0.32 * Math.sin(t * 0.00043);
      ty = H * 0.6 + H * 0.18 * Math.sin(t * 0.00061 + 1.3);
    }
    const pmx = mx, pmy = my;
    mx += (tx - mx) * C.ease;
    my += (ty - my) * C.ease;
    const inst = Math.hypot(mx - pmx, my - pmy);
    vel = vel * 0.85 + inst * 0.15;

    const sx = clamp(W * C.sigmaXRatio, C.sigmaXMin, C.sigmaXMax);
    const sy = H * C.sigmaYRatio;
    const cy = H * C.bandCenter;
    const scrollDrift = window.scrollY;

    for (let li = 0; li < lines.length; li++){
      const L = lines[li];
      const ys = new Array(nPts);
      for (let k = 0; k < nPts; k++){
        const x = k * gap;
        // 基础形状：多个慢速正弦叠加
        let y = cy + L.off
          + Math.sin(x * 0.004 + t * 0.00028 + L.i * 0.7) * 14
          + Math.sin(x * 0.0016 - t * 0.00019 + L.i * 1.3) * 22
          + Math.sin(scrollDrift * 0.0022 + L.i * 0.35) * 26;  // 滚动联动
        // 鼠标局部变形：高斯权重
        const dx = x - mx, dy = y - my;
        const w = Math.exp(-(dx * dx) / (2 * sx * sx)) * Math.exp(-(dy * dy) / (2 * sy * sy));
        y += (my - y) * C.attract * w;                                  // 吸附
        y += Math.sin(dx * 0.022 - t * 0.005) * (10 + vel * 1.4) * w;   // 涟漪
        ys[k] = y;
      }
      paths[li].setAttribute('d', pathFrom(ys));
    }
    rafId = requestAnimationFrame(frame);
  }

  window.addEventListener('pointermove', (e) => {
    tx = e.clientX; ty = e.clientY; lastMove = performance.now();
    if (mx < -999){ mx = tx; my = ty; }
  }, { passive: true });

  document.addEventListener('visibilitychange', () => {
    running = !document.hidden;
    if (running && !RM){ cancelAnimationFrame(rafId); rafId = requestAnimationFrame(frame); }
  });

  let resizeTimer = 0;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => {
      build();
      if (RM) drawStatic();
    }, 160);
  });

  function drawStatic(){ // 减少动态：只画一帧静态曲线
    const cy = H * C.bandCenter;
    for (let li = 0; li < lines.length; li++){
      const L = lines[li];
      const ys = new Array(nPts);
      for (let k = 0; k < nPts; k++){
        const x = k * gap;
        ys[k] = cy + L.off + Math.sin(x * 0.004 + L.i * 0.7) * 14 + Math.sin(x * 0.0016 + L.i * 1.3) * 22;
      }
      paths[li].setAttribute('d', pathFrom(ys));
    }
  }

  build();
  if (RM) drawStatic();
  else rafId = requestAnimationFrame(frame);
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
    menuBtn.addEventListener('click', () => {
      const open = panel.classList.toggle('open');
      menuBtn.setAttribute('aria-expanded', open);
      $$('a', panel).forEach((a, i) => { a.style.transitionDelay = open ? (60 + i * 45) + 'ms' : '0ms'; });
    });
    panel.addEventListener('click', (e) => {
      if (e.target.closest('a')) panel.classList.remove('open');
    });
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
