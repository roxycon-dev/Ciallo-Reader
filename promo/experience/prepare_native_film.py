"""Select genuine UI states for the fast product film. No UI pixels are repainted."""
from pathlib import Path
from PIL import Image
import subprocess, io, json

ROOT=Path(__file__).resolve().parent
FF=r'C:\Users\GuanXingRen\Miniconda3\Lib\site-packages\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v7.1.exe'
OUT=ROOT/'assets'; records=[]
ITEMS=[
 ('native-source-picker','11_glass_ui.mp4',2.0,(0,1250,1224,2680)),
 ('native-source-manage','11_glass_ui.mp4',4.8,None),
 ('native-source-more','11_glass_ui.mp4',7.4,None),
 ('native-search','02_search.mp4',6.5,None),
 ('native-search-more','02_search.mp4',8.8,None),
 ('native-source-jump','03_detail_download.mp4',7.8,(0,70,1224,1800)),
 ('native-detail','03_detail_download.mp4',12.2,None),
 ('native-chapters','03_detail_download.mp4',12.2,(0,1260,1224,2700)),
 ('native-downloading','03_detail_download.mp4',14.4,(0,1920,1224,2690)),
 ('native-download-paused','03_detail_download.mp4',19.8,(0,2180,1224,2700)),
 ('native-download-done','03_detail_download.mp4',21.0,(0,2180,1224,2700)),
 ('native-comic-modes','07_comic_glass.mp4',2.3,(0,650,1224,2140)),
 ('native-comic-layout','07_comic_glass.mp4',4.0,(0,650,1224,2140)),
 ('native-comic-image','07_comic_glass.mp4',8.1,(0,650,1224,2140)),
 ('native-comic-translate','07_comic_glass.mp4',10.4,(0,650,1224,2140)),
 ('native-comic-ocr','07_comic_glass.mp4',11.0,(0,650,1224,2140)),
 ('native-comic-theme','07_comic_glass.mp4',12.3,(0,650,1224,2140)),
 ('native-comic-auto','07_comic_glass.mp4',14.3,(0,650,1224,2140)),
 ('native-comic-gesture','07_comic_glass.mp4',15.5,(0,650,1224,2140)),
 ('native-novel-type','09_novel_settings.mp4',20.0,(0,450,1224,2650)),
 ('native-novel-layout','09_novel_settings.mp4',23.2,(0,450,1224,2650)),
 ('native-novel-text','10_roxy.mp4',0.5,(0,0,1224,1050)),
 ('native-bookmark','10_roxy.mp4',2.65,(326,970,914,1680)),
 ('native-theme-green','11_glass_ui.mp4',13.3,None),
 ('native-theme-orange','11_glass_ui.mp4',14.8,None),
 ('native-glass-controls','11_glass_ui.mp4',20.5,None),
 ('native-progress','06_comic_read.mp4',11.3,(0,2300,1224,2700)),
 ('native-import','04_shelf.mp4',1.6,(0,220,1224,690)),
 ('native-stats','12_history_stats.mp4',1.6,None),
 ('native-calendar','12_history_stats.mp4',3.3,None),
 ('native-trends','12_history_stats.mp4',6.8,None),
 ('native-resume','12_history_stats.mp4',0.1,(0,0,432,510)),
]
ITEMS=[(name,src,t,(0,0,1224,1950) if name.startswith('native-comic-') else crop) for name,src,t,crop in ITEMS]
for name,source,second,crop in ITEMS:
    raw=subprocess.run([FF,'-hide_banner','-loglevel','error','-ss',str(second),'-i',str(ROOT.parent/'user-footage'/source),'-frames:v','1','-f','image2pipe','-vcodec','png','-'],check=True,capture_output=True).stdout
    im=Image.open(io.BytesIO(raw)).convert('RGB')
    if crop:im=im.crop(crop)
    im.save(OUT/(name+'.webp'),'WEBP',quality=95,method=5)
    records.append(dict(file=name+'.webp',source=source,second=second,crop_ltrb=crop,width=im.width,height=im.height))
(ROOT/'native-manifest.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
# A contact sheet for selection; never used as product imagery.
thumbs=[]
from PIL import ImageDraw,ImageFont
font=ImageFont.truetype(r'C:\Windows\Fonts\arial.ttf',14)
for r in records:
    im=Image.open(OUT/r['file']);im.thumbnail((230,440))
    tile=Image.new('RGB',(254,482),'#28332d');tile.paste(im,((254-im.width)//2,28));ImageDraw.Draw(tile).text((8,460),r['file'].replace('native-',''),font=font,fill='white');thumbs.append(tile)
sheet=Image.new('RGB',(254*7,482*5),'#19231d')
for i,t in enumerate(thumbs):sheet.paste(t,((i%7)*254,(i//7)*482))
(ROOT/'review-v4').mkdir(exist_ok=True)
sheet.save(ROOT/'review-v4'/'native-selection.jpg',quality=90)
print('Extracted',len(records),'authentic UI states.')
