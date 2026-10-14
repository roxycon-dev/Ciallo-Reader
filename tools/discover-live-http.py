"""JSONL HTTP transport for a live, anonymous discovery audit; no Android/device required."""
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
import re
import sys
import threading
import time
from urllib.parse import urlsplit, urlunsplit, parse_qsl, urlencode
import requests
import urllib3
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
from pathlib import Path

backend = os.environ.get('DISCOVER_LIVE_HTTP_BACKEND', 'requests')
if backend in ('curl', 'auto'):
    from curl_cffi import requests as curl_requests
    from curl_cffi import CurlHttpVersion

sessions = {}
lock = threading.Lock()
output_lock = threading.Lock()

def safe_url(url):
    p = urlsplit(url)
    query = [(k, '[redacted]' if re.search('token|pass|auth|secret|key|session', k, re.I) else v) for k, v in parse_qsl(p.query, keep_blank_values=True)]
    return urlunsplit((p.scheme, p.hostname + ((':' + str(p.port)) if p.port else ''), p.path, urlencode(query), ''))

def perform(message):
    started = time.monotonic()
    result = {'id': message['id']}
    try:
        key = message['source']
        with lock:
            actual_backend = 'curl' if backend == 'curl' or (backend == 'auto' and key in ('mycomic.js', 'bilimanga.js', 'goda.js')) else 'requests'
            if key not in sessions:
                sessions[key] = curl_requests.Session(impersonate='chrome131') if actual_backend == 'curl' else requests.Session()
            session = sessions[key]
        if message.get('action') == 'cookies':
            jar = getattr(session.cookies, 'jar', session.cookies)
            result['cookies'] = [{'name': c.name, 'value': c.value, 'domain': c.domain} for c in jar]
        else:
            method = message.get('method', 'GET').upper()
            url = message['url']
            if method not in ('GET', 'HEAD', 'POST'):
                raise ValueError('Audit blocks modifying HTTP methods')
            data = message.get('data')
            if method == 'POST' and re.search(r'\bmutation\b', str(data), re.I):
                raise ValueError('Audit blocks GraphQL mutations')
            headers = {str(k): str(v) for k, v in (message.get('headers') or {}).items() if v is not None}
            if not any(k.lower() == 'user-agent' for k in headers):
                headers['User-Agent'] = 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.5359.128 Mobile Safari/537.36'
            if not any(k.lower() == 'accept' for k in headers):
                headers['Accept'] = 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8'
            if not any(k.lower() == 'accept-language' for k in headers):
                headers['Accept-Language'] = 'zh-CN,zh;q=0.9,en;q=0.8'
            if isinstance(data, (dict, list)):
                # JsMessageHandler encodes every structured payload as JSON.
                if not any(k.lower() == 'content-type' for k in headers):
                    headers['Content-Type'] = 'application/json; charset=utf-8'
                data = json.dumps(data, ensure_ascii=False)
            options = {'impersonate': 'firefox144'} if actual_backend == 'curl' and any('Firefox/144' in v for k, v in headers.items() if k.lower() == 'user-agent') else {}
            if actual_backend == 'curl' and key.replace('.js', '') not in ('mycomic', 'nhentai', 'pufei', 'bilimanga', 'goda', 'comick'):
                options['http_version'] = CurlHttpVersion.V1_1
            pinned = key == 'aimanhua.js' and urlsplit(url).hostname == 'mh.iqtao.cn'
            r = session.request(method, url, headers=headers, data=data, timeout=(10, 18), stream=True, **({'verify':False} if pinned else {}), **options)
            if pinned:
                from cryptography import x509
                from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
                import datetime
                cert = x509.load_der_x509_certificate(r.raw.connection.sock.getpeercert(binary_form=True))
                digest = hashlib.sha256(cert.public_key().public_bytes(Encoding.DER,PublicFormat.SubjectPublicKeyInfo)).hexdigest()
                if digest != '84f816f39fb638fe06c65f5489ef9be3ed93ab195a56ff1ba014dfe000869729' or datetime.datetime.now(datetime.timezone.utc) > cert.not_valid_after_utc + datetime.timedelta(days=14):
                    raise ValueError('Publisher TLS key/grace verification failed')
                names=cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value.get_values_for_type(x509.DNSName)
                if not ('mh.iqtao.cn' in names or '*.iqtao.cn' in names): raise ValueError('Publisher hostname mismatch')
                (Path(__file__).resolve().parents[1]/'artifacts/discover-live/aimanhua-publisher.pem').write_bytes(cert.public_bytes(Encoding.PEM))

            payload = bytearray()
            for chunk in r.iter_content(65536):
                payload.extend(chunk)
                if len(payload) > 12 * 1024 * 1024:
                    raise ValueError('Response exceeds audit 12 MiB limit')
            r.close()
            # The App decodes ordinary responses as UTF-8; GBK sources use fetchBytes.
            text = payload.decode('utf-8', errors='replace')
            result.update(status=r.status_code, url=r.url, headers=dict(r.headers), body=text,
                bytes=base64.b64encode(payload).decode('ascii'),
                evidence={'url': safe_url(r.url), 'status': r.status_code, 'length': len(payload),
                    'sha256': hashlib.sha256(payload).hexdigest(),
                    'responseDate': r.headers.get('Date'),
                    'backend': actual_backend,
                    'challenge': bool(re.search(r'cf-chl-|cf-turnstile|challenge-platform|Just a moment|Attention Required', text, re.I)),
                    'title': (re.search(r'<title[^>]*>(.*?)</title>', text, re.I | re.S).group(1)[:120] if re.search(r'<title[^>]*>(.*?)</title>', text, re.I | re.S) else None)})
    except Exception as e:
        result['error'] = type(e).__name__ + ': ' + str(e)[:280]
    result['ms'] = round((time.monotonic() - started) * 1000)
    with output_lock:
        print(json.dumps(result, ensure_ascii=True), flush=True)

with ThreadPoolExecutor(max_workers=6) as pool:
    for line in sys.stdin:
        if line.strip():
            pool.submit(perform, json.loads(line))
