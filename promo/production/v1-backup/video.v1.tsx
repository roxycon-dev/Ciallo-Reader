import React from 'react';
import {
  AbsoluteFill,
  Audio,
  Img,
  OffthreadVideo,
  Sequence,
  interpolate,
  spring,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from 'remotion';

export const TOTAL_FRAMES = 72 * 30;

const C = {
  night: '#090b0f',
  ink: '#11171b',
  green: '#8bcbb4',
  cream: '#eef0ed',
  muted: '#9aa5a6',
};

const Screen: React.FC<{src: string; start?: number; blur?: number; className?: string; style?: React.CSSProperties}> = ({src, start = 0, blur = 0, style}) => (
  <OffthreadVideo
    src={staticFile(`media/${src}`)}
    startFrom={start}
    muted
    style={{width: '100%', height: '100%', objectFit: 'cover', display: 'block', filter: blur ? `blur(${blur}px)` : undefined, ...style}}
  />
);

const Mood: React.FC<{light?: string}> = ({light = 'mint'}) => (
  <AbsoluteFill style={{
    background: `radial-gradient(ellipse 42% 90% at 73% 52%, ${light === 'amber' ? 'rgba(139,103,66,.22)' : 'rgba(94,149,133,.17)'}, transparent 74%), linear-gradient(114deg, #080a0d 0%, #101519 48%, #080a0c 100%)`,
  }}>
    <AbsoluteFill style={{background: 'linear-gradient(180deg, rgba(255,255,255,.025), transparent 28%, rgba(0,0,0,.24))'}} />
    <div style={{position: 'absolute', inset: '0 0 auto', height: 1, background: 'linear-gradient(90deg, transparent, rgba(180,218,205,.38), transparent)'}} />
  </AbsoluteFill>
);

const Badge: React.FC = () => null;

const Caption: React.FC<{cn: string; en: string; eyebrow?: string; align?: 'left' | 'center'}> = ({cn, en, eyebrow, align = 'left'}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const y = interpolate(spring({frame: frame - 3, fps, config: {damping: 22, stiffness: 90}},), [0, 1], [24, 0]);
  const opacity = interpolate(frame, [0, 12], [0, 1], {extrapolateRight: 'clamp'});
  return (
    <div style={{position: 'absolute', zIndex: 20, left: align === 'left' ? 145 : 0, right: align === 'left' ? 'auto' : 0, width: align === 'left' ? 690 : '100%', top: '50%', transform: `translateY(-50%) translateY(${y}px)`, opacity, textAlign: align, color: C.cream}}>
      {eyebrow && <div style={{font: '600 15px/1.4 Arial, sans-serif', letterSpacing: 4, color: C.green, marginBottom: 25}}>{eyebrow}</div>}
      <div style={{fontFamily: 'Georgia, "Noto Serif SC", serif', fontSize: 66, lineHeight: 1.3, letterSpacing: 3, fontWeight: 500, textShadow: '0 4px 30px rgba(0,0,0,.34)'}}>{cn}</div>
      <div style={{font: '400 22px/1.5 Arial, sans-serif', letterSpacing: 2, color: '#aeb8b5', marginTop: 17}}>{en}</div>
      <div style={{height: 1, width: 82, background: 'linear-gradient(90deg, #91cbb5, transparent)', marginTop: 30}} />
    </div>
  );
};

const Phone: React.FC<{children: React.ReactNode; local: number; width?: number; height?: number; left?: number; top?: number; turn?: number; scale?: number; blur?: number}> = ({children, local, width = 516, height = 1072, left = 1256, top = 3, turn = 0, scale = 1, blur = 0}) => {
  const {fps} = useVideoConfig();
  const entry = spring({frame: local, fps, config: {damping: 25, stiffness: 76}});
  const drift = Math.sin(local / 34) * 3;
  const rise = interpolate(entry, [0, 1], [60, 0]);
  const rotation = interpolate(entry, [0, 1], [turn + 3, turn]);
  return (
    <div style={{position: 'absolute', zIndex: 8, left, top: top + rise + drift, width, height, borderRadius: 57, padding: 17, boxSizing: 'border-box', background: 'linear-gradient(145deg, #50595d, #111518 20%, #050607 83%, #45514e)', border: '1px solid rgba(225,239,233,.34)', boxShadow: '0 42px 110px rgba(0,0,0,.65), 0 0 80px rgba(114,184,160,.11)', transform: `perspective(1700px) rotateY(${rotation}deg) rotateZ(-.4deg) scale(${scale})`, overflow: 'hidden'}}>
      <div style={{position: 'relative', height: '100%', width: '100%', overflow: 'hidden', borderRadius: 42, background: '#08090a', filter: blur ? `blur(${blur}px)` : undefined}}>{children}</div>
      <div style={{position: 'absolute', zIndex: 3, top: 6, left: '50%', transform: 'translateX(-50%)', width: 84, height: 6, borderRadius: 5, background: 'rgba(0,0,0,.8)', border: '1px solid rgba(225,239,233,.12)'}} />
    </div>
  );
};

const StandardShot: React.FC<{src: string; start: number; cn: string; en: string; eyebrow?: string; light?: string; blur?: number; bottomBlur?: boolean; noCopy?: boolean; turn?: number}> = ({src, start, cn, en, eyebrow, light, blur = 0, bottomBlur = false, noCopy = false, turn = 0}) => {
  const frame = useCurrentFrame();
  const slowPush = 1 + Math.min(frame, 240) * .00012;
  return <AbsoluteFill>
    <Mood light={light} />
    <div style={{position: 'absolute', zIndex: 1, left: 1050, top: -120, width: 950, height: 1350, opacity: .14, background: 'radial-gradient(ellipse, rgba(172,218,201,.44), transparent 65%)', filter: 'blur(36px)'}} />
    <Phone local={frame} turn={turn} scale={slowPush}>
      <Screen src={src} start={start} blur={blur} />
      {bottomBlur && <div style={{position: 'absolute', zIndex: 4, left: 0, right: 0, bottom: 0, height: '29%', backdropFilter: 'blur(19px)', background: 'linear-gradient(180deg, rgba(16,21,22,.18), rgba(9,13,15,.45))', borderTop: '1px solid rgba(220,236,229,.18)'}} />}
      {src === 'shelf.mp4' && <div style={{position: 'absolute', zIndex: 4, left: '3%', right: '3%', top: '57%', height: '8%', borderRadius: 8, backdropFilter: 'blur(16px)', background: 'rgba(7,10,12,.16)'}} />}
    </Phone>
    {!noCopy && <Caption cn={cn} en={en} eyebrow={eyebrow} />}
    <Badge />
  </AbsoluteFill>;
};

const HomeShot: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const entry = spring({frame, fps, config: {damping: 22, stiffness: 74}});
  const reveal = interpolate(frame, [0, 30, 66], [.12, .76, 1], {extrapolateRight: 'clamp'});
  const panelScale = interpolate(frame, [0, 96, 150], [.955, 1, 1.012], {extrapolateRight: 'clamp'});
  const panelX = interpolate(entry, [0, 1], [86, 0]);
  const sweepX = interpolate(frame, [10, 91], [-740, 890], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const titleOpacity = interpolate(frame, [17, 39], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  return <AbsoluteFill>
    <AbsoluteFill style={{background: 'radial-gradient(ellipse 42% 85% at 73% 54%, rgba(92,148,131,.15), transparent 72%), #07090c'}} />
    <div style={{position: 'absolute', left: 1050, top: -95, width: 780, height: 1190, background: 'radial-gradient(ellipse, rgba(127,190,169,.18), transparent 72%)', filter: 'blur(30px)', opacity: reveal}} />
    <div style={{position: 'absolute', left: 1260 + panelX, top: 0, width: 520, height: 1080, borderRadius: 52, padding: 16, boxSizing: 'border-box', background: 'linear-gradient(145deg,#58615e,#171b1d 22%,#050607 82%,#65716a)', border: '1px solid rgba(220,239,230,.38)', boxShadow: '0 44px 100px rgba(0,0,0,.62),0 0 60px rgba(130,198,175,.16)', transform: `perspective(1500px) rotateY(${interpolate(entry,[0,1],[7,-2])}deg) scale(${panelScale})`, transformOrigin: 'center'}}>
      <div style={{position: 'relative', height: '100%', overflow: 'hidden', borderRadius: 39, background: '#090b0d'}}>
        <Screen src="home-hero.mp4" style={{filter: `brightness(${reveal}) contrast(1.03)`}} />
        <div style={{position: 'absolute', zIndex: 6, left: '3%', top: '57%', width: '23%', height: '6%', borderRadius: 5, backdropFilter: 'blur(16px)', background: 'rgba(7,10,12,.18)'}} />
        <div style={{position: 'absolute', zIndex: 5, top: 0, bottom: 0, left: 0, width: 380, transform: `translateX(${sweepX}px) skewX(-14deg)`, background: 'linear-gradient(90deg,transparent,rgba(230,255,244,.18),rgba(229,252,241,.04),transparent)', mixBlendMode: 'screen', pointerEvents: 'none'}} />
      </div>
    </div>
    <div style={{position: 'absolute', zIndex: 12, left: 145, top: '50%', width: 820, transform: 'translateY(-45%)', opacity: titleOpacity, color: C.cream}}>
      <div style={{font: '600 14px/1.4 Arial,sans-serif', letterSpacing: 4, color: C.green, marginBottom: 24}}>A QUIETER KIND OF READING</div>
      <div style={{font: '500 76px/1.25 Georgia,"Noto Serif SC",serif', letterSpacing: 5, textShadow: '0 6px 38px rgba(0,0,0,.5)'}}>Ciallo阅读</div>
      <div style={{font: '400 23px/1.4 Arial,sans-serif', letterSpacing: 4, color: '#aebbb5', marginTop: 14}}>CIALLO READER</div>
      <div style={{height: 1, width: 118, background: 'linear-gradient(90deg,#91cbb5,transparent)', marginTop: 31}} />
    </div>
    <Badge />
  </AbsoluteFill>;
};

const SearchShot: React.FC = () => {
  const frame = useCurrentFrame();
  const zoom = interpolate(frame, [0, 110], [1.02, 1.105], {extrapolateRight: 'clamp'});
  const objectY = interpolate(frame, [0, 24], [0, 8], {extrapolateRight: 'clamp'});
  return <AbsoluteFill>
    <Mood />
    <div style={{position: 'absolute', zIndex: 6, left: 150, top: 116, color: C.cream}}>
      <div style={{font: '600 14px/1.4 Arial, sans-serif', letterSpacing: 4, color: C.green, marginBottom: 18}}>DISCOVER</div>
      <div style={{font: '500 47px/1.25 Georgia, "Noto Serif SC", serif', letterSpacing: 2}}>找到你的下一本</div>
      <div style={{font: '400 19px/1.5 Arial, sans-serif', letterSpacing: 1.5, color: '#aeb8b5', marginTop: 8}}>Find your next read.</div>
    </div>
    <div style={{position: 'absolute', left: 100, top: 386, width: 1720, height: 430, overflow: 'hidden', borderRadius: 28, border: '1px solid rgba(227,240,234,.3)', boxShadow: '0 32px 80px rgba(0,0,0,.55)', transform: `scale(${zoom})`, transformOrigin: 'center'}}>
      <Screen src="search.mp4" start={75} style={{objectPosition: `center ${objectY}%`}} />
      <div style={{position: 'absolute', inset: 0, background: 'linear-gradient(90deg, rgba(7,10,12,.24), transparent 20%, transparent 80%, rgba(7,10,12,.28))'}} />
    </div>
    <div style={{position: 'absolute', left: 0, right: 0, bottom: 104, textAlign: 'center', zIndex: 5, color: 'rgba(210,224,219,.56)', font: '500 14px/1 Arial, sans-serif', letterSpacing: 3}}>REAL SEARCH FIELD · CROPPED FROM USER FOOTAGE</div>
    <Badge />
  </AbsoluteFill>;
};

const NovelTurnShot: React.FC = () => {
  const f = useCurrentFrame();
  const blur = interpolate(f, [0, 18, 150], [12, 10, 14], {extrapolateRight: 'clamp'});
  return <StandardShot src="novel-turn.mp4" start={0} cn="翻页，自有节奏" en="Let the page turn in its own time." eyebrow="A MOMENT BETWEEN CHAPTERS" light="amber" blur={blur} turn={1} />;
};

const RoxyShot: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const zoom = interpolate(spring({frame, fps, config: {damping: 22, stiffness: 60}}), [0, 1], [.94, 1]);
  const showVideo = frame >= 72 && frame < 117;
  const showPopup = frame >= 96 && frame < 114;
  const mascotEntry = spring({frame, fps, config: {damping: 19, stiffness: 78}});
  const mascotScale = interpolate(mascotEntry, [0, 1], [.9, 1]);
  const mascotY = interpolate(mascotEntry, [0, 1], [64, 0]) + Math.sin(frame / 19) * 3;
  return <AbsoluteFill>
    <Mood light="amber" />
    <div style={{position: 'absolute', left: 1275, top: 22, width: 500, height: 1036, borderRadius: 42, overflow: 'hidden', border: '1px solid rgba(220,239,230,.28)', boxShadow: '0 40px 90px rgba(0,0,0,.62)', transform: `scale(${zoom})`, background: '#090b0d'}}>
      <Img src={staticFile('media/roxy-onboarding.png')} style={{position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover'}} />
      {showVideo && <Sequence from={72} durationInFrames={45}><Screen src="roxy.mp4" start={36} blur={12} /></Sequence>}
      {showPopup && <Sequence from={96} durationInFrames={18}>
        <div style={{position: 'absolute', left: '26%', top: '38%', width: '48%', height: '25%', overflow: 'hidden', borderRadius: 22, border: '1px solid rgba(220,235,228,.4)', boxShadow: '0 10px 40px rgba(0,0,0,.38)'}}>
          <OffthreadVideo src={staticFile('media/roxy.mp4')} startFrom={60} muted style={{position: 'absolute', width: '208.33%', height: '400%', left: '-54.16%', top: '-168%', objectFit: 'cover', filter: 'saturate(1.08)'}} />
        </div>
      </Sequence>}
    </div>
    <div style={{position: 'absolute', left: 670, top: 195, width: 545, height: 740, borderRadius: 290, background: 'radial-gradient(ellipse, rgba(222,177,125,.24), rgba(107,142,131,.12) 42%, transparent 72%)', filter: 'blur(24px)'}} />
    <Img src={staticFile('media/mascot_reading.webp')} style={{position: 'absolute', zIndex: 10, left: 704, top: 130 + mascotY, width: 500, height: 830, objectFit: 'contain', transform: `scale(${mascotScale})`, filter: 'drop-shadow(0 26px 44px rgba(0,0,0,.56))'}} />
    <div style={{position: 'absolute', zIndex: 15, left: 145, top: '50%', width: 500, transform: 'translateY(-50%)', color: C.cream}}>
      <div style={{font: '600 14px/1.4 Arial,sans-serif', letterSpacing: 4, color: C.green, marginBottom: 24}}>A READING COMPANION</div>
      <div style={{font: '500 54px/1.35 Georgia,"Noto Serif SC",serif', letterSpacing: 2}}>让陪伴，也留在书页旁</div>
      <div style={{font: '400 19px/1.5 Arial,sans-serif', letterSpacing: 1.3, color: '#aeb8b5', marginTop: 15}}>A small moment beside every chapter.</div>
      <div style={{height: 1, width: 76, background: 'linear-gradient(90deg,#91cbb5,transparent)', marginTop: 25}} />
    </div>
    <Badge />
  </AbsoluteFill>;
};

const GroupShot: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const cards = [
    {src: 'search-card.png', label: 'Discover'},
    {src: 'shelf-card.png', label: 'Your shelf'},
    {src: 'comic-card.png', label: 'Comics'},
    {src: 'glass-card.png', label: 'Personalize'},
  ];
  return <AbsoluteFill>
    <Mood />
    <div style={{position: 'absolute', top: 180, left: 0, right: 0, textAlign: 'center', color: C.cream}}>
      <div style={{font: '600 14px/1 Arial, sans-serif', letterSpacing: 4, color: C.green, marginBottom: 18}}>ONE READER · MANY WAYS TO READ</div>
      <div style={{font: '500 48px/1.25 Georgia, "Noto Serif SC", serif', letterSpacing: 2}}>为每一种阅读习惯，留一席之地</div>
      <div style={{font: '400 20px/1.4 Arial, sans-serif', letterSpacing: 1.5, color: '#aeb8b5', marginTop: 12}}>A place for every kind of reading.</div>
    </div>
    {cards.map((card, index) => {
      const local = Math.max(0, frame - index * 5);
      const enter = spring({frame: local, fps, config: {damping: 17, stiffness: 95}});
      const x = 245 + index * 365;
      const turn = [-5, -2, 2, 5][index];
      return <div key={card.src} style={{position: 'absolute', left: x, top: 362 + (1 - enter) * 76, width: 285, height: 626, borderRadius: 26, overflow: 'hidden', border: '1px solid rgba(232,241,236,.3)', background: '#090c0e', boxShadow: '0 28px 70px rgba(0,0,0,.5), 0 0 45px rgba(119,184,163,.1)', transform: `perspective(1000px) rotateY(${turn}deg) rotateZ(${turn * -.08}deg)`, opacity: interpolate(enter, [0, 1], [0, 1])}}>
        <Img src={staticFile(`media/${card.src}`)} style={{width: '100%', height: '100%', objectFit: 'cover'}} />
        {card.src === 'shelf-card.png' && <div style={{position: 'absolute', left: '3%', top: '57%', width: '38%', height: '7%', borderRadius: 5, backdropFilter: 'blur(16px)', background: 'rgba(7,10,12,.18)'}} />}
        <div style={{position: 'absolute', left: 0, right: 0, bottom: 0, height: 54, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(6,9,10,.72)', color: '#dfe7e3', font: '500 15px Arial, sans-serif', letterSpacing: 2}}>{card.label}</div>
      </div>;
    })}
    <Badge />
  </AbsoluteFill>;
};

const Outro: React.FC = () => {
  const frame = useCurrentFrame();
  const fade = interpolate(frame, [0, 20], [0, 1], {extrapolateRight: 'clamp'});
  return <AbsoluteFill style={{background: 'radial-gradient(ellipse 48% 88% at 50% 45%, rgba(95,146,133,.14), transparent 72%), #080a0d', color: C.cream, alignItems: 'center', justifyContent: 'center', opacity: fade}}>
    <div style={{font: '400 18px/1.2 Arial, sans-serif', letterSpacing: 7, color: C.green, marginBottom: 24}}>A QUIETER KIND OF READING</div>
    <div style={{font: '500 98px/1.15 Georgia, "Noto Serif SC", serif', letterSpacing: 6}}>Ciallo阅读</div>
    <div style={{font: '300 30px/1.4 Arial, sans-serif', letterSpacing: 5, color: '#b2bdb9', marginTop: 9}}>CIALLO READER</div>
    <div style={{width: 80, height: 1, background: C.green, marginTop: 34, marginBottom: 30}} />
    <div style={{display: 'flex', gap: 42, textAlign: 'left'}}>
      <div style={{width: 770, minHeight: 172, padding: '24px 30px', boxSizing: 'border-box', border: '1px solid rgba(204,228,217,.38)', borderRadius: 20, background: 'linear-gradient(140deg,rgba(188,224,208,.11),rgba(255,255,255,.025))', boxShadow: '0 20px 55px rgba(0,0,0,.32)'}}>
        <div style={{font: '600 13px/1.4 Arial', letterSpacing: 3, color: C.green, marginBottom: 9}}>GET THE APP</div>
        <div style={{font: '500 42px/1.18 "Noto Sans SC",Arial,sans-serif', letterSpacing: 2, color: '#eef2ef'}}>下载 APK</div>
        <div style={{font: '400 20px/1.35 Arial,sans-serif', color: '#b5c1bc', marginTop: 5}}>GitHub Releases</div>
        <div style={{font: '400 14px/1.35 Arial,sans-serif', color: '#81908c', marginTop: 6}}>github.com/a1553846342-dotcom/EASYREADER/releases</div>
      </div>
      <div style={{width: 770, minHeight: 172, padding: '24px 30px', boxSizing: 'border-box', border: '1px solid rgba(204,228,217,.38)', borderRadius: 20, background: 'linear-gradient(140deg,rgba(188,224,208,.11),rgba(255,255,255,.025))', boxShadow: '0 20px 55px rgba(0,0,0,.32)'}}>
        <div style={{font: '600 13px/1.4 Arial', letterSpacing: 3, color: C.green, marginBottom: 9}}>OPEN SOURCE</div>
        <div style={{font: '500 42px/1.18 "Noto Sans SC",Arial,sans-serif', letterSpacing: 2, color: '#eef2ef'}}>项目主页</div>
        <div style={{font: '400 20px/1.35 Arial,sans-serif', color: '#b5c1bc', marginTop: 5}}>Project on GitHub</div>
        <div style={{font: '400 14px/1.35 Arial,sans-serif', color: '#81908c', marginTop: 6}}>github.com/a1553846342-dotcom/EASYREADER</div>
      </div>
    </div>
  </AbsoluteFill>;
};

export const CialloPromo: React.FC = () => <AbsoluteFill style={{backgroundColor: C.night, fontFamily: 'Arial, sans-serif'}}>
  <Audio src={staticFile('audio/ambient-piano.wav')} volume={0.34} />
  <Sequence from={0}><Audio src={staticFile('audio/shimmer-sparkle-sweep.mp3')} volume={0.13} /></Sequence>
  <Sequence from={150}><Audio src={staticFile('audio/ui-select-click.mp3')} volume={0.1} /></Sequence>
  <Sequence from={390}><Audio src={staticFile('audio/ui-success-soft.mp3')} volume={0.11} /></Sequence>
  <Sequence from={600}><Audio src={staticFile('audio/swoosh-quick.mp3')} volume={0.1} /></Sequence>
  <Sequence from={1050}><Audio src={staticFile('audio/glass-plate-slide.mp3')} volume={0.12} /></Sequence>
  <Sequence from={1230}><Audio src={staticFile('audio/paper-page-turn.mp3')} volume={0.16} /></Sequence>
  <Sequence from={1656}><Audio src={staticFile('audio/ui-success-soft.mp3')} volume={0.1} /></Sequence>
  <Sequence from={1920}><Audio src={staticFile('audio/transition-soft.mp3')} volume={0.08} /></Sequence>
    <Sequence from={0} durationInFrames={150}><HomeShot /></Sequence>
  <Sequence from={150} durationInFrames={120}><SearchShot /></Sequence>
  <Sequence from={270} durationInFrames={150}><StandardShot src="detail.mp4" start={0} cn="一眼看见，安心收下" en="From detail to download, in one flow." eyebrow="LIBRARY" turn={-1} /></Sequence>
  <Sequence from={420} durationInFrames={180}><StandardShot src="shelf.mp4" start={0} cn="书架，井然有序" en="Keep your library close." eyebrow="YOUR LIBRARY" turn={1} /></Sequence>
  <Sequence from={600} durationInFrames={240}><StandardShot src="comic-open.mp4" start={150} cn="从封面，走进故事" en="From cover to story." eyebrow="COMICS" turn={-1} /></Sequence>
  <Sequence from={840} durationInFrames={210}><StandardShot src="comic-read.mp4" start={45} cn="一格一格，读进故事里" en="Panel by panel, find your rhythm." eyebrow="READ COMICS" noCopy /></Sequence>
  <Sequence from={1050} durationInFrames={180}><StandardShot src="comic-glass.mp4" start={240} cn="顺手的设置，轻触即达" en="Reading controls, right where you need them." eyebrow="READING SETTINGS" noCopy turn={1} /></Sequence>
  <Sequence from={1230} durationInFrames={270}><NovelTurnShot /></Sequence>
  <Sequence from={1500} durationInFrames={60}><StandardShot src="glass-ui.mp4" start={510} cn="调成喜欢的样子" en="Make the page feel like yours." eyebrow="PERSONALIZE" noCopy turn={-1} /></Sequence>
  <Sequence from={1560} durationInFrames={150}><RoxyShot /></Sequence>
  <Sequence from={1710} durationInFrames={210}><GroupShot /></Sequence>
  <Sequence from={1920} durationInFrames={240}><Outro /></Sequence>
</AbsoluteFill>;
