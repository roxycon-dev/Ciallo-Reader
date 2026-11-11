"""Check alternate public image renditions for failed WordPress reader URLs."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import io
import json
import requests
from PIL import Image

OUT = Path('.source-verification/money-hero')
audit = json.loads((OUT / 'image-audit.json').read_text(encoding='utf-8'))
failed = [r for r in audit['rows'] if not r['decoded'] and not (OUT / 'chapter-latest' / ('%03d.jpeg' % r['page'])).exists()]

def run(row):
    urls = [row['url'].replace('i3.wp.com', host) for host in ['i1.wp.com', 'i2.wp.com']] + [row['url'] + '?w=1080', row['url'].replace('@1080w.avif', '@1080w.jpg'), row['url'].replace('https://i3.wp.com/', 'http://')]
    results = []
    for url in urls:
        result = {'page': row['page'], 'url': url}
        try:
            r = requests.get(url, timeout=25)
            result.update(status=r.status_code, bytes=len(r.content))
            r.raise_for_status()
            im = Image.open(io.BytesIO(r.content))
            im.load()
            result.update(decoded=True, size=im.size, format=im.format)
            (OUT / 'chapter-latest' / ('%03d.jpeg' % row['page'])).write_bytes(r.content)
        except Exception as e:
            result.update(decoded=False, error=str(e)[:130])
        results.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
        if result['decoded']:
            break
    return results

with ThreadPoolExecutor(max_workers=3) as pool:
    rows = list(pool.map(run, failed))
(OUT / 'image-route-audit.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding='utf-8')
