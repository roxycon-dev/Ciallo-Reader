"""Verify the public reader image protocol against the maintained Keiyoushi adapter."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from io import BytesIO
from PIL import Image
import base64, json, requests

OUT = Path('.source-verification/latest-comics/hero-expanded')
STD = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'
CUSTOM = '_-9876543210abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'

def decode(data):
    encoded = data['images']
    assert encoded.startswith('qM9') and encoded.endswith('Z7')
    body = encoded[3:-2]
    tail_len = (len(body) - 5) // 3
    head_len = (len(body) - 5 - tail_len) // 2
    assert body[head_len:head_len+2] == 'Vx'
    assert body[-tail_len-3:-tail_len] == 'pL0'
    payload = body[-tail_len:] + body[:head_len] + body[head_len+2:-tail_len-3]
    payload = ''.join(payload[i:i+7][::-1] if (i//7)%2 else payload[i:i+7] for i in range(0,len(payload),7))
    plain = payload.translate(str.maketrans(CUSTOM, STD))
    images = json.loads(base64.urlsafe_b64decode(plain + '==='))
    size = len(images)
    decoy = data['order_id'] ^ (((data['sid'] * 0x9E3779B1) ^ (size * 0x85EBCA6B)) % size)
    if 0 <= decoy < size:
        images.pop(decoy)
    host = 'https://hip-tx-s1.s3imgs.top' if data['line'] == 9 else 'https://hip-tx-1.s3imgs.top'
    return [host + path for path in images]

def download(item):
    index, url = item
    try:
        r = requests.get(url, headers={'User-Agent': 'Mozilla/5.0'}, timeout=30)
        r.raise_for_status()
        im = Image.open(BytesIO(r.content)); im.load()
        target = OUT / '180-5-images' / f'{index+1:03}.jpg'
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(r.content)
        row = {'index':index,'url':url,'status':r.status_code,'bytes':len(r.content),'width':im.width,'height':im.height}
    except Exception as e:
        row = {'index':index,'url':url,'error':str(e)[:200]}
    print(json.dumps(row,ensure_ascii=False),flush=True)
    return row

if __name__ == '__main__':
    data=json.loads((OUT/'xipi-c-192938.json').read_text(encoding='utf8'))['data']
    images=decode(data)
    print('chapter',data['chapter_title'],'images',len(images),flush=True)
    (OUT/'180-5-image-urls.json').write_text(json.dumps(images,ensure_ascii=False,indent=2),encoding='utf8')
    with ThreadPoolExecutor(max_workers=3) as pool:
        rows=list(pool.map(download,enumerate(images)))
    (OUT/'180-5-image-audit.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf8')
    assert rows and all('error' not in row for row in rows)
