"""Package the static experience as both a folder ZIP and a single offline HTML."""
from pathlib import Path
import base64, mimetypes, re, zipfile, json

root=Path(__file__).resolve().parent
out=root.parent/'output'
out.mkdir(exist_ok=True)
html=(root/'index.html').read_text(encoding='utf-8')
css=(root/'experience.css').read_text(encoding='utf-8-sig')
cinema_css=(root/'cinema.css').read_text(encoding='utf-8-sig')
js=(root/'experience.js').read_text(encoding='utf-8')
html=html.replace('<link rel="stylesheet" href="experience.css">', '<style>'+css+'</style>')
html=html.replace('<link rel="stylesheet" href="cinema.css">', '<style>'+cinema_css+'</style>')
html=html.replace('<script src="experience.js" defer></script>', '')
html=re.sub(r'\s*<link rel="preload"[^>]+>','',html)
html=html.replace('</body>','<script>'+js+'</script>\n</body>')
mime={'.webp':'image/webp','.otf':'font/otf','.mp3':'audio/mpeg'}
embedded=[]
for asset in sorted((root/'assets').iterdir()):
    key='assets/'+asset.name
    if key not in html:continue
    media_type=mime.get(asset.suffix,mimetypes.guess_type(asset.name)[0] or 'application/octet-stream')
    uri='data:'+media_type+';base64,'+base64.b64encode(asset.read_bytes()).decode('ascii')
    html=html.replace(key,uri)
    embedded.append(asset.name)
standalone=out/'Ciallo-reading-experience.html'
standalone.write_text(html,encoding='utf-8')
archive=out/'Ciallo-reading-experience.zip'
with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=8) as z:
    for name in ['index.html','experience.css','cinema.css','experience.js','README.md','DESIGN-v3.md','ASSETS.md','asset-manifest.json','server.mjs','package.json']:
        z.write(root/name,'Ciallo-reading-experience/'+name)
    for asset in sorted((root/'assets').iterdir()):
        z.write(asset,'Ciallo-reading-experience/assets/'+asset.name)
print(json.dumps({'standalone':str(standalone),'standalone_bytes':standalone.stat().st_size,'zip':str(archive),'zip_bytes':archive.stat().st_size,'embedded_assets':embedded},ensure_ascii=False))
