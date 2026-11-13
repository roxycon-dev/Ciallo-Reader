"""Package the 38.6-second native footage film for fully offline playback."""
from pathlib import Path
import base64, json, re, zipfile

root=Path(__file__).resolve().parent
out=root.parent/'output';out.mkdir(exist_ok=True)
html=(root/'index.html').read_text(encoding='utf-8')
css=(root/'kinetic-film.css').read_text(encoding='utf-8')
html=html.replace('<link rel="stylesheet" href="finish.css">','<style>'+(root/'finish.css').read_text(encoding='utf-8')+'</style>')
html=html.replace('<link rel="stylesheet" href="atelier.css">','<style>'+(root/'atelier.css').read_text(encoding='utf-8')+'</style>')
html=html.replace('<link rel="stylesheet" href="composition.css">','<style>'+(root/'composition.css').read_text(encoding='utf-8')+'</style>')
js=(root/'kinetic-film.js').read_text(encoding='utf-8')
html=html.replace('<link rel="stylesheet" href="kinetic-film.css">','<style>'+css+'</style>')
html=html.replace('<script src="kinetic-film.js" defer></script>','')
html=html.replace('</body>','<script>'+js+'</script></body>')
assets=sorted(set(re.findall(r'assets/[\w.-]+',html)))
mime={'.webp':'image/webp','.otf':'font/otf','.mp3':'audio/mpeg'}
for key in assets:
    asset=root/key
    html=html.replace(key,'data:'+mime[asset.suffix]+';base64,'+base64.b64encode(asset.read_bytes()).decode('ascii'))
standalone=out/'Ciallo-reading-experience.html';standalone.write_text(html,encoding='utf-8')
archive=out/'Ciallo-reading-experience.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=8) as z:
    for name in ['index.html','kinetic-film.css','finish.css','atelier.css','composition.css','kinetic-film.js','README.md','DIRECTOR-v5.md','ATELIER-v6.md','native-manifest.json','kinetic-manifest.json','ASSETS.md','AUDIO-ATTRIBUTION.md','server.mjs','build.py']+assets:
        z.write(root/name,'Ciallo-reading-experience/'+name)
print(json.dumps({'html':str(standalone),'bytes':standalone.stat().st_size,'zip':str(archive),'zip_bytes':archive.stat().st_size,'assets':len(assets)},ensure_ascii=False))
