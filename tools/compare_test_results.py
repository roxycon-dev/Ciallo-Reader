"""对比当前分支与基线（af6e1b5）的单测结果，区分本次引入与既有失败。"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def summary(root):
    rows = {}
    for path in glob.glob(os.path.join(root, "*.xml")):
        tree = ET.parse(path)
        suite = tree.getroot()
        name = suite.get("name") or os.path.basename(path)
        rows[name] = {
            "tests": int(suite.get("tests", 0)),
            "failures": int(suite.get("failures", 0)),
            "errors": int(suite.get("errors", 0)),
            "skipped": int(suite.get("skipped", 0)),
        }
    return rows


current = summary(sys.argv[1])
baseline = summary(sys.argv[2])

cur_t = sum(v["tests"] for v in current.values())
cur_f = sum(v["failures"] + v["errors"] for v in current.values())
base_t = sum(v["tests"] for v in baseline.values())
base_f = sum(v["failures"] + v["errors"] for v in baseline.values())
print(f"当前分支: {cur_t} 项，失败 {cur_f}")
print(f"基线子集: {base_t} 项，失败 {base_f}\n")

print("当前分支有失败、基线未覆盖或通过：【需人工确认】")
for name, row in sorted(current.items()):
    if row["failures"] + row["errors"] == 0:
        continue
    base = baseline.get(name)
    tag = "基线未运行" if base is None else ("基线同样失败" if base["failures"] + base["errors"] else "基线通过")
    print(f"  {name:60s} 失败 {row['failures'] + row['errors']:>2}  {tag}")

print("\n当前分支全部失败类合计:", sum(1 for r in current.values() if r["failures"] + r["errors"]))
