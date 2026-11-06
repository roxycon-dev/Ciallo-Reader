"""github.com:443 不可达时，用 Git Data API 把本地提交推到远端 main。

按本地提交的 tree/author/committer/message 原样重建，目标是远端得到同一个 commit sha。
"""
import base64
import json
import subprocess
import urllib.error
import urllib.request

OWNER, REPO = "roxycon-dev", "Ciallo-Reader"


def git(*args, binary=False):
    out = subprocess.run(["git", *args], capture_output=True)
    if out.returncode != 0:
        raise SystemExit(out.stderr.decode("utf-8", "replace"))
    return out.stdout if binary else out.stdout.decode("utf-8", "replace").strip()


def token():
    out = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n",
                         capture_output=True, text=True)
    for line in out.stdout.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1]
    raise SystemExit("没有可用的 GitHub 凭据")


TOKEN = token()


def api(method, path, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(
        f"https://api.github.com/repos/{OWNER}/{REPO}{path}", data=data, method=method,
        headers={"Authorization": f"Bearer {TOKEN}", "Accept": "application/vnd.github+json",
                 "User-Agent": "ciallo-push", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as exc:
        raise SystemExit(f"{method} {path} -> {exc.code}: {exc.read().decode()[:500]}")


head = git("rev-parse", "HEAD")
parent = git("rev-parse", "HEAD^")
tree = git("rev-parse", "HEAD^{tree}")
message = git("log", "-1", "--format=%B")
author_name = git("log", "-1", "--format=%an")
author_email = git("log", "-1", "--format=%ae")
author_date = git("log", "-1", "--format=%aI")
committer_name = git("log", "-1", "--format=%cn")
committer_email = git("log", "-1", "--format=%ce")
committer_date = git("log", "-1", "--format=%cI")
print("本地 HEAD:", head, "父提交:", parent, "tree:", tree)

ref = api("GET", "/git/ref/heads/main")
remote = ref["object"]["sha"]
print("远端 main:", remote)
if remote != parent:
    raise SystemExit(f"远端已变化（{remote}），先同步再推")
if remote == head:
    raise SystemExit("远端已包含该提交")

status = git("diff", "--name-status", parent, head).splitlines()
changes = []
for line in status:
    parts = line.split("\t")
    changes.append((parts[0][0], parts[-1]))
print("变更文件数:", len(changes))

entries = []
for kind, path in changes:
    if kind == "D":
        entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
        continue
    blob = git("cat-file", "blob", f"HEAD:{path}", binary=True)
    sha = api("POST", "/git/blobs", {"content": base64.b64encode(blob).decode(), "encoding": "base64"})["sha"]
    entries.append({"path": path, "mode": "100644", "type": "blob", "sha": sha})
    print("  blob", path, "->", sha[:10])

new_tree = api("POST", "/git/trees", {"base_tree": api("GET", f"/git/commits/{remote}")["tree"]["sha"], "tree": entries})
print("新 tree:", new_tree["sha"], "(本地一致)" if new_tree["sha"] == tree else "(与本地不同!)")

commit = api("POST", "/git/commits", {
    "message": message,
    "tree": new_tree["sha"],
    "parents": [remote],
    "author": {"name": author_name, "email": author_email, "date": author_date},
    "committer": {"name": committer_name, "email": committer_email, "date": committer_date},
})
print("新提交:", commit["sha"], "(与本地一致)" if commit["sha"] == head else "(与本地不同)")

api("PATCH", "/git/refs/heads/main", {"sha": commit["sha"], "force": False})
print("已更新 main ->", commit["sha"])
