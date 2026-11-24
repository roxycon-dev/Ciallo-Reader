# 1.6.7 · 2026-11-25（268）

修复 issue #11：番茄小说整本下载慢、正文格式被误判，并按请求公开官方签名密钥。

## 番茄整本下载：有限并发 + 断点保留

问题：约 1000 章的书要十几分钟，文件不到 1 MB。原来的下载循环是严格串行的，而且**每章之后
固定 `delay(200)`**——1000 章光睡眠就是 200 秒，加上 1000 次往返，全部时间都花在等网络上。

现在的模型（`GuestNovelEpub`）：

- **4 个固定 worker** 预取章节，与共享 OkHttp dispatcher 的 `maxRequestsPerHost = 4` 对齐；
  再多也只会排进 OkHttp 自己的队列，不会更快。
- **ZIP 写入仍然严格按目录顺序**：`chapter$index.xhtml`、`nav.xhtml`、`content.opf` 的 spine
  与串行版本完全一致，所以电子书排版、章节顺序、书名页码都不变。
- **窗口上限 8 章**：worker 只有在写入端消费并释放许可后才继续预取，3 万章的目录也不会把正文
  全堆在内存里。
- **每章校验通过立刻落断点**：暂停、切网或某章失败都不再丢弃已经验证过的正文；恢复时跳过它们，
  而且断点计数改成「连续前缀」语义，避免并发下虚报进度导致恢复位置错位。
- 旧的固定 200 ms 睡眠换成 0–40 ms 抖动，保留轻微的请求错峰。
- 章节顺序、目录二次核对（下载期间源站改目录则整体失败）、插图 sha256、CRC 校验等原有安全
  检查一条没删。

实测口径（`GuestNovelConcurrencyTest`，24 章 × 120 ms 模拟往返）：串行下限 2.88 s + 旧睡眠
4.8 s；现在必须在 2.4 s 内完成，且峰值并发 ≥2 且 ≤4、每章恰好请求一次、EPUB 逐章顺序正确。

## 番茄正文解析：不再把合法插图判成「不支持的格式」

问题：`番茄返回了不支持的正文格式`。原因是旧的 `parseParts` 只要发现 `<img>`（哪怕是正常
插图）就直接 `require` 失败，于是整章被判定不可下载。

现在的规则：

- 正常 HTML 正文（`<p>`、`<br>`、内联 `<span>/<strong>`、`<div>` 布局）都解析；
- **插图保留**：`src` / `data-src` / `data-original` 会解析成绝对地址（补 `//`、升级 `http→https`），
  作为 `NovelPart.Image` 进 EPUB；相对路径用 `https://fanqienovel.com/` 作为基准解析；
- 懒加载占位、`data:` URI、追踪像素这类拿不到真实地址的 `img` 直接丢弃，而不是让整章失败；
- 双重转义的正文（`&lt;p&gt;…`）会自动反转义，不再把标签当正文写进书里；
- **仍然拒绝**：私有区字形（`\uE000–\uF8FF`，字体混淆）、`\uFFFD` 乱码、`<canvas>/<svg>/<video>/<audio>`
  这类无法离线渲染的内容、`获取内容失败` / `请登录后阅读` / `购买本章` 这类占位页。

同时把「接口异常」和「格式不支持」分开：

- 缺少 `content`/`txt` 字段、空 payload、错误码 → `SourceException.NetworkError`，交给
  既有的有界重试策略（最多 5 次，只重试网络类错误）；
- 正文清洗后为空且像登录/购买页 → `ParseError`，明确提示需要登录或购买；
- 不会再出现「接口抖一下就被报成格式不支持」的误判。

兼容的响应外形：`{code,content}`、`{code,data:{content}}`、`{code,txt}`、
`data` 内嵌 `code` 的嵌套信封。

## 官方签名密钥公开

`signing/release-keystore/ciallo-reader-release.p12`（别名 `ciallo-reader-release`，3072 位
RSA，有效期至 2054-03-13）与明文口令已入库，配套说明见
[`signing/release-keystore/README.md`](../signing/release-keystore/README.md)。
证书指纹不变：`3ee5fb4018a00457eb24cbbfc43e78967d24069b760a5d386d6c9454b3770794`。

这样任何人都能：

1. 自行改 bug、抬 `versionCode`；
2. 用同一张证书 `assembleRelease`；
3. 直接覆盖安装到装有 v1.5.6–v1.6.6 的设备上，书架、阅读进度、设置全部保留。

代价与边界写在 [`docs/app-signing.md`](app-signing.md)：同签名包天然可互相覆盖，「官方版」
由 GitHub Release + `SHA256SUMS.txt` 认定。若要独立签名只能换包名另起发行渠道。

## 其它

- 版本号 267 → 268，`versionName` 1.6.6 → 1.6.7；同签名可覆盖 v1.6.6。
- 新增中英文词条：番茄正文接口不可用说明、需登录/购买说明、章节不完整说明。
- 新增/更新测试：`GuestNovelConcurrencyTest`（并发、顺序、暂停断点）、
  `FanqieGatewayTest`（插图、懒加载占位、双重转义、`br`/`div` 布局、缺失正文的异常分类）、
  `GuestNovelResumeTest`（断点、暂停、损坏记录、目录变化）。
- 下载/源实现相关 74 个单测全部通过。

## 已知边界

- 番茄正文仍来自第三方公开网关（`biek.top`），它缺章或停服时客户端无法补齐，只会明确报错；
- 并发度固定 4，不随网络类型自适应；如果网关限流，把 `CHAPTER_CONCURRENCY` 调到 2 即可；
- 插图下载仍在写入端串行（每张一次请求），一本插图很多的书，插图阶段会比正文阶段慢。
