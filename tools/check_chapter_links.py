"""检查排行页/目录页里的章节链接是否全部符合书源的章节正则。"""
import re
import sys

import lxml.html

CHAPTER = re.compile(r"/book/(\d{1,9})/([0-9a-f]{8,16})\.html")
ANY_CHAPTER = re.compile(r"/book/(\d{1,9})/([^/#?]+?)\.html")

for path in sys.argv[1:]:
    doc = lxml.html.fromstring(open(path, encoding="utf-8", errors="replace").read())
    hrefs = [str(h) for h in doc.xpath("//a/@href")]
    book_links = [h for h in hrefs if "/book/" in h]
    ok = [h for h in book_links if CHAPTER.search(h)]
    bad = [h for h in book_links if not CHAPTER.search(h)]
    print(f"{path}: /book/ 链接 {len(book_links)}，匹配 {len(ok)}，不匹配 {len(bad)}")
    for h in bad[:8]:
        m = ANY_CHAPTER.search(h)
        print("   不匹配样例:", h, "| 抓到的 hash:", m.group(2) if m else None)
