"""Archive anonymous public responses for the four requested chapter checks."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import json, re, requests
from bs4 import BeautifulSoup

OUT = Path('.source-verification/latest-comics')
OUT.mkdir(parents=True, exist_ok=True)
HEADERS = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/139.0.0.0 Safari/537.36'}

def fetch(item):
    name, url = item
    try:
        r = requests.get(url, headers=HEADERS, timeout=25)
        r.encoding = r.apparent_encoding
        (OUT / (name + '.html')).write_text(r.text, encoding='utf-8')
        soup = BeautifulSoup(r.text, 'html.parser')
        matches = [{'text': a.get_text(' ', strip=True)[:100], 'href': a.get('href')} for a in soup.select('a[href]') if re.search('金钱|金錢|180|181|1195|141|147|芙莉|过家家|過家家|海贼|海賊', a.get_text())]
        row = {'name': name, 'status': r.status_code, 'url': r.url, 'bytes': len(r.content), 'title': soup.title.get_text() if soup.title else '', 'matches': matches[:12]}
    except Exception as e:
        row = {'name': name, 'error': str(e)[:180]}
    print(json.dumps(row, ensure_ascii=False), flush=True)
    return row

if __name__ == '__main__':
    targets = [
        ('19-spy', 'https://m.sxchex.com/comic/70922'),
        ('19-frieren', 'https://m.sxchex.com/comic/68250'),
        ('19-op', 'https://m.sxchex.com/comic/71176'),
        ('19-spy-141', 'https://m.sxchex.com/chapter/1674318'),
        ('19-frieren-147', 'https://m.sxchex.com/chapter/1428912'),
        ('19-op-1195', 'https://m.sxchex.com/chapter/1690857'),
        ('stock-home', 'https://www.cstockbroker.com/custom/top'),
        ('u022-common', 'https://www.u022.com/static/km/js/common.js'),
        ('u022-search3', 'https://www.u022.com/search?key=这个勇者是金钱至上主义者'),
        ('u022-search4', 'https://www.u022.com/search/这个勇者是金钱至上主义者'),
        ('u022-spysearch', 'https://www.u022.com/search?key=间谍过家家'),
        ('mh160-frieren', 'https://www.mh160ba.com/kanmanhua/zangsongdefulilian/'),
        ('mh160-op', 'https://www.mh160ba.com/kanmanhua/haizeiwang/'),
        ('kk-hero', 'https://www.kkwebtoon.com/comic/30593'),
        ('guoman-work', 'https://www.guoman.net/comic/haizeiwang'),
        ('u022-chapter', 'https://www.u022.com/read/61945/3789471/1.html'),
        ('u022-home', 'https://www.u022.com/'),
        ('ai-work', 'https://mh.iqtao.cn/961857393.html'),
        ('ai-api', 'https://mh.iqtao.cn/index.php/api/comic/chapter?mid=961857393'),
        ('ai-canonical-api', 'https://www.alipaypay.com/index.php/api/comic/chapter?mid=961857393'),
        ('u022-spy', 'https://www.u022.com/info/61945.html'),
        ('u022-search', 'https://www.u022.com/search.php?searchword=这个勇者是金钱至上主义者'),
        ('guoman-op', 'https://www.guoman.net/chapter/500896-223224.html'),
        ('guoman-home', 'https://www.guoman.net/'),
        ('kk-home', 'https://www.kkwebtoon.com/'),
        ('ak-hero', 'https://akmh666.com/comic/zhegeyongzheshijinqianzhishangzhuyizhe'),
        ('zw-hero', 'https://www.zwccw.net/comic_2227.html'),
        ('mh160-spy', 'https://www.mh160ba.com/kanmanhua/jiandieguojiajia/'),
        ('dm5-frieren', 'https://www.dm5.com/manhua-zangsongdefulilian/'),
        ('xw-op', 'https://www.xwmanhua.com/comic/haizeiwang/read-1589.html'),
        ('keiyoushi-zh', 'https://api.github.com/repos/keiyoushi/extensions-source/contents/src/zh'),
    ]
    with ThreadPoolExecutor(max_workers=5) as pool:
        rows = list(pool.map(fetch, targets))
    (OUT / 'discovery.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding='utf-8')
