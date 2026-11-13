import os, re, collections

root = r"C:\Users\GuanXingRen\Downloads\novel-reader (1)\novel-reader\app\src\main\java"
prefs_path = os.path.join(root, r"com\example\data\PreferencesManager.kt")
settings_path = os.path.join(root, r"com\example\ui\SettingsTabScreen.kt")

s = open(prefs_path, encoding="utf-8").read()
keys = sorted(set(re.findall(r"var\s+([A-Za-z0-9_]+)\s*:", s)))

usage = collections.defaultdict(list)
for dirpath, _, files in os.walk(root):
    for f in files:
        if not f.endswith(".kt"):
            continue
        p = os.path.join(dirpath, f)
        if p in (prefs_path, settings_path):
            continue
        try:
            t = open(p, encoding="utf-8").read()
        except Exception:
            continue
        for k in keys:
            # 只认 prefs.key 或 .key 的读取形态，避免误报
            if re.search(r"\.%s\b" % re.escape(k), t):
                rel = os.path.relpath(p, root).replace("\\", "/")
                usage[k].append(rel)

print("keys", len(keys))
dead = [k for k in keys if not usage[k]]
print("\n== 无任何读取点（除设置页/PreferencesManager）==")
for k in dead:
    print(" ", k)

print("\n== 读取点 <= 1 ==")
for k in keys:
    if 0 < len(usage[k]) <= 1:
        print(" ", k, "->", usage[k])
