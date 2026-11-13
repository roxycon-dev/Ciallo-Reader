"""Package the 36.2-second native footage film for fully offline playback."""
from pathlib import Path
import base64, json, re, zipfile

root=Path(__file__).resolve().parent
out=root.parent/'output';out.mkdir(exist_ok=True)
html=(root/'index.html').read_text(encoding='utf-8')
css=(root/'native-film.css').read_text(encoding='utf-8')
js=(root/'native-film.js').read_text(encoding='utf-8')
html=html.replace('<link rel="stylesheet" href="native-film.css">','<style>'+css+'</style>')
html=html.replace('<script src="native-film.js" defer></script>','')
html=html.replace('</body>','<script>'+js+'</script></body>')
assets=sorted(set(re.findall(r'assets/[\w.-]+',html)))
mime={'.webp':'image/webp','.otf':'font/otf','.mp3':'audio/mpeg'}
for key in assets:
    asset=root/key
    html=html.replace(key,'data:'+mime[asset.suffix]+';base64,'+base64.b64encode(asset.read_bytes()).decode('ascii'))
standalone=out/'Ciallo-reading-experience.html';standalone.write_text(html,encoding='utf-8')
archive=out/'Ciallo-reading-experience.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=8) as z:
    for name in ['index.html','native-film.css','native-film.js','README.md','NATIVE-FILM-v4.md','native-manifest.json','ASSETS.md','server.mjs','build.py']+assets:
        z.write(root/name,'Ciallo-reading-experience/'+name)
print(json.dumps({'html':str(standalone),'bytes':standalone.stat().st_size,'zip':str(archive),'zip_bytes':archive.stat().st_size,'assets':len(assets)},ensure_ascii=False))
