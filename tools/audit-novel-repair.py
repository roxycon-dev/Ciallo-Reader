"""Save anonymous live responses for source repair; no account state is read."""
import concurrent.futures
import json
from pathlib import Path
import requests
from bs4 import BeautifulSoup

OUT = Path('.source-verification/repair-20261020')
OUT.mkdir(parents=True, exist_ok=True)
URLS = {
    'nineteen': 'https://m.sxchex.com/custom/update?page=1',
    'fq_bundle': 'https://lf-fe.fqnovelstatic.com/obj/novel-fanqie-fe/toutiao/muye/js/muye_dab5cb12.js',
    'quanben': 'https://www.quanben.io/index.php?c=book&a=search&keywords=小王子',
    'novelia': 'https://n.novelia.cc/api/novel?page=0&pageSize=20&query=无职&provider=syosetu,kakuyomu&type=0&level=1&translate=2&sort=0',
    'ixdzs': 'https://ixdzs8.com/bsearch?q=无职',
    'fanqie_detail': 'https://fanqienovel.com/page/7608478271843355673',
}

def fetch(item):
    key, url = item
    row = {'key': key, 'url': url}
    try:
        r = requests.get(url, timeout=(12, 25), headers={'User-Agent': 'Mozilla/5.0', 'Referer': 'https://' + url.split('/')[2] + '/'})
        r.encoding = 'utf-8'
        (OUT / (key + '.txt')).write_text(r.text, encoding='utf-8')
        row.update(status=r.status_code, bytes=len(r.content), chars=len(r.text))
        if key != 'fq_bundle':
            soup = BeautifulSoup(r.text, 'html.parser')
            row.update(title=soup.title.get_text() if soup.title else '', elements=len(soup.find_all()))
    except Exception as e:
        row['error'] = str(e)
    return row

if __name__ == '__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        rows = list(pool.map(fetch, URLS.items()))
    (OUT / 'live.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(rows, ensure_ascii=True, indent=2))
