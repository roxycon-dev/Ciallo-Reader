"""Anonymous discovery using current upstream source routes; archives evidence, never authenticates."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from bs4 import BeautifulSoup
import json, re, requests

OUT = Path('.source-verification/latest-comics/hero-expanded')
OUT.mkdir(parents=True, exist_ok=True)
KEY = '这个勇者是金钱至上主义者'
HEADERS = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/139.0.0.0 Safari/537.36'}

def run(item):
    name, method, url, data = item
    try:
        r = requests.request(method, url, data=data, headers=HEADERS, timeout=22)
        r.encoding = 'utf-8'
        (OUT / (name + '.html')).write_text(r.text, encoding='utf-8')
        doc = BeautifulSoup(r.text, 'html.parser')
        links = [{'title': a.get_text(' ', strip=True)[:110], 'url': a.get('href')} for a in doc.select('a[href]') if re.search('金钱|金錢|鹰身|鷹身|180[.．]5|天使|180话', a.get_text())]
        row = {'name': name, 'status': r.status_code, 'url': r.url, 'bytes': len(r.content), 'title': doc.title.text if doc.title else '', 'links': links[:12]}
        if r.headers.get('Content-Type', '').startswith('application/json'):
            row['jsonSample'] = r.text[:1500]
    except Exception as e:
        row = {'name': name, 'error': str(e)[:180]}
    print(json.dumps(row, ensure_ascii=False), flush=True)
    return row

if __name__ == '__main__':
    targets = [
        ('liumanhua', 'GET', 'https://www.liumanhua.com/search?key=' + KEY, None),
        ('miaoqu', 'GET', 'https://www.miaoqumh.org/search?key=' + KEY, None),
        ('mhua5', 'GET', 'https://www.mhua5.com/search?key=' + KEY, None),
        ('manhuashe', 'GET', 'https://www.311s.com/search/' + KEY + '/1', None),
        ('manhuashe-alt', 'GET', 'https://www.m206.com/search/' + KEY + '/1', None),
        ('dumanwu', 'POST', 'https://www.dumanwu1.com/s', {'k': KEY[:12]}),
        ('rumanhua', 'POST', 'https://www.rumanhua2.com/s', {'k': KEY[:12]}),
        ('bakamh', 'GET', 'https://bakamh.com/?s=' + KEY + '&post_type=wp-manga', None),
        ('mh1234', 'GET', 'https://m.wmh1234.com/search/?keywords=' + KEY, None),
        ('jjmh', 'GET', 'https://www.jjmhw2.top/search?keyword=' + KEY, None),
        ('baoziai', 'GET', 'https://www.baoziaicomic.com/search?q=' + KEY, None),
        ('baoziai-api', 'GET', 'https://www.baoziaicomic.com/api/search?keyword=' + KEY, None),
    ]
    with ThreadPoolExecutor(max_workers=4) as pool:
        rows = list(pool.map(run, targets))
    (OUT / 'summary.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding='utf-8')
