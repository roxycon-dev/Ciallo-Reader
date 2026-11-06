"""比对多个已发布版本的 APK 签名证书；同时看官方固定指纹。"""
import json
import os
import subprocess
import urllib.request

TOKEN = ""
r = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n",
                   capture_output=True, text=True)
for line in r.stdout.splitlines():
    if line.startswith("password="):
        TOKEN = line.split("=", 1)[1]

EXPECTED = open(os.path.join("ciallo", "signing", "release-cert.sha256")).read().strip()
print("官方固定证书指纹:", EXPECTED)
print("证书指纹=SHA-256(subject DER)。下面用 apksigner 打印的证书摘要仅作版本间一致性比对。\n")

APKSIGNER = r"C:\asdk\build-tools\35.0.0\apksigner.bat"
env = dict(os.environ, JAVA_HOME=r"C:\Users\lenovo\AppData\Roaming\AutoClaw-official\accounts\88230b3119d9edb1e2cde3e1baf750efb06e02d2c005fe564b46f69c1439ad53\projects\Default\android-build\jdk-17.0.20.1+1")
OUT = os.path.join("ciallo", "release-audit")
os.makedirs(OUT, exist_ok=True)


def api(path):
    req = urllib.request.Request("https://api.github.com" + path, headers={
        "Authorization": f"Bearer {TOKEN}", "Accept": "application/vnd.github+json",
        "User-Agent": "ciallo-audit"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode())


for tag in ("v1.5.14", "v1.5.13", "v1.5.12", "v1.5.11"):
    try:
        rel = api(f"/repos/roxycon-dev/Ciallo-Reader/releases/tags/{tag}")
    except Exception as exc:  # noqa: BLE001
        print(tag, "取发布失败:", exc)
        continue
    apks = [a for a in rel.get("assets", []) if a["name"].endswith(".apk")]
    if not apks:
        print(tag, "无 APK 附件:", [a["name"] for a in rel.get("assets", [])])
        continue
    asset = apks[0]
    target = os.path.join(OUT, f"{tag}.apk")
    if not os.path.exists(target):
        req = urllib.request.Request(asset["browser_download_url"], headers={"User-Agent": "ciallo-audit"})
        with urllib.request.urlopen(req, timeout=300) as resp, open(target, "wb") as f:
            f.write(resp.read())
    out = subprocess.run([APKSIGNER, "verify", "--print-certs", target],
                         capture_output=True, text=True, env=env)
    digest = ""
    subject = ""
    for line in out.stdout.splitlines():
        if "SHA-256 digest" in line:
            digest = line.split(":", 1)[1].strip()
        if "certificate DN" in line:
            subject = line.split(":", 1)[1].strip()
    print(f"{tag}  {asset['name']:24s} {asset['size']/1e6:6.1f}MB  DN={subject}")
    print(f"        证书 SHA-256 = {digest}")
