"""核对详情页声明的章节数与目录页条数是否一致（判断目录是否被截断）。"""
import re
import subprocess
import sys

import lxml.html

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
BASE = "https://alicesw4.homes"


def fetch(path):
    out = subprocess.run(["curl.exe", "-sS", "-L", "--max-time", "25", "-A", UA, BASE + path],
                         capture_output=True)
    return out.stdout.decode("utf-8", "replace")


for novel_id in sys.argv[1:]:
    detail = lxml.html.fromstring(fetch(f"/novel/{novel_id}.html"))
    rows = [re.sub(r"\s+", " ", p.text_content()).strip() for p in detail.xpath("//div[@class='novel_info']/p")]
    toc = lxml.html.fromstring(fetch(f"/other/chapters/id/{novel_id}.html"))
    count = len(toc.xpath("//ul[contains(@class,'mulu_list')]/li/a"))
    print(f"novel {novel_id}: 目录={count} | detail={rows}")
