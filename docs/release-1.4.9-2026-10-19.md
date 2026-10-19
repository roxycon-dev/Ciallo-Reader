# Ciallo Reader 1.4.9 · 小说插图、下载恢复与搜索结果修复

版本 **1.4.9 / 232**，Android 7.0+，仅 `arm64-v8a`，沿用固定发布签名。

[GitHub Release](https://github.com/roxycon-dev/Ciallo-Reader/releases/tag/v1.4.9) · [下载 APK](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.4.9/Ciallo-Reader-v1.4.9.apk) · [SHA256SUMS](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.4.9/SHA256SUMS.txt)

## 更新

- 修复 Wenku8 Library 与 Z-Library 的 EPUB 正文插图“图片读取失败”。新导入生成正确的包内图片路径，已有书籍兼容读取旧引用，无需删除重下，不改章节、进度和标注。
- 修复 Novelia 有效 UTF-8 译文中的孤立源站缺字被误判为整本乱码。保留源站原文；继续拦截损坏编码、空字符、密集缺字、缺章、缺译占位与损坏图片。
- 排除 Z-Library 页面明确隐藏、名称与搜索词完全一致的假卡，避免无结果时展示不可下载的书籍。可见的同名版本正常保留。
- Z-Library 下载链接失效时自动选择可用节点、重新取得同一书名/作者/格式的文件；优先直连探测，其次使用系统代理。下载额度、频率及存储问题明确报错。
- 书库增加文字“下载任务”入口和常驻进度提示，展示准备、百分比、字节量、失败原因、文件明细，支持暂停、继续、取消与重试。小说与漫画任务可同时查看。
- 修复根目录 OPF 的 EPUB 目录路径拼接，使已完整下载的文件可重试入库，保留路径边界检查。

## 验证

| 用户指定作品 | 实际验收 |
| --- | --- |
| Wenku8 Library《青春恋爱物语》对应的渡航《果然我的青春恋爱喜剧搞错了》（ID 1213） | 87,225,957 B 完整 EPUB；755 个本地章节，395 张独立图片全部解码，非封面插图在正式阅读器显示 |
| Z-Library《青春猪头少年不会梦到兔女郎学姐》 | 完整标题精确匹配，3,386,637 B EPUB；18 章，13 张独立插图全部解码，非封面插图在正式阅读器显示 |
| Novelia《青春猪头少年不会梦到平行弹簧》（hameln/387502） | 951,084 B；完整目录 73 章逐项对应，入库拆分 74 节；源站已有的一处缺字保留，不再阻止整本下载 |

旧书的 52 张插图兼容读取通过，读取前后章节和进度记录完全一致。两个 Z-Library 节点均复现同名隐藏假卡，过滤后可见同名结果为零。真实下载使用正式 WorkManager 和 Room 入库流程，阅读页使用生产 ReaderScreen。

另外已验证失效 Z-Library 链接自动换节点，以及根目录 OPF 完整文件免重下重试入库。完整范围见 [插图专项记录](novel-illustrations-repair-2026-10-19.md) 与 [下载恢复记录](zlibrary-download-recovery-2026-10-19.md)。

测试环境为 Android 35 / x86_64 模拟器及 JVM/Robolectric，实网使用已有系统代理；不能承诺所有地区免代理或覆盖所有书籍。Novelia 源站原译文的一处缺字没有被杜撰补写。此前指定《无职转生未来日记》没有取得有效文件，只验证了其隐藏假卡排除，不以其他书的下载冒充该书成功。

## 安装包校验

最终 Release/R8/Vital Lint 构建成功，**41 项定向 JVM/Robolectric 回归通过**。生产代码的真实下载、旧引用、两书非封面阅读与隐藏卡设备用例 **9 项通过**，会话清理另验。APK ZIP CRC、版本、架构、固定签名及 16 KiB 对齐通过；1,418 项构建输入哈希核对一致。

文件：`Ciallo-Reader-v1.4.9.apk`，**29,130,128 字节**。

```text
c9001018846ea5d5fadd595771711413a8ad4159af1ccd312f05de71ecbcf073  Ciallo-Reader-v1.4.9.apk
```

发布证书 SHA-256：`d2115e3cc5880b210a120558aaab03eb30ee5de2952a80f3376dda7e45501146`。
