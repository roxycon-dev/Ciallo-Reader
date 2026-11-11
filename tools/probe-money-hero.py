"""Read-only public source discovery; save live responses for reproducible audits."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import json
import re
import requests
from bs4 import BeautifulSoup

OUT = Path('.source-verification/money-hero')
OUT.mkdir(parents=True, exist_ok=True)
HEADERS = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/139.0.0.0 Safari/537.36'}

def fetch(item):
    name, url = item
    try:
        r = requests.get(url, headers=HEADERS, timeout=25)
        r.encoding = 'utf-8'
        (OUT / (name + '.txt')).write_text(r.text, encoding='utf-8')
        soup = BeautifulSoup(r.text, 'html.parser')
        links = [{'text': a.get_text(' ', strip=True)[:140], 'href': a.get('href')} for a in soup.select('a[href]') if re.search('金钱|勇者|第1[3-9][0-9]|[2-9][0-9]{2}话', a.get_text())]
        numbers = [float(m) for m in re.findall(r'第\s*(\d+(?:\.\d+)?)\s*[话話]', soup.get_text())]
        row = {'name': name, 'status': r.status_code, 'url': r.url, 'length': len(r.content), 'title': soup.title.get_text() if soup.title else '', 'highestNumber': max(numbers, default=None), 'matches': links[:25]}
    except Exception as e:
        row = {'name': name, 'error': str(e)[:250]}
    print(json.dumps(row, ensure_ascii=False), flush=True)
    return row

if __name__ == '__main__':
    targets = [
        ('colamanga-search', 'https://www.colamanga.com/search?type=1&searchString=这个勇者是金钱至上主义者'),
        ('baozi', 'https://www.baozimh.com/comic/zhegeyongzheshijinqianzhishangzhuyizhe-yunhaijinggewenhuazuozhelimodao'),
        ('baozi-org', 'https://base.baozimh.org/manga/zhegeyongzheshijinqianzhishangzhuyizhe-yunhaijinggewenhuazuozhelimodao'),
        ('guazi', 'https://www.guazimanhua.com/comic.php?id=25945'),
        ('manhuagui-cc', 'https://manhuagui.cc/comic/zhegeyongzheshijinqianzhishangzhuyizhe'),
        ('alipaypay', 'https://www.alipaypay.com/search/?keywords=这个勇者是金钱至上主义者'),
        ('keiyoushi-zh', 'https://api.github.com/repos/keiyoushi/extensions-source/contents/src/zh'),
        ('picacg', 'https://raw.githubusercontent.com/venera-app/venera-configs/main/picacg.js'),
        ('sixmh-home', 'https://www.n0n9.com/'),
        ('sixmh-search', 'https://www.n0n9.com/search?keyword=这个勇者是金钱至上主义者'),
        ('alipaypay-search', 'https://www.alipaypay.com/search/这个勇者是金钱至上主义者'),
        ('akmh666', 'https://akmh666.com/comic/zhegeyongzheshijinqianzhishangzhuyizhe'),
        ('goda-api-doc', 'https://raw.githubusercontent.com/keiyoushi/extensions-source/main/lib-multisrc/goda/src/eu/kanade/tachiyomi/multisrc/goda/GoDa.kt'),
        ('baozi-org-api-doc', 'https://api-get-v3.mgsearcher.com/'),
    ]
    with ThreadPoolExecutor(max_workers=4) as pool:
        rows = list(pool.map(fetch, targets))
    (OUT / 'discovery.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding='utf-8')
