"""Original, deterministic 41.35 s score; no borrowed melody or recordings."""
from pathlib import Path
import json, subprocess, wave
import numpy as np

ROOT = Path(__file__).resolve().parent
RATE, DURATION = 44100, 41.35
N = int(RATE * DURATION)
dry = np.zeros((N, 2), dtype=np.float64)
notes = []

def hz(midi):
    return 440 * 2 ** ((midi - 69) / 12)

def place(tone, at, amp, pan=0):
    start = int(at * RATE)
    count = min(len(tone), N - start)
    if count <= 0:
        return
    gains = np.array([np.sqrt((1-pan)/2), np.sqrt((1+pan)/2)])
    dry[start:start+count] += tone[:count, None] * gains * amp

def piano(at, midi, amp=.13, length=4.8, pan=0):
    t = np.arange(int(length*RATE))/RATE
    f = hz(midi)
    tone = np.zeros_like(t)
    for k, a in enumerate([1,.33,.14,.075,.025], 1):
        env = (1-np.exp(-t/.004))*np.exp(-t/(1.7/(k**.42)))
        tone += a*env*np.sin(2*np.pi*f*k*(1+.000035*k*k)*t)
    # The closely tuned second string and sympathetic fifth add body, not an oscillator buzz.
    tone += .13*np.sin(2*np.pi*f*1.0013*t)*(1-np.exp(-t/.009))*np.exp(-t/1.5)
    tone *= np.minimum(1,(length-t)/.15)
    place(tone,at,amp,pan)
    notes.append({'second':round(at,3),'midi':midi,'velocity':amp})

def pad(at, pitches, length, amp):
    t = np.arange(int(length*RATE))/RATE
    env = np.sin(np.pi*np.clip(t/length,0,1))**1.8
    for i,m in enumerate(pitches):
        f=hz(m)
        tone=(np.sin(2*np.pi*f*t)+.2*np.sin(2*np.pi*f*2*t))*.55
        tone+=.23*np.sin(2*np.pi*f*1.0015*t)+.22*np.sin(2*np.pi*f*.9985*t)
        place(tone*env,at,amp/len(pitches),(i/(len(pitches)-1)-.5)*.8)

# The music follows visual events, not a free-running arpeggio grid.
FF=r'C:/Users/GuanXingRen/Miniconda3/Lib/site-packages/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v7.1.exe'
foley=np.zeros_like(dry)
events=[]
rng=np.random.default_rng(827)
def cue(at,description):
    events.append({'second':round(at,3),'frame_30fps':round(at*30),'picture':description})
def chord(at,pitches,amp=.065):
    for i,m in enumerate(pitches): piano(at+i*.014,m,amp,pan=(i-(len(pitches)-1)/2)*.16)
def pulse(at,amp=.075):
    t=np.arange(int(.36*RATE))/RATE
    phase=2*np.pi*(49*t+32*.026*(1-np.exp(-t/.026)))
    tone=np.sin(phase)*(1-np.exp(-t/.003))*np.exp(-t/.09)
    place(tone,at,amp)
def air(start,end,amp=.03,pan=0):
    t=np.arange(int((end-start)*RATE))/RATE
    noise=rng.normal(0,1,len(t))
    f=np.fft.rfftfreq(len(t),1/RATE)
    noise=np.fft.irfft(np.fft.rfft(noise)*np.exp(-((f-1200)/900)**2),len(t))
    noise/=max(np.max(np.abs(noise)),1e-8)
    place(noise*np.sin(np.pi*t/(end-start))**2,start,amp,pan)
def material(name,at,level=.08,length=.5):
    raw=subprocess.check_output([FF,'-hide_banner','-loglevel','error','-i',str(ROOT/'assets'/name),'-f','f32le','-ac','2','-ar',str(RATE),'pipe:1'])
    x=np.frombuffer(raw,dtype=np.float32).reshape(-1,2).copy()
    block=441
    energy=np.array([np.mean(x[i:i+block]**2) for i in range(0,len(x),block)])
    peak=int(np.argmax(energy))*block
    before=int(min(.16,length*.3)*RATE)
    a=max(0,peak-before);x=x[a:a+int(length*RATE)]
    if len(x)==0:return
    env=np.ones(len(x));r=min(int(.035*RATE),len(x)//2)
    env[:r]=np.linspace(0,1,r);env[-r:]=np.linspace(1,0,r)
    x*=env[:,None]*level/max(np.max(np.abs(x)),1e-7)
    start=max(0,int(at*RATE)-(peak-a));n=min(len(x),N-start)
    foley[start:start+n]+=x[:n]

# Opening: the two-note theme opens with the book, then resolves when it settles.
for t,m,v in [(.22,66,.13),(1.055,73,.13),(1.93,69,.105)]:piano(t,m,v,pan=-.12)
pad(.1,[47,54,61],3.0,.06);air(.3,1.8,.028)
material('paper-page-turn.mp3',1.055,.055,.65);cue(1.055,'开页运动速度峰值')
# Discovery: chord changes coincide with the cuts; short pickups lead into them.
for t,ch in [(3.1,[43,57,62,66]),(6.4,[45,59,64]),(7.9,[47,61,66]),(10.4,[50,61,69])]:
    chord(t,ch,.065);pulse(t,.06);cue(t,'发现段切点')
for t,m in [(4.45,73),(5.2,69),(7.1,71),(8.65,73),(9.35,78),(11.1,76)]:piano(t,m,.074)
pad(3.1,[43,50,62,66],3.2,.07);pad(7.9,[47,54,61,66],3.9,.07)
air(7.58,8.10,.045,-.1);air(11.55,12.15,.065)
# Manga: the entry cut is the strongest musical accent in the film.
pulse(12.15,.21);chord(12.15,[43,50,62],.10);cue(12.15,'走进去字卡：短重音')
material('paper-page-turn.mp3',12.15,.105,.45)
chord(12.7,[55,62,66,69],.085);pad(12.7,[43,55,62,66],2.7,.09)
for t,m in [(13.25,78),(13.8,74),(14.6,73)]:piano(t,m,.077)
# The rail lands every 0.8 s. A restrained beat and chord tone follow each landing.
for i,m in enumerate([64,66,69,71,73,76]):
    t=15.4+i*.8;pulse(t,.09 if i%2==0 else .065);piano(t,m,.105,pan=(i%2-.5)*.28)
    piano(t+.4,[57,59,61][i%3],.047,length=1.2)
    if i:air(t-.285,t,.026,(i%2-.5)*.5)
    cue(t,'阅读设置第%d屏落定'%(i+1))
pad(15.4,[45,52,59,64],4.65,.08)
# Quiet page: silence is part of the score. No metronomic notes under the novel.
material('paper-page-turn.mp3',20.20,.06,.32);cue(20.20,'静下来：撤掉鼓点与混响尾巴')
piano(21.18,66,.085,pan=-.12);piano(22.45,73,.065,pan=.12)
pad(21.05,[47,54,61],2.7,.034)
piano(23.95,69,.07);material('paper-page-turn.mp3',23.95,.05,.32);cue(23.95,'书签保存')
# Download's three genuine states shape the phrase itself.
chord(24.95,[43,57,62],.07);pulse(24.95,.07)
piano(25.45,69,.06);cue(24.95,'下载开始')
cue(25.9,'已暂停：短暂收声')
chord(26.5,[50,57,61,66],.08);cue(26.5,'已完成：和声解决')
chord(27.35,[47,59,66],.067);material('paper-page-turn.mp3',27.35,.05,.38)
# Theme color resolves at the actual wipe midpoint. Glass rings at its moving highlight.
pad(28.75,[43,55,62,66],2.5,.08);chord(28.95,[55,62,66],.068)
air(29.9,30.7,.028);piano(30.4,78,.09,pan=.15);cue(30.4,'主题颜色交替中点')
pad(31.25,[45,57,61,64],2.15,.055)
material('glass-plate-slide.mp3',32.35,.085,.65);piano(32.35,76,.074);cue(32.35,'玻璃掠光中点')
chord(33.45,[47,59,66],.075);piano(34.15,73,.085);piano(35.2,69,.062)
# Coda: repeat the opening theme only after the picture emerges from its dark breath.
for t,m,v in [(36.7,66,.10),(37.35,73,.095),(38.0,69,.075)]:piano(t,m,v)
cue(36.7,'故事还在：从暗场中显现，主题返回')
pad(36.7,[50,57,61,66],4.65,.075)
chord(39.10,[38,50,57,61,66],.069);piano(39.66,73,.055)
cue(39.10,'片尾品牌从暗场落定：最后和弦')

# Reverb is shorter in the kinetic middle, longer only for the beginning and coda.
wet=dry.copy()
for delay,gain in [(.071,.11),(.163,.08),(.281,.075),(.463,.055),(.719,.035)]:
    n=int(delay*RATE);wet[n:]+=dry[:-n,::-1]*gain
clock=np.arange(N)/RATE
# Automate actual silence, including tails, when the visuals pause.
automation=np.interp(clock,[0,.1,19.96,20.2,20.72,21.1,25.82,25.9,26.15,26.45,36.1,36.41,36.65,36.85,38.55,38.814,39.1,39.85,41.35],
                            [0,1,1,.025,.025,1,1,.10,.10,1,1,.20,.65,1,1,.15,1,1,0])
wet*=automation[:,None]
# Fixed gain preserves the musical differences between quiet and full sections.
wet*=.55/max(np.max(np.abs(wet)),1e-8)
master=wet+foley
master*=min(1,.79/max(np.max(np.abs(master)),1e-8))
review=ROOT/'review-audio-v9';review.mkdir(exist_ok=True)
for name,data in [('cinema-score',wet),('cinema-foley',foley),('cinema-soundtrack',master)]:
    wav=review/(name+'.wav')
    with wave.open(str(wav),'wb') as f:
        f.setnchannels(2);f.setsampwidth(2);f.setframerate(RATE);f.writeframes((np.clip(data,-1,1)*32767).astype(np.int16).tobytes())
    subprocess.run([FF,'-y','-hide_banner','-loglevel','error','-i',str(wav),'-c:a','libmp3lame','-b:a','192k',str(ROOT/'assets'/(name+'.mp3'))],check=True)
(review/'score-cues.json').write_text(json.dumps({'duration':DURATION,'original':True,'visual_cues':events,'notes':notes},ensure_ascii=False,indent=2),encoding='utf-8')
print(f'Edited score: {len(notes)} attacks; {len(events)} visual cues; one master shared by HTML and MP4.')
