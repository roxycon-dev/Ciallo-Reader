"""Merge live retries and render an evidence-based source matrix; never turn HTTP 200 into a pass."""
from collections import Counter
from datetime import datetime
import hashlib
import json
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'artifacts/discover-live'
LABELS = {'available': '返回作品', 'partially_available': '部分成功、部分报错', 'empty': '空结果',
    'unsupported': '未提供入口', 'no_discovery_data': '无可用发现结果', 'login_or_key_required': '需要登录/凭据',
    'http_403': 'HTTP 403 拒绝访问', 'http_404': 'HTTP 404', 'http_4xx': 'HTTP 参数/认证错误',
    'http_5xx': '服务端错误', 'network_error': 'TLS/网络失败', 'timeout': '请求超时',
    'verification_required': '触发站点防护', 'self_host_configuration_required': '待配置自建服务器',
    'parse_or_api_error': '解析/接口错误', 'host_unsupported': '校验器能力不足', 'audit_budget_exceeded': '校验预算限制'}


def load(file):
    return json.loads(file.read_text(encoding='utf-8'))


def cell(row, name):
    stage = next((s for s in row['stages'] if s['name'] == name), None)
    if stage is None:
        return '—'
    if stage['status'] in ('available', 'empty'):
        return str(stage.get('count', 0))
    return LABELS.get(stage['status'], stage['status'])


def main():
    baseline = OUT / 'round-2-results.json'
    if not baseline.exists():
        baseline.write_bytes((OUT / 'live-results.json').read_bytes())
    report = load(baseline)
    by_file = {r['file']: r for r in report['sources']}
    attempts = {r['file']: [r] for r in report['sources']}
    first_path = OUT / 'round-1-results.json'
    if first_path.exists():
        for row in load(first_path)['sources']:
            if row['file'] in attempts:
                attempts[row['file']].insert(0, row)
    retry_path = OUT / 'retry/live-results.json'
    if retry_path.exists():
        retry = load(retry_path)
        report['provenance'] = retry.get('provenance', {})
        for row in retry['sources']:
            attempts[row['file']].append(row)
            by_file[row['file']] = row
        report['finishedAt'] = retry['finishedAt']
    profiles = load(OUT / 'patched/index.json')
    sources = [by_file[p['fileName']] for p in profiles]
    for row in sources:
        row['attempts'] = [{'startedAt': r['startedAt'], 'status': r['status'], 'stages': r['stages'], 'http': r['http']} for r in attempts[row['file']]]
        row['name'] = next(p['name'] for p in profiles if p['fileName'] == row['file'])
        row['sourceSha256'] = hashlib.sha256((OUT / 'patched' / row['file']).read_bytes()).hexdigest()
    report['sources'] = sources
    report['totals'] = dict(Counter(s['status'] for s in sources))
    report['uniqueSourceKeys'] = len({s['key'] for s in sources})
    report['profilesWithEntries'] = sum(any(s.get('count', 0) > 0 for s in r['stages']) for r in sources)
    report['uniqueKeysWithEntries'] = len({r['key'] for r in sources if any(s.get('count', 0) > 0 for s in r['stages'])})
    offered = [r for r in sources if r['offered']]
    offered_with_entries = sum(any(s.get('count', 0) > 0 for s in r['stages']) for r in offered)
    report['preparation'] = load(OUT / 'preparation.json')
    (OUT / 'live-results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    def stamp(value):
        return datetime.fromisoformat(value.replace('Z', '+00:00')).astimezone(ZoneInfo('Asia/Shanghai')).strftime('%Y-%m-%d %H:%M:%S')
    content = [
        '# 发现书源实时在线验证 · 2026-10-13', '',
        f"执行时间：{stamp(report['startedAt'])}–{stamp(report['finishedAt'])}（北京时间）。本轮没有启动模拟器。", '',
        '范围：当次从 Venera 官方索引取得的 33 份配置、4 个本地附加源、1 个原生 MangaDex API 对应探测；共 **38 份配置 / 37 个源 key**。拷贝漫画的两份配置使用同一个 key。已排除的源也做探测，但没有改变 App 排除政策。未读取设备内另行导入的私人配置或账号。', '',
        f"**{report['profilesWithEntries']} 份配置 / {report['uniqueKeysWithEntries']} 个源至少返回一个非空作品列表或分类结果。** 这是单项返回成功，不能推导所有维度、所有分类、详情和漫画图片均可读。", '',
        f"App 当前提供的 {len(offered)} 个源中，{offered_with_entries} 个至少返回一项作品数据。其余为 picacg 登录前置检查、wnacg/hitomi 解析失败、MYCOMIC 防护、爱漫画缺少发现入口。当前发现目录未识别到可执行的热议入口，不能用榜单数据替代讨论度。", '',
        '方法：运行生产 Kotlin patchScript 导出的真实脚本、App 的同步 JS 桥与发现协议；网络层为桌面 Python Requests，DOM 为 Cheerio，全部响应来自真实 HTTP。匿名默认设置，不使用固定响应或已有账号 Cookie。原生 MangaDex 使用与 Kotlin 实现对应的更新/榜单 API 请求，抽样 5 项；没有宣称运行了 Android Kotlin/Cronet/WebView。', '',
        '数字为本次第一页解析并通过 id/title 检查的作品数量。分类仅抽样一个原站分类；“—”表示未声明或被前序访问失败阻断，0 表示真实执行后无作品。推荐数可能包含多个原站区块，不能与单页大小直接比较。HTTP 200 和空分类均不自动算成功。', '',
        '| 书源 / key | App 当前提供 | 原站推荐 | 更新 | 热议 | 榜单 | 点击 | 分类抽样 | 本次结论 | HTTP 表现 |',
        '| --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |',
    ]
    for row in sources:
        codes = Counter(str(h.get('status', '网络错误')) for h in row['http'])
        code_text = ', '.join(f'{code}×{count}' for code, count in sorted(codes.items())) or '无需 HTTP / 未配置'
        if row['status'] == 'self_host_configuration_required':
            code_text = '服务器未配置；初始化资源不计可用'
        elif row['status'] == 'login_or_key_required' and not row['http']:
            code_text = '脚本登录前置检查；此前请求 401'
        label = LABELS.get(row['status'], row['status'])
        if row.get('siteOnly') and row.get('siteReachable'):
            label = '首页在线；没有发现接口'
        content.append('| ' + ' | '.join([row['name'] + ' / `' + row['key'] + '`', '是' if row['offered'] else '否',
            *[cell(row, name) for name in ['native', 'updates', 'discussion', 'ranking', 'clicks', 'category']], label, code_text]) + ' |')
    content.extend(['', '## 需要优先处理的发现问题', '',
        '- **漫画蛙吧**：推荐返回 24 本，分类请求返回 415；当前分类链路可复现失败，应修正请求协议后回归。',
        '- **hitomi / 绅士漫画 wnacg**：真实端点返回 200/206，但脚本取 DOM 字段报空指针或解析为空。属于解析兼容问题候选，不能当作站点离线；应核对当前页面结构、跳转域名与选择器。',
        '- **嗶哩、vomic、ehentai、H-Comic**：部分命名为更新/点击/榜单的入口返回 0。vomic 分类返回 12 本，而推荐与更新为 0；嗶哩推荐返回 50 本，而更新和点击为 0。应分别检查原站分区、范围选择和桥接语义，暂不能宣称这些维度可用。',
        '- **GoDa、漫小肆、jcomic**：推荐有作品，分类抽样为 0；一次空分类不足以证明整套分类失效，需要扩大分类抽样并对照原站。',
        '- **爱漫画**：首页 200，当前脚本没有 explore/category 定义；需补原站目录入口才能在发现中正常浏览。',
        '- **优酷漫画 ykmh**：推荐有作品，分类失败 `temp is not defined`，属于脚本变量错误；该源当前已排除。',
        '- **MangaDex**：原生 API 探测返回更新与榜单；重复的 JS 配置经历 TLS 失败后重试 HTTP 200，但解析 0 项。其标题逻辑匹配 `zh_CN/zh_TW`，App 返回 `zh-CN`，有 locale 兼容缺陷，不能据此判断 MangaDex 离线。',
        '- **漫画1234**：本次 200 页面没有解析出作品，分类取空字段；需检查返回页面是否仍为漫画站和现有 DOM 契约。', '',
        '## 访问限制与复核边界', '',
        '- picacg：匿名脚本直接提示 Not logged in；首轮真实请求也收到 401。没有提供账号，保持“需登录”，不判站点宕机。',
        '- MYCOMIC、18漫画：防护页面；包子、少年 Jump+、ComicWalker：403/认证接口异常。桌面没有 App 的 WebView 过盾与 Cronet 网络特征，结果仅描述本次客户端访问。',
        '- 嗨皮漫画：TLS EOF 重试仍失败；漫画柜：读取超时重试仍失败。需要换时间或网络复核，不作为永久下架依据。',
        '- 爱看漫：首轮网络/入口失败，重试推荐返回 12 本、分类返回 48 本，说明可用性具有波动；没有因此恢复已禁用源。',
        '- Lanraragi、Komga、Kavita 是自建服务，没有用户服务器地址/账号，不能做对应实例的在线验证。',
        '- 没有验证账号登录、付费章、作品详情、图片下载、每个分类和无限分页；本次范围是发现列表/原站分类首页及无发现入口源的首页响应。', '',
        '## 复跑与证据', '',
        '准备：`python tools/discover-live-prepare.py`，需要 JAVA_HOME 指向 JBR、项目 Kotlin 2.0.21 编译器缓存。`--offline` 仅复用已下载配置，HTTP 校验仍实时执行。', '',
        '临时目录安装 `cheerio@1.0.0 iconv-lite@0.6.3`，然后执行：', '',
        '```text', 'node tools/discover-live-audit.cjs artifacts/discover-live/patched <临时 node_modules 绝对路径>',
        'python tools/discover-live-report.py', '```', '',
        '可用环境变量 `DISCOVER_LIVE_KEYS` 限定源 key、`DISCOVER_LIVE_OUT` 指定独立重试目录。报告生成器保留 round-2 基准并合并 retry 目录，重复生成不会改变已记录时间。', '',
        '结构化证据：`artifacts/discover-live/live-results.json`；含每阶段数量/错误、实际 URL、HTTP 状态、响应长度及 SHA-256、起止时间和全部完成轮次的重试记录，不保存响应正文或账号信息。第一轮保留 `round-1-results.json`，第二轮保留 `round-2-results.json`。准备与桥接版本指纹用于区分脚本版本变化。额外 DOM 抽样见 `parser-diagnostics.json`：wnacg 当前为移动版结构，hitomi 同一图库端点随后出现 404，漫画1234 再次超时；这些波动没有覆盖原始证据。', '',
        '校验器修正记录：APP.locale 同步返回、CBC 填充、结构化 POST 的 JSON 编码、响应 UTF-8 解码、请求阶段归属，以及匿名认证失败循环的重复请求上限。初始校验器错误没有记为最终站点故障。', '',
        '官方配置索引：[venera-configs/index.json](https://raw.githubusercontent.com/venera-app/venera-configs/main/index.json)。', '',
    ])
    target = ROOT / 'docs/discover-live-2026-10-13.md'
    target.write_text('\n'.join(content), encoding='utf-8')
    print(json.dumps(dict(profiles=report['profiles'], uniqueKeys=report['uniqueSourceKeys'], withEntries=report['profilesWithEntries'], totals=report['totals']), ensure_ascii=False))


if __name__ == '__main__':
    main()
