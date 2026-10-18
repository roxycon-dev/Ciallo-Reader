"""Anonymous public-page evidence; never supplies user cookies or solves challenges."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import sys
from datetime import datetime, timezone
import requests
from bs4 import BeautifulSoup

OUT = Path('.source-verification/public-novels-20261018')
OUT.mkdir(parents=True, exist_ok=True)

def fetch(url):
    row = {'url': url}
    try:
        response = requests.get(url, timeout=(10, 25), headers={
            'User-Agent': 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/119.0.0.0 Mobile Safari/537.36',
            'Referer': url.split('/', 3)[0] + '//' + url.split('/')[2] + '/',
        })
        if not response.encoding or response.encoding.lower() == 'iso-8859-1':
            response.encoding = response.apparent_encoding
        key = hashlib.sha256(url.encode()).hexdigest()[:16]
        (OUT / (key + '.html')).write_text(response.text, encoding='utf-8')
        soup = BeautifulSoup(response.text, 'html.parser')
        row.update(status=response.status_code, final_url=response.url, title=soup.title.get_text() if soup.title else '', file=key+'.html',
                   forms=[{'action': x.get('action'), 'method': x.get('method'), 'inputs': [{k:y.get(k) for k in ['name','value','type']} for y in x.select('input')]} for x in soup.select('form')][:6],
                   links=[{'text':x.get_text(' ',strip=True)[:65],'href':x.get('href')} for x in soup.select('a[href]') if x.get_text(strip=True)][:85])
    except Exception as error:
        row['error'] = str(error)
    return row

if __name__ == '__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        rows = list(pool.map(fetch, sys.argv[1:]))
    (OUT / 'latest-probe.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
    (OUT / ('batch-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%f') + '.json')).write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
    for row in rows:
        print(json.dumps({key: value for key,value in row.items() if key not in ('links', 'forms')},ensure_ascii=True))
