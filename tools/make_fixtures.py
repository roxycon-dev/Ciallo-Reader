"""把抓到的真实页面整理成离线测试快照：正文文字替换为中性占位，保留 DOM 结构。"""
import os
import re
import shutil

import lxml.html

OUT = "ciallo/app/src/test/resources/alicesw"
os.makedirs(OUT, exist_ok=True)

PAIRS = {
    "alice_search.html": "search.html",
    "alice_detail.html": "detail.html",
    "alice_toc.html": "toc.html",
    "q_https___www_alicesw1_quest_.html": "linepage.html",
}
for src, dst in PAIRS.items():
    shutil.copyfile(src, os.path.join(OUT, dst))
    print(f"{src} -> {dst} ({os.path.getsize(os.path.join(OUT, dst))} bytes)")

# 正文页：保留容器/段落数量，文字换成中性内容。
html = open("alice_ch1.html", encoding="utf-8", errors="replace").read()
doc = lxml.html.fromstring(html)
body = doc.xpath("//div[contains(@class,'read-content')]")[0]
paras = body.xpath("./p")
for i, p in enumerate(paras, 1):
    for child in list(p):
        p.remove(child)
    p.text = f"示例正文第{i}段。"
doc.xpath("//div[contains(@class,'read-content')]")[0].set("data-fixture", "neutralized")
with open(os.path.join(OUT, "chapter.html"), "w", encoding="utf-8") as f:
    f.write(lxml.html.tostring(doc, encoding="unicode"))
print(f"chapter.html 段落={len(paras)} -> {os.path.getsize(os.path.join(OUT,'chapter.html'))} bytes")

# 章节不存在页（真实 404 提示页），用于测解析错误分支
shutil.copyfile("alice_chap.html", os.path.join(OUT, "missing_chapter.html"))
print("missing_chapter.html", os.path.getsize(os.path.join(OUT, "missing_chapter.html")))

for name in sorted(os.listdir(OUT)):
    print("  fixture:", name, os.path.getsize(os.path.join(OUT, name)))
