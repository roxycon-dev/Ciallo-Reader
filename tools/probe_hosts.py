"""并发探测候选域名的 HTTPS 可达性（判断是否被 SNI 阻断）。"""
import concurrent.futures as cf
import ssl
import socket
import sys

UA_HOSTS = [f"www.alicesw{i}.homes" for i in range(1, 12)] + [
    "www.alicesw.net", "www.alicesw.cc", "www.alicesw.top", "www.alicesw.xyz",
    "www.alicesw.vip", "www.alicesw.ltd", "www.alicesw.icu", "www.alicesw.best",
    "www.alicesw.site", "www.alicesw.online", "www.alicesw.live", "www.alicesw.pro",
    "www.alicesw.club", "www.alicesw.love", "www.alicesw.cn", "www.alicesw.tw",
    "www.alicesw.com", "www.alicesw1.quest", "alicesw.click", "alicesw1.quest",
]


def probe(host):
    try:
        ip = socket.gethostbyname(host)
    except Exception as exc:  # noqa: BLE001
        return host, "DNS-FAIL", str(exc)[:40]
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        with socket.create_connection((ip, 443), timeout=8) as raw:
            with ctx.wrap_socket(raw, server_hostname=host) as tls:
                return host, "TLS-OK", ip
    except Exception as exc:  # noqa: BLE001
        return host, "TLS-FAIL", f"{ip} {type(exc).__name__}"


if __name__ == "__main__":
    hosts = sys.argv[1:] or UA_HOSTS
    with cf.ThreadPoolExecutor(max_workers=16) as pool:
        rows = list(pool.map(probe, hosts))
    for host, status, detail in sorted(rows, key=lambda r: (r[1], r[0])):
        print(f"{status:10s} {host:32s} {detail}")
