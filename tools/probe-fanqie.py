import concurrent.futures
import json
from pathlib import Path
import requests

OUT = Path('.source-verification/repair-20261020')
book = '7608478271843355673'
toc = json.loads((OUT / 'fq_probe1.json').read_text(encoding='utf-8'))['data']
print('directory keys', list(toc), 'chapters', len(toc['allItemIds']))
urls = {
    'alt_first': 'http://101.35.133.34:5000/api/content?tab=小说&item_id=' + toc['allItemIds'][0],
    'alt_middle': 'http://101.35.133.34:5000/api/content?tab=小说&item_id=' + toc['allItemIds'][len(toc['allItemIds'])//2],
    'alt_last': 'http://101.35.133.34:5000/api/content?tab=小说&item_id=' + toc['allItemIds'][-1],
    'shd_query': 'https://www.shuhuangdian.com/api/book/search?keyword=无职转生，仅此而已的人生&page=1&pageSize=20',
    'shd_detail': 'https://www.shuhuangdian.com/api/book?id=fq' + book,
    'biek_first': 'https://biek.top/api/fq.php?action=content&item_id=' + toc['allItemIds'][0],
    'biek_middle': 'https://biek.top/api/fq.php?action=content&item_id=' + toc['allItemIds'][len(toc['allItemIds'])//2],
    'biek_search': 'https://biek.top/api/fq.php?action=search&q=无职转生，仅此而已的人生',
    'biek_detail': 'https://biek.top/api/fq.php?action=detail&book_id=' + book,
    'biek_content': 'https://biek.top/api/fq.php?action=content&item_id=' + toc['allItemIds'][-1],
    'cengui_docs': 'https://api.cenguigui.cn/api/tomato.html',
    'fq_exact_search': 'https://novel.snssdk.com/api/novel/channel/homepage/search/search/v1/?device_platform=android&parent_enterfrom=novel_channel_search.tab.&offset=0&aid=1967&q=无职转生，仅此而已的人生',
    'fq_info': 'https://fanqienovel.com/api/book/info?bookId=' + book,
    'fq_app_info': 'https://novel.snssdk.com/api/novel/book/directory/list/v1/?book_id=' + book + '&aid=1967',
}
for label, chapter in [('first', toc['allItemIds'][0]), ('middle', toc['allItemIds'][len(toc['allItemIds'])//2]), ('last', toc['allItemIds'][-1])]:
    urls['fq_' + label] = 'https://fanqienovel.com/api/reader/full?itemId=' + chapter

def fetch(item):
    k, u = item
    try:
        session = requests.Session()
        if k.startswith('alt_'): session.trust_env = False
        r = session.get(u, timeout=25, headers={'User-Agent':'Mozilla/5.0', 'Referer':'https://fanqienovel.com/'})
        r.encoding = 'utf-8'
        (OUT / (k + '.json')).write_text(r.text, encoding='utf-8')
        d = r.json()
        row = {'key':k, 'status':r.status_code, 'code': d.get('code'), 'bytes':len(r.content)}
        data = d.get('data') or {}
        if isinstance(data, dict):
            row['keys'] = list(data)
            if k.startswith('shd'): row['sample'] = str(d)[:2600]
            row['books'] = [{'id': x.get('book_id'), 'title':x.get('title')} for x in data.get('ret_data', [])]
            content = data.get('chapterData',{}).get('content', '')
            row['content_chars'] = len(content)
        if 'content' in d: row['content_chars'] = len(d['content'])
        return row
    except Exception as e:
        return {'key':k,'error':str(e)}

with concurrent.futures.ThreadPoolExecutor(6) as pool:
    print(json.dumps(list(pool.map(fetch, urls.items())), ensure_ascii=True, indent=2))
