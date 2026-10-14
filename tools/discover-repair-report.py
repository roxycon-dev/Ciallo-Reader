"""Summarize existing repair evidence and package; performs no network requests."""
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'artifacts/discover-repair'


def read_round(name):
    return json.loads((OUT / name / 'live-results.json').read_text(encoding='utf-8'))


def digest(data):
    return hashlib.sha256(data).hexdigest()


baseline = read_round('final-live')
rows = {r['key']: dict(r, evidenceFile='final-live/live-results.json') for r in baseline['sources']}
for key, name in [('hcomic', 'hcomic-ua-jm'), ('jm', 'jm-failover'), ('aimanhua', 'final-retry-aimanhua')]:
    row = next(r for r in read_round(name)['sources'] if r['key'] == key)
    rows[key] = dict(row, evidenceFile=name + '/live-results.json')
assert len(rows) == 20
assert sum(r['status'] == 'available' for r in rows.values()) == 18
assert rows['mycomic']['status'] == 'verification_required'
assert rows['aimanhua']['status'] == 'network_error'
for row in rows.values():
    current = ROOT / 'artifacts/discover-live/patched' / row['file']
    assert digest(current.read_bytes()) == row['sourceSha256'], 'Stale script evidence: ' + row['key']

tests = []
for path in sorted((ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')):
    suite = ET.parse(path).getroot()
    tests.append({k: suite.attrib[k] for k in ['name', 'tests', 'failures', 'errors', 'skipped']})
assert sum(int(t['tests']) for t in tests) == 50
assert all(int(t[k]) == 0 for t in tests for k in ['failures', 'errors', 'skipped'])
assert 'BUILD SUCCESSFUL' in (ROOT / 'discover-repair-delivery-build.log').read_text(encoding='utf-8')
apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
destination = OUT / 'Ciallo-Reader-discover-repair-2026-10-14-debug.apk'
shutil.copy2(apk, destination)
assets = {}
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    dex = b''.join(archive.read(name) for name in archive.namelist() if name.endswith('.dex'))
    compiled_markers = ['__cialloApiFailover', '__cialloUnavailable', 'R-18', '19r']
    assert all(marker.encode() in dex for marker in compiled_markers), 'APK lacks latest compiled repair markers'
    for path in [*(ROOT / 'app/src/main/assets/js_extra').glob('*.js'), ROOT / 'app/src/main/assets/venera/discover_bridge.js']:
        relative = path.relative_to(ROOT / 'app/src/main/assets').as_posix()
        assert archive.read('assets/' + relative) == path.read_bytes(), 'APK stale asset: ' + relative
        assets[relative] = digest(path.read_bytes())
metadata = json.loads((apk.parent / 'output-metadata.json').read_text(encoding='utf-8'))
receipt = {
    'generatedAt': datetime.now(timezone.utc).isoformat(), 'taskComplete': False,
    'scope': '20 App-offered sources; existing live evidence only; no new requests',
    'summary': {'recentSuccessfulSamples': 18, 'verificationBlocked': 1, 'latestNetworkBlocked': 1},
    'sources': list(rows.values()), 'tests': tests,
    'build': {'log': 'discover-repair-delivery-build.log', 'successful': True},
    'apk': {'file': destination.name, 'bytes': destination.stat().st_size, 'sha256': digest(destination.read_bytes()),
            'applicationId': metadata['applicationId'], 'variant': metadata['variantName'],
            'version': metadata['elements'][0]['versionName'], 'versionCode': metadata['elements'][0]['versionCode'],
            'zipCrcPassed': True, 'compiledRepairMarkers': compiled_markers, 'packagedAssets': assets},
    'boundaries': ['No emulator/device execution this round', 'No publication',
                   'No MYCOMIC challenge pass', 'Discovery/category samples do not prove all chapters/images readable'],
}
(OUT / 'verification.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
lines = [
    '# 发现书源修复审查 · 2026-10-14（未全部完成）', '',
    '范围按用户澄清收敛为书库当前提供的 **20 个漫画源**，不要求未配置的自建服务，不重新启用已排除源。', '',
    '**18 个源有本轮近期真实请求成功证据；MYCOMIC 仍被 Cloudflare 验证拦截，爱漫画最新请求持续断连。任务未完成。** 这是按时间保留的抽样结果，不是永久可用保证，也不是完整阅读链路验收。已通过源停止重复在线测试。', '',
    '## 已修复', '',
    '- WNACG：真实分页目录、桌面请求身份、移动/桌面卡片选择器及绝对封面地址。',
    '- 漫蛙吧：分类 URL 优先级和 JSON POST 请求头，保留作者。',
    '- H-Comic：请求身份更新；上游恒为空的排行榜明确标为不支持，避免伪造榜单。',
    '- Vomic：结构化解析 Next.js Flight/RSC 和 HTML 包装，支持引号标题、长封面地址；保留原始作者、日期、状态、阅读与评论数。',
    '- 爱漫画：接入真实 MCCMS 最新目录、原站题材及连载/完结分类与第二页；此前首页及第二页均返回 30 条新作品，最新远端连接失败仍未解除。',
    '- 嗶哩漫画：发现目录请求身份修复，首页及第二页各返回 50 条新作品。',
    '- 禁漫：只读 GET 在发布方已知线路间有限重试；验证解密 JSON 后再记住线路，401 不切线，写入请求保持原逻辑。离线契约覆盖错误正文、坏解密、5 路上限、18 秒重试预算、非本站 URL 不切线。',
    '- Goda：生产传输启用已有 Cronet HTTP/2 路径。Picacg 使用用户授权凭据真实登录成功，未保存凭据或令牌到报告/源码。',
    '- 缓存脚本补丁升级至 41；旧缓存同样应用修复。Comic 桥仅保留明确命名的统计/作者元数据，不推测不存在的数值；成人分类补齐 19r/R-18 等别名并隔离作者/角色标签。',
    '- MYCOMIC：书源管理新增“验证”，打开实际目录并同步浏览器身份与会话；流程已构建，真实过盾尚未验证成功。', '',
    '## 逐源证据', '',
    '下表取最终全量轮次，加上仅针对失败源的修复后复验。没有用爱漫画的较早成功覆盖最新失败。时间为北京时间；“—”表示未声明该维度，不用推荐列表替代统计。', '',
    '| 书源 | 原站推荐 | 更新 | 榜单 | 点击 | 分类 | 最新记录（北京时间） | 结论 |',
    '| --- | ---: | ---: | ---: | ---: | ---: | --- | --- |',
]
for row in rows.values():
    stages = {s['name']: s for s in row['stages']}
    values = [str(stages[n]['count']) if n in stages and stages[n]['status'] == 'available' else '—' for n in ['native', 'updates', 'ranking', 'clicks', 'category']]
    local = datetime.fromisoformat(row['finishedAt'].replace('Z', '+00:00')).astimezone(__import__('zoneinfo').ZoneInfo('Asia/Shanghai')).strftime('%m-%d %H:%M:%S')
    status = {'available': '抽样通过', 'verification_required': '验证拦截，未修至可用', 'network_error': '远端断连，未修至可用'}[row['status']]
    lines.append('| ' + ' | '.join([row['name'] + ' / `' + row['key'] + '`', *values, local, status]) + ' |')
lines.extend([
    '', '所有源均没有可验证的原生热议列表声明；缺失数据如实提示，不合成评论/讨论度。分类仅抽样一个原站分类，不把少量抽样宣称为全部分类验收。', '',
    '第二页新 ID 证据见 `artifacts/discover-repair/pagination-unique/live-results.json`；最新 H-Comic 分页见 `hcomic-ua-jm/`，Vomic 结构化解析见 `vomic-json/`。', '',
    '## 尚未解除的阻断', '',
    '- MYCOMIC：站点首页、目录、榜单均返回 Cloudflare 403。已修好 App 内验证入口；桌面浏览器控制不可用，未获取经过实际站点验证的会话，不能宣称已修复至可用。官方扩展未提供已验证的镜像。',
    '- 爱漫画：较早真实目录分页通过；最新 HTTPS 首页、目录、章节 API 均连接重置，HTTP 诊断为 502 空响应。没有足够证据断言永久停站，也不能把历史成功当作当前可用。详见 `aimanhua-availability.json` 和 `final-retry-aimanhua/`。', '',
    '## 校验和验证包', '',
    f'- 最终 Debug 构建成功，50 项 JVM/Compose/JS DOM 相关回归全部通过，无失败、错误或跳过。只读主机协议及禁漫切线离线契约通过，未重复请求健康源。',
    f'- 验证包：`artifacts/discover-repair/{destination.name}`，{destination.stat().st_size:,} B，1.3.6 / 217；SHA256 `{receipt["apk"]["sha256"]}`。ZIP CRC 与本地源资产逐字节一致。',
    '- 本包为当前共享工作区 Debug 验证包，包含其他已有改动；未发布、未安装、未启动模拟器。本轮没有验证完整阅读、真机 GPU 表现或全部分类。',
    '- 结构化回执：`artifacts/discover-repair/verification.json`；最终构建日志：`discover-repair-delivery-build.log`。', '',
    '## 旧报告勘误', '',
    '初版桌面 DOM 主机混用了 document/element 数字 ID，导致多次解析后的空结果/错误；另一次默认 User-Agent 处理大小写不当，覆盖了源提供的请求身份。修正并重新验证后，旧报告的 hitomi/ehentai/mxs 等解析或空流故障结论撤回，Picacg 原认证失败不算源协议故障。旧日志保留作为历史，不作当前结论。', '',
    '## 参考实现', '',
    '- [Venera 官方配置](https://github.com/venera-app/venera-configs)：沿用原站契约及线路。',
    '- [Keiyoushi MCCMS](https://github.com/keiyoushi/extensions-source/blob/main/lib-multisrc/mccms/src/eu/kanade/tachiyomi/multisrc/mccms/MCCMSWeb.kt)：目录分页与 tags/finish 路由。',
    '- [Keiyoushi MYCOMIC](https://github.com/keiyoushi/extensions-source/tree/main/src/zh/mycomic)：目录及官方域名依据。', '',
])
(ROOT / 'docs/discover-repair-2026-10-14.md').write_text('\n'.join(lines), encoding='utf-8')
print(json.dumps({'taskComplete': False, 'sources': 20, 'successfulSamples': 18, 'testsPassed': 50,
                  'apkSha256': receipt['apk']['sha256'], 'bytes': destination.stat().st_size}))
