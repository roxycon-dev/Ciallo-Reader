import React from 'react';
import {
  AbsoluteFill,
  Audio,
  Easing,
  Img,
  OffthreadVideo,
  Sequence,
  interpolate,
  spring,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from 'remotion';

export const TOTAL_FRAMES = 70 * 30;

const ink = '#080b0f';
const paper = '#f1f0e9';
const mint = '#9ddac4';

const Live: React.FC<{
  src: string;
  start?: number;
  rate?: number;
  style?: React.CSSProperties;
}> = ({src, start = 0, rate = 1, style}) => (
  <OffthreadVideo
    src={staticFile(`media/${src}`)}
    startFrom={start}
    playbackRate={rate}
    muted
    style={{width: '100%', height: '100%', objectFit: 'cover', display: 'block', ...style}}
  />
);

const Studio: React.FC<{children?: React.ReactNode; warm?: boolean}> = ({children, warm = false}) => (
  <AbsoluteFill style={{
    overflow: 'hidden',
    background: warm
      ? 'radial-gradient(ellipse at 50% 48%, #20221e 0%, #111410 45%, #080a0c 100%)'
      : 'radial-gradient(ellipse at 50% 42%, #171e1e 0%, #0d1114 51%, #07090c 100%)',
  }}>
    {children}
  </AbsoluteFill>
);

const Device: React.FC<{children: React.ReactNode; style: React.CSSProperties}> = ({children, style}) => (
  <div style={{
    position: 'absolute', boxSizing: 'border-box', padding: 14, borderRadius: 54,
    background: 'linear-gradient(145deg, #4c5657 0%, #13181a 18%, #050607 82%, #48504c 100%)',
    border: '1px solid rgba(220,239,231,.45)',
    boxShadow: '0 34px 92px rgba(0,0,0,.52), inset 0 0 0 1px rgba(255,255,255,.07)',
    overflow: 'hidden', ...style,
  }}>
    <div style={{position: 'relative', width: '100%', height: '100%', borderRadius: 40, overflow: 'hidden', background: '#090b0d'}}>{children}</div>
    <div style={{position: 'absolute', top: 7, left: '50%', transform: 'translateX(-50%)', width: 82, height: 5, borderRadius: 4, background: '#030405', border: '1px solid rgba(230,243,237,.12)'}} />
  </div>
);

const Poetry: React.FC<{cn: string; en: string; top?: number; left?: number; size?: number}> = ({cn, en, top = 130, left = 132, size = 66}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const rise = interpolate(spring({frame: frame - 8, fps, config: {damping: 23, stiffness: 72}}), [0, 1], [24, 0]);
  const opacity = interpolate(frame, [4, 24], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  return (
    <div style={{position: 'absolute', zIndex: 20, top, left, color: paper, opacity, transform: `translateY(${rise}px)`, pointerEvents: 'none'}}>
      <div style={{fontFamily: 'Georgia, "Noto Serif SC", serif', fontSize: size, lineHeight: 1.25, letterSpacing: 3, textShadow: '0 4px 24px rgba(0,0,0,.35)'}}>{cn}</div>
      <div style={{marginTop: 13, color: 'rgba(232,239,235,.72)', font: '400 19px/1.4 Arial,sans-serif', letterSpacing: 2}}>{en}</div>
    </div>
  );
};

const Opening: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const entry = spring({frame: frame - 4, fps, config: {damping: 24, stiffness: 56}});
  const scale = interpolate(entry, [0, 1], [1.22, 1]);
  const turn = interpolate(entry, [0, 1], [-11, -1.5]);
  const orbit = interpolate(frame, [0, 70, 175], [36, 0, -16], {extrapolateRight: 'clamp', easing: Easing.inOut(Easing.cubic)});
  return <Studio>
    <div style={{position: 'absolute', inset: 0, perspective: 1500}}>
      <Device style={{left: 690 + orbit, top: -42, width: 545, height: 1198, transform: `rotateY(${turn}deg) scale(${scale})`, transformOrigin: 'center center'}}>
        <Live src="shelf-hires.mp4" start={0} />
      </Device>
    </div>
    <Poetry cn="一触，故事苏醒" en="A touch. A story unfolds." top={400} left={130} size={68} />
  </Studio>;
};

const Journey: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const settle = spring({frame, fps, config: {damping: 20, stiffness: 82}});
  const searchY = interpolate(frame, [0, 48, 82], [92, 0, -8], {extrapolateRight: 'clamp'});
  const panelScale = interpolate(frame, [0, 45, 84, 150], [.94, 1.02, 1, 1.08], {extrapolateRight: 'clamp'});
  const deviceTurn = interpolate(settle, [0, 1], [5, -2]);
  return <Studio>
    <Sequence from={0} durationInFrames={84}>
      <div style={{position: 'absolute', left: 180, top: 332 + searchY, width: 1560, height: 274, overflow: 'hidden', borderRadius: 28, border: '1px solid rgba(213,237,226,.46)', boxShadow: '0 26px 80px rgba(0,0,0,.42)', transform: `perspective(1300px) rotateY(${deviceTurn}deg) scale(${panelScale})`}}>
        <Live src="search-field.mp4" />
      </div>
    </Sequence>
    <Sequence from={84} durationInFrames={96}>
      <DownloadMoment />
    </Sequence>
    <Sequence from={180} durationInFrames={60}>
      <ShelfMoment />
    </Sequence>
    <Sequence from={240} durationInFrames={150}>
      <OpenBookMoment />
    </Sequence>
  </Studio>;
};

const DownloadMoment: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const entrance = spring({frame: frame - 3, fps, config: {damping: 18, stiffness: 86}});
  const z = interpolate(entrance, [0, 1], [1.16, 1]);
  const y = interpolate(entrance, [0, 1], [58, 0]);
  return <>
    <div style={{position: 'absolute', inset: 0, display: 'grid', placeItems: 'center'}}>
      <div style={{width: 1120, height: 622, borderRadius: 32, overflow: 'hidden', border: '1px solid rgba(220,239,231,.48)', boxShadow: '0 32px 96px rgba(0,0,0,.48)', transform: `translateY(${y}px) scale(${z})`}}>
        <Live src="download-panel.mp4" />
      </div>
    </div>
    <div style={{position: 'absolute', left: 0, right: 0, bottom: 125, height: 1, background: 'linear-gradient(90deg, transparent, rgba(197,233,215,.42), transparent)'}} />
  </>;
};

const ShelfMoment: React.FC = () => {
  const frame = useCurrentFrame();
  const scale = interpolate(frame, [0, 59], [1.13, 1], {extrapolateRight: 'clamp', easing: Easing.out(Easing.cubic)});
  const x = interpolate(frame, [0, 59], [-26, 22], {extrapolateRight: 'clamp'});
  return <div style={{position: 'absolute', inset: 0, overflow: 'hidden'}}>
    <Live src="shelf-hires.mp4" start={0} style={{width: 704, height: 1552, position: 'absolute', left: 625 + x, top: -236, objectFit: 'cover', transform: `scale(${scale})`}} />
  </div>;
};

const OpenBookMoment: React.FC = () => {
  const frame = useCurrentFrame();
  const pull = interpolate(frame, [0, 60, 149], [1.16, 1, .89], {extrapolateRight: 'clamp', easing: Easing.inOut(Easing.cubic)});
  const yaw = interpolate(frame, [0, 72, 149], [6, -1, 2], {extrapolateRight: 'clamp'});
  return <div style={{position: 'absolute', inset: 0, perspective: 1400}}>
    <Device style={{left: 674, top: -127, width: 572, height: 1260, transform: `perspective(1400px) rotateY(${yaw}deg) scale(${pull})`}}>
      <Live src="comic-open-hires.mp4" start={60} />
    </Device>
    <div style={{position: 'absolute', right: 142, top: 134, width: 1, height: 786, background: 'linear-gradient(180deg, transparent, rgba(207,235,219,.45), transparent)'}} />
  </div>;
};

const ComicPhoneToPage: React.FC = () => {
  const frame = useCurrentFrame();
  const pull = interpolate(frame, [0, 46, 96, 120], [1, 1.05, 2.65, 3.45], {extrapolateRight: 'clamp', easing: Easing.inOut(Easing.cubic)});
  const yaw = interpolate(frame, [0, 44, 92, 120], [5, 2, -1.2, 0], {extrapolateRight: 'clamp'});
  const paperIn = interpolate(frame, [62, 120], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: Easing.inOut(Easing.cubic)});
  return <AbsoluteFill style={{overflow: 'hidden'}}>
    <div style={{position: 'absolute', inset: 0, background: 'radial-gradient(ellipse at 50% 42%, #171e1e 0%, #0d1114 51%, #07090c 100%)'}} />
    <div style={{position: 'absolute', inset: 0, opacity: paperIn, background: '#f1f0e9'}} />
    {frame < 120 && <div style={{position: 'absolute', inset: 0, perspective: 1800}}>
      <Device style={{left: 686, top: -165, width: 548, height: 1208, transform: `perspective(1800px) rotateY(${yaw}deg) scale(${pull})`, boxShadow: '0 30px 92px rgba(0,0,0,.48)'}}>
        <Live src="comic-page-early.mp4" rate={.9} />
      </Device>
    </div>}
    {frame < 65 && <div style={{position: 'absolute', left: 144, bottom: 150, color: paper, pointerEvents: 'none'}}>
      <div style={{font: '500 62px/1.2 Georgia, "Noto Serif SC", serif', letterSpacing: 4}}>入画</div>
      <div style={{marginTop: 13, color: 'rgba(232,239,235,.72)', font: '400 18px/1.4 Arial,sans-serif', letterSpacing: 3}}>STEP INTO THE FRAME</div>
    </div>}
  </AbsoluteFill>;
};

const MangaPaper: React.FC<{src: string; rate?: number; reverse?: boolean; duration: number}> = ({src, rate = 1, reverse = false, duration}) => {
  const frame = useCurrentFrame();
  const progress = interpolate(frame, [0, duration - 1], [0, 1], {extrapolateRight: 'clamp', easing: Easing.inOut(Easing.cubic)});
  const y = reverse ? interpolate(progress, [0, 1], [70, 28]) : interpolate(progress, [0, 1], [27, 70]);
  const scale = interpolate(progress, [0, 1], [1.02, 1.09]);
  const x = interpolate(progress, [0, 1], [18, -18]);
  return <AbsoluteFill style={{background: '#f1f0e9', overflow: 'hidden'}}>
    <Live src={src} rate={rate} style={{objectPosition: `50% ${y}%`, transform: `translateX(${x}px) scale(${scale})`, transformOrigin: 'center'}} />
  </AbsoluteFill>;
};

const GlassReturn: React.FC = () => {
  const frame = useCurrentFrame();
  const fade = interpolate(frame, [0, 45], [0, 1], {extrapolateRight: 'clamp'});
  const scale = interpolate(frame, [0, 50, 197], [1.22, 1.03, .99], {extrapolateRight: 'clamp'});
  const yaw = interpolate(frame, [0, 95, 197], [-4, 2, -1], {extrapolateRight: 'clamp'});
  return <AbsoluteFill style={{overflow: 'hidden', background: '#f1f0e9'}}>
    <div style={{position: 'absolute', inset: 0, background: 'radial-gradient(ellipse at 52% 43%, #171e1e 0%, #0d1114 51%, #07090c 100%)', opacity: fade}} />
    <Device style={{left: 686, top: -165, width: 548, height: 1208, transform: `perspective(1800px) rotateY(${yaw}deg) scale(${scale})`, boxShadow: '0 30px 92px rgba(0,0,0,.45)'}}>
      <Live src="comic-glass-hires.mp4" start={120} />
    </Device>
  </AbsoluteFill>;
};

const ComicReading: React.FC = () => <AbsoluteFill>
  <Sequence from={0} durationInFrames={120}><ComicPhoneToPage /></Sequence>
  <Sequence from={120} durationInFrames={54}><MangaPaper src="comic-page-early.mp4" rate={1} duration={54} /></Sequence>
  <Sequence from={174} durationInFrames={84}><MangaPaper src="comic-page-later.mp4" rate={1} reverse duration={84} /></Sequence>
  <Sequence from={258} durationInFrames={198}><GlassReturn /></Sequence>
</AbsoluteFill>;

const ComicPage: React.FC<{scale?: number; x?: number}> = ({scale = 1, x = 0}) => (
  <Img src={staticFile('media/comic-climax-hires.png')} style={{position: 'absolute', inset: 0, width: 1920, height: 1080, objectFit: 'cover', transform: `scale(${scale}) translateX(${x}px)`, transformOrigin: 'center'}} />
);

const ComicClimax: React.FC = () => {
  const frame = useCurrentFrame();
  const inOut = Easing.out(Easing.cubic);
  if (frame < 15) {
    const push = interpolate(frame, [0, 15], [1, 1.05], {extrapolateRight: 'clamp'});
    return <AbsoluteFill style={{background: '#080b0f'}}><ComicPage scale={push} /></AbsoluteFill>;
  }
  if (frame >= 116) return <AbsoluteFill style={{background: '#080b0f'}}><ComicPage scale={2.25} x={28} /></AbsoluteFill>;
  const progress = interpolate(frame, [98, 116], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: inOut});
  const edges = [
    [0, 0, 39, 0, 27, 100, 0, 100],
    [40, 0, 73, 0, 61, 100, 28, 100],
    [74 - progress * 74, 0, 100, 0, 100, 100, 62 - progress * 62, 100],
  ];
  const scales = [1.13, 1.75, 2.25 - progress * .08];
  const starts = [15, 17, 19];
  return <AbsoluteFill style={{background: '#ecece7'}}>
    {edges.map((e, i) => {
      if (frame < starts[i]) return null;
      const p = interpolate(frame, [starts[i], starts[i] + 3], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: inOut});
      const pop = 1.06 - p * .06;
      const clip = `polygon(${e[0]}% ${e[1]}%, ${e[2]}% ${e[3]}%, ${e[4]}% ${e[5]}%, ${e[6]}% ${e[7]}%)`;
      return <div key={i} style={{position: 'absolute', inset: 0, clipPath: clip, transform: `scale(${pop})`, transformOrigin: `${[260, 960, 1640][i]}px 540px`}}>
        <ComicPage scale={scales[i]} x={[-105, 0, 120][i]} />
      </div>;
    })}
    <svg width={1920} height={1080} style={{position: 'absolute', inset: 0, pointerEvents: 'none'}}>
      <line x1={748} y1={-10} x2={518} y2={1090} stroke="#202423" strokeWidth={18} />
      <line x1={748} y1={-10} x2={518} y2={1090} stroke="#f5f3ed" strokeWidth={11} />
      <line x1={1410 - progress * 1460} y1={-10} x2={1180 - progress * 1460} y2={1090} stroke="#202423" strokeWidth={18} />
      <line x1={1410 - progress * 1460} y1={-10} x2={1180 - progress * 1460} y2={1090} stroke="#f5f3ed" strokeWidth={11} />
    </svg>
  </AbsoluteFill>;
};

const ComicSettings: React.FC = () => {
  const frame = useCurrentFrame();
  const scale = interpolate(frame, [0, 62, 128, 194], [1.06, 1, 1.08, .99], {extrapolateRight: 'clamp'});
  const x = interpolate(frame, [0, 86, 194], [28, -14, 8], {extrapolateRight: 'clamp'});
  const yaw = interpolate(frame, [0, 96, 194], [3, -2, 1], {extrapolateRight: 'clamp'});
  return <Studio>
    <div style={{position: 'absolute', inset: 0, perspective: 1600}}>
      <Device style={{left: 675 + x, top: -145, width: 570, height: 1260, transform: `perspective(1600px) rotateY(${yaw}deg) scale(${scale})`}}>
        <Live src="comic-glass-hires.mp4" start={318} />
      </Device>
    </div>
  </Studio>;
};

const NovelControls: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const entrance = spring({frame: frame - 4, fps, config: {damping: 20, stiffness: 74}});
  const scale = interpolate(entrance, [0, 1], [.94, 1]) + Math.sin(frame / 42) * .008;
  const yaw = interpolate(frame, [0, 90, 179], [3, -2, 1], {extrapolateRight: 'clamp'});
  return <Studio warm>
    <div style={{position: 'absolute', inset: 0, perspective: 1400}}>
      <div style={{position: 'absolute', left: 382, top: 288, width: 1156, height: 870, overflow: 'hidden', borderRadius: 34, border: '1px solid rgba(230,232,217,.52)', boxShadow: '0 34px 85px rgba(0,0,0,.42)', transform: `perspective(1400px) rotateY(${yaw}deg) scale(${scale})`}}>
        <Live src="novel-panel.mp4" />
      </div>
    </div>
    <div style={{position: 'absolute', right: 144, bottom: 116, color: 'rgba(238,240,232,.72)', font: '400 17px/1.4 Arial,sans-serif', letterSpacing: 3}}>A PAGE, MADE YOURS</div>
  </Studio>;
};

const GlassControls: React.FC = () => {
  const frame = useCurrentFrame();
  const scale = interpolate(frame, [0, 88, 179], [1.07, 1, 1.04], {extrapolateRight: 'clamp'});
  const yaw = interpolate(frame, [0, 90, 179], [5, -2, 1], {extrapolateRight: 'clamp'});
  return <Studio>
    <div style={{position: 'absolute', left: 520, top: -202, width: 880, height: 1940, transform: `perspective(1650px) rotateY(${yaw}deg) scale(${scale})`, transformOrigin: 'center'}}>
      <Live src="glass-ui-hires.mp4" start={480} />
    </div>
    <div style={{position: 'absolute', right: 140, top: 90, width: 1, height: 900, background: 'linear-gradient(180deg, transparent, rgba(180,222,203,.35), transparent)'}} />
  </Studio>;
};

const Roxy: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const emergence = spring({frame: frame - 12, fps, config: {damping: 20, stiffness: 54}});
  const scale = interpolate(emergence, [0, 1], [.38, 1]) * (1 + Math.sin(frame / 28) * .009);
  const x = interpolate(emergence, [0, 1], [1040, 632]) + Math.sin(frame / 31) * 8;
  const y = interpolate(emergence, [0, 1], [442, 164]) + Math.sin(frame / 20) * 4;
  const frameX = interpolate(frame, [0, 66, 179], [990, 1090, 1130], {extrapolateRight: 'clamp'});
  const frameScale = interpolate(frame, [0, 66, 179], [1.08, .92, .88], {extrapolateRight: 'clamp'});
  const tilt = interpolate(emergence, [0, 1], [2, -1.5]);
  return <Studio warm>
    <div style={{position: 'absolute', left: frameX, top: -265, width: 660, height: 1452, borderRadius: 46, overflow: 'hidden', border: '1px solid rgba(229,237,229,.33)', boxShadow: '0 30px 84px rgba(0,0,0,.42)', transform: `perspective(1200px) rotateY(-5deg) scale(${frameScale})`, transformOrigin: 'center'}}>
      <Img src={staticFile('media/roxy-onboarding.png')} style={{width: '100%', height: '100%', objectFit: 'cover'}} />
    </div>
    <Img src={staticFile('media/mascot_reading.webp')} style={{position: 'absolute', left: x, top: y, width: 672, height: 806, objectFit: 'contain', transform: `perspective(1200px) rotateY(${tilt}deg) scale(${scale})`, transformOrigin: 'center bottom', filter: 'drop-shadow(0 24px 35px rgba(0,0,0,.26))'}} />
    <Poetry cn="陪伴，留在这一页" en="Stay a little longer." top={420} left={116} size={56} />
  </Studio>;
};

const Outro: React.FC = () => {
  const frame = useCurrentFrame();
  const fade = interpolate(frame, [0, 22], [0, 1], {extrapolateRight: 'clamp'});
  const buttonIn = spring({frame: frame - 20, fps: 30, config: {damping: 22, stiffness: 80}});
  const buttonY = interpolate(buttonIn, [0, 1], [26, 0]);
  return <AbsoluteFill style={{background: 'radial-gradient(ellipse at 50% 36%, #17211e 0%, #0a0d10 58%, #07090c 100%)', color: paper, alignItems: 'center', justifyContent: 'center', opacity: fade}}>
    <div style={{font: '500 96px/1.14 Georgia, "Noto Serif SC", serif', letterSpacing: 6}}>Ciallo阅读</div>
    <div style={{marginTop: 8, color: 'rgba(233,239,235,.65)', font: '300 26px/1.4 Arial,sans-serif', letterSpacing: 6}}>CIALLO READER</div>
    <div style={{width: 88, height: 1, background: mint, marginTop: 30, marginBottom: 28}} />
    <div style={{display: 'flex', gap: 34, transform: `translateY(${buttonY}px)`}}>
      <div style={{width: 765, height: 196, padding: '22px 30px', boxSizing: 'border-box', border: '1px solid rgba(204,228,217,.42)', borderRadius: 22, background: 'linear-gradient(140deg,rgba(188,224,208,.12),rgba(255,255,255,.025))'}}>
        <div style={{font: '600 14px/1.3 Arial,sans-serif', letterSpacing: 3, color: mint}}>GET THE APP</div>
        <div style={{font: '500 48px/1.15 "Noto Sans SC",Arial,sans-serif', letterSpacing: 2, marginTop: 10}}>下载 APK</div>
        <div style={{font: '400 22px/1.35 Arial,sans-serif', color: 'rgba(233,239,235,.7)', marginTop: 5}}>github.com/a1553846342-dotcom/EASYREADER/releases</div>
      </div>
      <div style={{width: 765, height: 196, padding: '22px 30px', boxSizing: 'border-box', border: '1px solid rgba(204,228,217,.42)', borderRadius: 22, background: 'linear-gradient(140deg,rgba(188,224,208,.12),rgba(255,255,255,.025))'}}>
        <div style={{font: '600 14px/1.3 Arial,sans-serif', letterSpacing: 3, color: mint}}>OPEN SOURCE</div>
        <div style={{font: '500 48px/1.15 "Noto Sans SC",Arial,sans-serif', letterSpacing: 2, marginTop: 10}}>项目主页</div>
        <div style={{font: '400 22px/1.35 Arial,sans-serif', color: 'rgba(233,239,235,.7)', marginTop: 5}}>github.com/a1553846342-dotcom/EASYREADER</div>
      </div>
    </div>
  </AbsoluteFill>;
};

export const CialloPromo: React.FC = () => <AbsoluteFill style={{background: ink, fontFamily: 'Arial,sans-serif'}}>
  <Audio src={staticFile('audio/ambient-piano.wav')} volume={0.3} />
  <Sequence from={180}><Audio src={staticFile('audio/ui-select-click.mp3')} volume={0.09} /></Sequence>
  <Sequence from={1242}><Audio src={staticFile('audio/transition-soft.mp3')} volume={0.045} /></Sequence>
  <Sequence from={1260}><Audio src={staticFile('audio/paper-page-turn.mp3')} volume={0.08} /></Sequence>
  <Sequence from={1770}><Audio src={staticFile('audio/glass-plate-slide.mp3')} volume={0.09} /></Sequence>
  <Sequence from={0} durationInFrames={180}><Opening /></Sequence>
  <Sequence from={180} durationInFrames={390}><Journey /></Sequence>
  <Sequence from={570} durationInFrames={456}><ComicReading /></Sequence>
  <Sequence from={1026} durationInFrames={234}><ComicSettings /></Sequence>
  <Sequence from={1260} durationInFrames={150}><ComicClimax /></Sequence>
  <Sequence from={1410} durationInFrames={180}><NovelControls /></Sequence>
  <Sequence from={1590} durationInFrames={180}><GlassControls /></Sequence>
  <Sequence from={1770} durationInFrames={150}><Roxy /></Sequence>
  <Sequence from={1920} durationInFrames={180}><Outro /></Sequence>
</AbsoluteFill>;
