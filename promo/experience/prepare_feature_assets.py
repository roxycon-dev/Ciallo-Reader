"""Additional settled frames for the feature presentation; originals stay untouched."""
from pathlib import Path
from PIL import Image
import subprocess, io, json

root = Path(__file__).resolve().parent
ff = r'C:\Users\GuanXingRen\Miniconda3\Lib\site-packages\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v7.1.exe'
manifest = json.loads((root/'asset-manifest.json').read_text(encoding='utf-8'))
items = [
    ('comic-first.webp', '06_comic_read.mp4', 2.9, (0,460,1224,2250)),
    ('comic-native.webp', '06_comic_read.mp4', 11.3, None),
    ('download-native.webp', '03_detail_download.mp4', 19.8, None),
]
for name, source, second, crop in items:
    raw = subprocess.run([ff,'-hide_banner','-loglevel','error','-ss',str(second),'-i',str(root.parent/'user-footage'/source),'-frames:v','1','-f','image2pipe','-vcodec','png','-'],capture_output=True,check=True).stdout
    im = Image.open(io.BytesIO(raw)).convert('RGB')
    if crop: im = im.crop(crop)
    im.save(root/'assets'/name, 'WEBP', quality=94, method=6)
    manifest = [m for m in manifest if m['file'] != name]
    manifest.append(dict(file=name, source=source, second=second, crop_ltrb=crop, width=im.width, height=im.height, reason='Settled genuine device frame, no blur, redraw, or interpolated turn.'))
(root/'asset-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print('Prepared 3 additional authentic frames.')
