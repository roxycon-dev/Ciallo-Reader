# Ciallo Reader 1.4.8 · 中文小说免登录源与下载完整性

版本 **1.4.8 / 231**，Android 7.0+，仅 `arm64-v8a`。

[GitHub Release](https://github.com/roxycon-dev/Ciallo-Reader/releases/tag/v1.4.8) · [下载 APK](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.4.8/Ciallo-Reader-v1.4.8.apk) · [SHA256SUMS](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.4.8/SHA256SUMS.txt)

## 更新

- 新增 **全本小说网、话本小说、SF轻小说**。公开作品无需登录即可搜索，整本下载为 EPUB，再从书架离线阅读；正文插图随书保存。新增三源以整本 EPUB 提供完整图文，不提供纯文字在线预览。
- 原有小说源显示名改为 **Novelia**、**Wenku8 Library**，保留原源 ID 与书架关联。Novelia 是站点域名简称，并非其日文上游网站。
- 下载逐项核对章节顺序、正文编码、EPUB ZIP/书脊与插图引用及图片解码。检测到空章、可识别缺章、已知占位正文、付费锁章或未处理的分页时，拒绝生成不完整的成功结果；更新失败保留原书与阅读数据。
- 公开小说请求增加一次可取消的网络异常重试。逐章生成 EPUB 的下载暂停后重新生成文件。
- 包含此前工作区已有的收藏封面加载与漫画回数识别修复：复用书源请求头，本地封面失效时回退；保留 180.5 等小数话数，番外和停更公告不按目录序号冒充正文话数。[修复记录](favorite-covers-chapter-labels-2026-10-18.md)

## 验证范围

三个新增源分别以《小王子》《KPL九尾：我也不想被他钓》《我的微小说集》验证搜索 → 整本下载 → 入库 → 生产阅读器显示，逐章核对段落与插图。现有 Novelia、Wenku8 Library、爱下电子书也完成样书复核。爱下电子书含已知“内容还在处理中”占位文字的样本已改为拒绝下载，未作为完整成功样本。

- Android 原生测试实际执行 **31 项通过**，包含 6 份真实下载书籍的本地阅读器显示检查；另外 **2 项实验源用例明确忽略**。
- JVM 下载与源响应回归 **22 项通过**。
- 最终 `assembleRelease` 成功，包含发布 lint；保留 R8 与资源精简。APK ZIP CRC、版本、架构与固定发布签名核验通过。
- 原生验证环境为 Android 35 / x86_64 模拟器，使用已有系统代理，未使用书源账号或订阅凭据；没有最终 ARM64 正式包的实体手机验收记录。

有限样书验证不等于覆盖市面所有作品，无法证明源站原文绝无缺漏，也不承诺与所有首发站同步更新。未通过匿名完整链路的候选源未启用。逐源结果和限制见 [小说书源验收报告](novel-guest-sources-verification-2026-10-19.md)。

## 安装包校验

文件：`Ciallo-Reader-v1.4.8.apk`，**29,119,208 字节**。

```text
0edc7b129db64c4b60f8fae0cc2732a642a47f2a2de04c1201c0faf460cf0fd7  Ciallo-Reader-v1.4.8.apk
```

发布证书 SHA-256：`d2115e3cc5880b210a120558aaab03eb30ee5de2952a80f3376dda7e45501146`，沿用固定签名。
