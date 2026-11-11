"""Extract sharp, authentic stills. Never interpolate or synthesize product UI."""
from pathlib import Path
from PIL import Image
import io, subprocess, shutil, json

HERE=Path(__file__).resolve().parent
PROMO=HERE.parent
REPO=PROMO.parent
OUT=HERE/'assets'
OUT.mkdir(exist_ok=True)
FF=Path(r'C:\Users\GuanXingRen\Miniconda3\Lib\site-packages\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v7.1.exe')
records=[]
def extract(name,source,time,crop=None,max_width=1224,reason='Selected clear, settled screen state.'):
    result=subprocess.run([str(FF),'-hide_banner','-loglevel','error','-ss',str(time),'-i',str(PROMO/'user-footage'/source),'-frames:v','1','-f','image2pipe','-vcodec','png','-'],check=True,capture_output=True)
    im=Image.open(io.BytesIO(result.stdout)).convert('RGB')
    if crop: im=im.crop(crop)
    if im.width>max_width: im.resize((max_width,round(im.height*max_width/im.width)),Image.Resampling.LANCZOS).save(OUT/name,'WEBP',quality=94,method=6)
    else: im.save(OUT/name,'WEBP',quality=94,method=6)
    records.append({'file':name,'source':source,'second':time,'crop_ltrb':crop,'width':im.width,'height':im.height,'reason':reason})

extract('shelf.webp','04_shelf.mp4',1.6,reason='Settled bookshelf; no transition or notification.')
extract('glass.webp','11_glass_ui.mp4',18.0,reason='Settled native glass controls; original color and pixels.')
extract('reader.webp','07_comic_glass.mp4',4.0,reason='Native reader settings with underlying manga; no added mask.')
extract('comic-page.webp','06_comic_read.mp4',10.0,(0,460,1224,2250),reason='Later fully loaded page. Original black letterboxing excluded by composition.')
extract('comic-detail-a.webp','06_comic_read.mp4',10.0,(700,1560,1210,2230),reason='Roxy illustration in the genuine later manga page.')
extract('comic-detail-b.webp','06_comic_read.mp4',2.2,(780,1410,1150,2215),reason='Character close-up from a settled manga page; no loading or page-turn glitch.')
extract('comic-detail-c.webp','06_comic_read.mp4',10.0,(80,610,840,1110),reason='Architecture from the same authentic manga page.')
extract('novel-controls.webp','09_novel_settings.mp4',9.4,(0,1740,1224,2660),reason='Native settings panel only; no body prose, no censorship layer.')
extract('search.webp','02_search.mp4',3.2,(0,230,1224,445),reason='Complete search row after actual query entry.')
extract('download.webp','03_detail_download.mp4',19.8,(0,2020,1224,2700),reason='Download drawer with settled progress state.')
for source,target in [('mascot_reading.webp','roxy.webp'),('mascot_celebrate.webp','roxy-hello.webp')]:
    shutil.copy2(REPO/'app/src/main/res/drawable-nodpi'/source,OUT/target)
    records.append({'file':target,'source':'app/src/main/res/drawable-nodpi/'+source,'reason':'Original bundled application mascot artwork.'})
im=Image.open(PROMO/'captures/real-device/roxy-onboarding.png').convert('RGB')
im.save(OUT/'onboarding.webp','WEBP',quality=92,method=6)
for source,target in [('noto_serif_sc_regular.otf','serif.otf'),('noto_sans_sc_regular.otf','sans.otf')]:
    shutil.copy2(REPO/'app/src/main/res/font'/source,OUT/target)
subprocess.run([str(FF),'-y','-hide_banner','-loglevel','error','-i',str(PROMO/'production/remotion/public/audio/ambient-piano.wav'),'-codec:a','libmp3lame','-b:a','128k',str(OUT/'ambient-piano.mp3')],check=True)
(HERE/'asset-manifest.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
lines=['# Web experience assets','', 'All application and manga imagery comes from the user-supplied phone recordings. Files are sharp static frames or natural crops; no blur, pixel masking, frame interpolation, AI manga, or reconstructed app UI is used.','', 'The experience animates these stills in the browser. It deliberately does not replay the recordings: the source includes uneven gestures, loading frames, broken page turns around 4–8 seconds in clip 06, and hold frames. These motions are not represented as smooth native app behavior.','', '| File | Source | Time | Crop (left, top, right, bottom) |','|---|---|---|---|']
for r in records:lines.append(f"| {r['file']} | {r['source']} | {r.get('second','—')} | {r.get('crop_ltrb','—')} |")
lines+=['','Fonts: bundled Noto Serif SC and Noto Sans SC from the app.','Music: existing original procedural piano cue from promo/production/prepare_audio.py, off by default.','Roxy artwork: existing application artwork. Manga and covers: user-selected third-party material; this workflow does not confer additional rights.','No phone was connected, modified, or recorded for this web revision.']
(HERE/'ASSETS.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print(json.dumps({'files':len(records),'assets':str(OUT),'size_bytes':sum(p.stat().st_size for p in OUT.iterdir())}))
