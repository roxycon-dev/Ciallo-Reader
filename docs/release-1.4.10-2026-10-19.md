# Ciallo Reader 1.4.10 · 新增「书阁」小说书源

版本 **1.4.10 / 233**，Android 7.0+，仅 `arm64-v8a`，沿用固定发布签名。

[GitHub Release](https://github.com/roxycon-dev/Ciallo-Reader/releases/tag/v1.4.10) · [下载 APK](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.4.10/Ciallo-Reader-v1.4.10.apk) · [SHA256SUMS](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.4.10/SHA256SUMS.txt)

## 更新

- 新增免登录小说书源 **书阁（`xbookcn`，`https://xbookcn.app`）**：支持搜索、作品详情、完整目录、在线正文、整本 EPUB 下载与更新检查；无需账号，不依赖成人源开关，始终在书源管理中出现。
- 该站不提供整本文件，整本下载改为**逐章抓取正文后本地打包 EPUB**：下载前读一次目录，下载结束后再读一次目录并逐章比对 id 与标题，任一章缺失或不一致则整本作废并删除临时文件，不会产生半本入库。
- 修复 Cloudflare 注入脚本导致正常页面被误判为「验证或拦截页」的问题：改为先确认页面已有正文 / 搜索结果 / 目录结构再判定，只有确认为真挑战时才走内置验证流程并带 Cookie 重试。
- 源站 TLS 握手偶发失败时增加一次有界重试（连接失败、429、5xx 重试一次；404 与解析失败不重试），减少「随机的网络错误」。
- 作品链接只保存 12 位十六进制 ID，带 slug 的旧链接由 302 跳转兜底；超长章节在入库时按长度拆分为多个物理章，阅读器按逻辑章合并显示。
- 其余书源、书源注册开关、既有下载分支与既有章节处理逻辑**均未改动**。

## 验证

| 样本作品 | 实际验收 |
| --- | --- |
| 总裁X总裁 / 顾盼若浅（`b3068315c735`） | 搜索命中、详情、4 章目录、逐章正文；真实 `DownloadManager → DownloadWorker → XbookcnEpub` 整本下载，EPUB **287,610 B**，完整性校验通过，入库后在生产 `ReaderScreen` 打开阅读 |
| 舌尖上的诱惑 / 白娘子（`ee886a4fb420`） | 267 章目录与详情章数一致，order 连续，连续两次读取完全一致 |
| 还珠之和珅来了、锁情咒、无屌不欢（nph）、操你，还需要理由吗？(校园H) | 96 / 506 / 36 / 229 章目录与首中末章正文抽样，标题含括号、中文问号与大小写均可正确处理 |

搜索侧另验：完整书名、部分书名、作者名（20 条均为该作者）、繁体关键词、特殊符号均有结果，随机串返回空列表。站点**无正文插图、无封面**，书源不臆造图片，`cover` 为空。入库后物理 11 章由 `ChapterMerger` 合并为 4 个逻辑章。

测试：JVM/Robolectric **9 项通过**（含 2026-10-19 真实页面离线快照 3 项），Android 15 模拟器实网设备用例 **5 项通过**（注册不依赖成人源开关、搜索、目录、非法 ID 拒绝、下载+入库+阅读器）。

未测项如实列出：断网 / 取消 / 磁盘不足 / 429 与 5xx 注入、连载更新失败回滚、实体手机、搜索分页（站点只渲染首屏 20 条，不支持 SSR 分页）。完整范围见 [书阁验收报告](xbookcn-source-verification-2026-10-19.md)。

## 安装包校验

Release/R8 构建成功，9 项定向 JVM 测试与 5 项设备实网测试全部通过。APK ZIP CRC、版本号、架构、固定签名与 16 KiB 对齐通过。

文件：`Ciallo-Reader-v1.4.10.apk`，**29,112,092 字节**，包名 `com.aistudio.novelreader.kxmpzq`，versionCode 233 / versionName 1.4.10，仅 `arm64-v8a`。

```text
a1bdd2da1e7b0c8fd6efb76642798b93c606450e8b195d3dc7c424a3356ecfd5  Ciallo-Reader-v1.4.10.apk
```

`apksigner verify` 通过（`Verifies`，V2 签名），ZIP CRC 207 项无坏条目，8 个 `.so` 全部 16 KiB 对齐。

发布证书 SHA-256：`d2115e3cc5880b210a120558aaab03eb30ee5de2952a80f3376dda7e45501146`。
