"""Validate a public reader's complete image list, including real image decoding."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import hashlib
import io
import json
import requests
import sys
from bs4 import BeautifulSoup
from PIL import Image, ImageStat

OUT = Path('.source-verification/money-hero')
URL = 'https://www.alipaypay.com/961857393/5846169.html'
HEADERS = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/139.0.0.0 Safari/537.36', 'Referer': URL}

def main():
    jpeg = '--jpeg' in sys.argv
    if jpeg:
        HEADERS['Accept'] = 'image/jpeg'
    text = (OUT / 'alipaypay-reader.txt').read_text(encoding='utf-8')
    doc = BeautifulSoup(text, 'html.parser')
    images = [('https:' + img['data-src'] if img['data-src'].startswith('//') else img['data-src']) for img in doc.select('img.lazy[data-src]')]
    if jpeg:
        images = [url + '?w=1080' for url in images]
    else:
        (OUT / 'image-urls.json').write_text(json.dumps(images, indent=2), encoding='utf-8')
    dest = OUT / ('chapter-latest-jpeg' if jpeg else 'chapter-latest')
    dest.mkdir(exist_ok=True)

    def get(item):
        index, url = item
        row = {'page': index + 1, 'url': url}
        try:
            r = requests.get(url, headers=HEADERS, timeout=30)
            row.update(status=r.status_code, contentType=r.headers.get('Content-Type'), bytes=len(r.content))
            r.raise_for_status()
            image = Image.open(io.BytesIO(r.content))
            image.load()
            row.update(format=image.format, size=image.size, extrema=ImageStat.Stat(image.convert('RGB')).extrema, sha256=hashlib.sha256(r.content).hexdigest(), decoded=True)
            (dest / ('%03d.%s' % (index + 1, image.format.lower()))).write_bytes(r.content)
        except Exception as e:
            row.update(decoded=False, error=str(e)[:200])
        print(json.dumps(row, ensure_ascii=False), flush=True)
        return row

    with ThreadPoolExecutor(max_workers=3) as pool:
        rows = list(pool.map(get, enumerate(images)))
    report = {'reader': URL, 'expectedPages': 87, 'parsedPages': len(images), 'decodedPages': sum(r['decoded'] for r in rows), 'rows': rows}
    (OUT / ('image-audit-jpeg.json' if jpeg else 'image-audit.json')).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('SUMMARY', report['expectedPages'], report['parsedPages'], report['decodedPages'])

if __name__ == '__main__':
    main()
