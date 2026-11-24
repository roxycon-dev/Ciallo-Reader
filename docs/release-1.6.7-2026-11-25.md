对应 issue #11。这一版修番茄整本下载的两个真 bug，并把官方签名密钥公开——这样你自己改完能编出**同签名**的包，直接覆盖安装，书架与设置都不丢。

## 下载

- `Ciallo-Reader-v1.6.7.apk`（31,437,910 字节，arm64-v8a，Android 7.0+）
- `SHA256SUMS.txt`

```
SHA-256  370064034085013614483336aa1e34a6569c0053ce67df437d23be560bd12e91
证书     SHA-256 3ee5fb4018a00457eb24cbbfc43e78967d24069b760a5d386d6c9454b3770794
包名     com.aistudio.novelreader.kxmpzq (268 / 1.6.7, debuggable=false)
```

同签名，可直接覆盖 v1.5.6–v1.6.6。从 1.5.6 之前的老版本升级仍需备份 → 卸载 → 安装 → 导入。

## 1. 番茄整本下载慢

现象：1000 章的书、不到 1 MB，要十几分钟。

原因不是网络慢，是代码里两条硬伤：章节目录是**严格串行**请求的，而且**每章之后固定 `delay(200)`**。1000 章等于 1000 次往返 + 200 秒纯睡眠，时间全花在等自己。

改法：

- 4 个固定 worker 预取章节，正好对齐共享 OkHttp dispatcher 的 `maxRequestsPerHost = 4`；调更高只会排进 OkHttp 自己的队列，不会更快。
- **ZIP 写入仍严格按目录顺序**。`chapter$index.xhtml`、`nav.xhtml`、`content.opf` 的 spine 和串行版一致，电子书排版、章节顺序、页码都不变——快的只是等待。
- 内存窗口 8 章。worker 必须等写入端消费并释放许可才继续预取，3 万章目录也不会把正文堆进内存。
- **每章校验通过立刻落断点**。暂停、切网、某章失败，都不再丢弃已验证的正文；恢复时跳过它们。断点计数改成「连续前缀」语义，避免并发留下空洞时虚报进度、恢复位置错位。
- 固定 200 ms 睡眠换成 0–40 ms 抖动，保留轻微错峰。

原有安全网一条没删：目录二次核对（下载期间源站改目录则整体失败）、章节乱码/缺章检测、插图 SHA-256、ZIP CRC。

测试口径：24 章 × 120 ms 模拟往返，串行下限 2.88 s（含旧睡眠 4.8 s），现在要求 2.4 s 内完成，且峰值并发 ≥2 且 ≤4、每章恰好请求一次、EPUB 逐章顺序正确。

## 2. 「番茄返回了不支持的正文格式」

这句报错的真实原因有点蠢：旧代码只要在正文里看到 `<img>` —— **哪怕是正常插图** —— 就直接判失败。于是带图的章节整章被拒。

现在的规则：

- 正常 HTML 全解析：`<p>`、`<br>`、内联 `<span>/<strong>`、`<div>` 布局。
- **插图保留**：`src` / `data-src` / `data-original` 解析成绝对地址（补 `//`、`http→https`），作为图片进 EPUB；相对路径以 `https://fanqienovel.com/` 为基准。
- 懒加载占位、`data:` URI、追踪像素这类拿不到真实地址的 `img` 直接丢弃，不让整章陪葬。
- 双重转义正文（`&lt;p&gt;…`）自动反转义，不再把标签当正文写进书里。

仍然拒绝（这些是真坏内容）：私有区字形 `\uE000–\uF8FF`（字体混淆）、`\uFFFD` 乱码、`<canvas>/<svg>/<video>/<audio>`、「获取内容失败」「请登录后阅读」「购买本章」占位页。

同时把「接口抖动」和「格式不支持」分开：缺 `content`/`txt`、空响应、错误码 → 可重试的网络错误（最多 5 次）；清洗后为空且像登录/购买页 → 明确提示需登录或购买。不会再出现接口抖一下就被报成格式问题。

兼容的响应外形：`{code,content}`、`{code,data:{content}}`、`{code,txt}`、`data` 内嵌 `code` 的嵌套信封。

## 3. 官方签名密钥已公开

`signing/release-keystore/` 里有完整私钥和明文口令：

- 私钥库 `ciallo-reader-release.p12`，别名 `ciallo-reader-release`，3072 位 RSA，有效期至 2054-03-13
- 口令 `Hf7cEa1iS4R8CCRo+E4/qCD3G0rKZ426jyPdOSuEzSE=`
- 证书指纹不变：`3ee5fb40…0794`（`signing/release-cert.sha256` 仍由 `verifyReleaseSigning` 强制校验）

用法（详见 `signing/release-keystore/README.md`）：

```powershell
$env:KEYSTORE_PATH  = "signing\release-keystore\ciallo-reader-release.p12"
$env:KEY_ALIAS      = "ciallo-reader-release"
$env:STORE_PASSWORD = 'Hf7cEa1iS4R8CCRo+E4/qCD3G0rKZ426jyPdOSuEzSE='
$env:KEY_PASSWORD   = $env:STORE_PASSWORD
# 先把 versionCode 抬到比已装版本大，然后：
./gradlew.bat :app:verifyReleaseSigning :app:assembleRelease
```

已从 GitHub 下载这份 p12 回来验证过：SHA-256 与仓库内 `SHA256SUMS.txt` 一致，口令可解，`verifyReleaseSigning` 通过，编出的包与官方包同证书。

代价说清楚：同签名包天然可互相覆盖。这是刻意的取舍——不公开就不可能让你自己编译可覆盖的版本。官方性由 GitHub Release + `SHA256SUMS.txt` 认定，不靠「私钥只在维护者手里」。要独立签名只能换包名另起发行渠道，那条路放弃覆盖升级。

## 验证

- 下载与书源实现相关 **74 个单测全部通过**（新增 `GuestNovelConcurrencyTest`，扩充 `FanqieGatewayTest` / `GuestNovelResumeTest`）。
- `verifyReleaseSigning` 通过固定指纹门禁。
- `verify-apk-upgrade.py` 对 v1.6.6 → v1.6.7 四项通过：包名、证书、`versionCode` 递增、非 debuggable。
- Release 附件回下载后 SHA-256 与 `SHA256SUMS.txt` 一致。

## 已知边界

- 番茄正文仍来自第三方公开网关（`biek.top`），它缺章或停服时客户端补不了，只会明确报错。
- 并发度固定 4，不随网络自适应；如果网关限流，把 `CHAPTER_CONCURRENCY` 调到 2。
- 插图下载仍在写入端串行（一张一次请求）。插图很多的书，插图阶段会比正文阶段慢。
