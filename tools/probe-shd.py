import concurrent.futures
import re
from pathlib import Path
import requests
from bs4 import BeautifulSoup

out = Path('.source-verification/repair-20261020')
soup = BeautifulSoup(Path('.source-verification/public-novels-20261018/e1266403d5cddd9a.html').read_text(encoding='utf-8'), 'html.parser')
def read(script):
    try:
        s = requests.get('https://www.shuhuangdian.com' + script['src'], timeout=20).content.decode('utf-8')
        if '8167:function' in s:
            (out / 'shd-http.js').write_text(s, encoding='utf-8')
            return re.findall('.{0,80}(?:baseURL|https:|/api/).{0,180}', s)[:15]
    except Exception as e:
        return str(e)
with concurrent.futures.ThreadPoolExecutor(5) as pool:
    print(list(filter(None, pool.map(read, soup.select('script[src]')))))
