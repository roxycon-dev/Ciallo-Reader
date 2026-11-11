"""Mux the shared v9 master; no separate or duplicated sound-effect timing."""
from pathlib import Path
import subprocess
ROOT=Path(__file__).resolve().parents[1]
FF=r'C:/Users/GuanXingRen/Miniconda3/Lib/site-packages/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v7.1.exe'
for stem,name in [('cinema-soundtrack','Ciallo-cinematic-preview.mp4'),('cinema-foley','Ciallo-cinematic-no-music.mp4')]:
    subprocess.run([FF,'-y','-hide_banner','-loglevel','error','-i',str(ROOT/'review-v8/motion-silent.mp4'),'-i',str(ROOT/'review-audio-v9'/(stem+'.wav')),'-map','0:v','-map','1:a','-c:v','copy','-c:a','aac','-b:a','192k','-t','41.35','-movflags','+faststart',str(ROOT.parent/'output'/name)],check=True)
print('Video unchanged; v9 master and foley-only versions exported.')
