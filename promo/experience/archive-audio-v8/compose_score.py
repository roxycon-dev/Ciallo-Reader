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

# A four-note idea returns in the coda. The harmony opens from Bm9 to Dmaj9.
for t,m,v in [(0.28,66,.14),(1.04,69,.11),(1.82,73,.10),(2.50,76,.085)]:
    piano(t,m,v,pan=-.16)
pad(0,[47,54,61,66],4.1,.065)
sections=[
    (3.1,6.4,[43,50,57,62,66],.58),
    (6.4,7.9,[45,52,59,64,69],.43),
    (7.9,10.4,[47,54,61,66,69],.42),
    (10.4,12.15,[50,57,61,66,69],.44),
    (12.15,15.4,[43,50,57,62,66],.39),
    (15.4,20.2,[45,52,59,64,69],.40),
    (20.95,23.95,[47,54,61,66,69],1.0),
    (24.95,27.35,[43,50,57,62,66],.60),
    (27.35,28.95,[45,52,59,64,69],.55),
    (28.95,31.25,[50,57,61,66,73],.48),
    (31.25,33.45,[43,50,57,62,69],.74),
    (33.45,36.15,[47,54,61,66,73],.82),
]
for start,end,chord,step in sections:
    quiet=20<start<24
    piano(start,chord[0],.15 if not quiet else .08,length=5.5,pan=-.25)
    pad(start,chord[:4],end-start+1.0,.055 if not quiet else .023)
    for j,at in enumerate(np.arange(start+.12,end-.08,step)):
        m=chord[1:][[0,2,1,3,2,1][j%6]]+12
        piano(float(at),m,.067 if not quiet else .050,pan=(-.28 if j%2 else .28))
# Deliberate silence in the attack at 20.2 s lets the novel reveal breathe.
piano(23.95,78,.062,length=4.2,pan=.15)
for t,m,v in [(36.15,66,.11),(36.84,69,.09),(37.55,73,.085),(38.2,76,.075)]:
    piano(t,m,v,pan=-.1)
pad(36.15,[50,57,61,66],5.2,.07)
for k,m in enumerate([38,50,57,61,66,73]):
    piano(38.55+k*.07,m,.14 if k==0 else .055,length=6,pan=(k-2.5)*.11)

# Stereo early reflections and long, filtered tails. Every layer is reproducible.
wet=dry.copy()
for delay,gain in [(.061,.12),(.127,.10),(.263,.12),(.421,.1),(.673,.075),(1.01,.04),(1.51,.028)]:
    offset=int(delay*RATE)
    wet[offset:]+=dry[:-offset,::-1]*gain
fade=np.ones(N)
fade[:int(.15*RATE)]=np.linspace(0,1,int(.15*RATE))
fade[-int(1.7*RATE):]=np.linspace(1,0,int(1.7*RATE))**1.3
wet*=fade[:,None]
wet*=.48/max(np.max(np.abs(wet)),1e-6)
wav=ROOT/'review-v8/cinema-score.wav'
wav.parent.mkdir(parents=True,exist_ok=True)
with wave.open(str(wav),'wb') as f:
    f.setnchannels(2);f.setsampwidth(2);f.setframerate(RATE)
    f.writeframes((np.clip(wet,-1,1)*32767).astype(np.int16).tobytes())
ff=Path(r'C:\Users\GuanXingRen\Miniconda3\Lib\site-packages\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v7.1.exe')
subprocess.run([str(ff),'-y','-hide_banner','-loglevel','error','-i',str(wav),'-c:a','libmp3lame','-b:a','192k',str(ROOT/'assets/cinema-score.mp3')],check=True)
(ROOT/'review-v8/score-cues.json').write_text(json.dumps({'duration':DURATION,'original':True,'notes':notes},indent=2),encoding='utf-8')
print(f'Original score: {DURATION}s, {len(notes)} piano attacks, stereo.')
