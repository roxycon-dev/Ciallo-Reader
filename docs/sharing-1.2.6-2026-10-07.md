# 1.2.6 分享功能与验证（2026-10-07）

版本 1.2.6 / 207。书架分享本地文件，收藏分享对应作品的详情链接。

## 用户行为

- 书架单本使用 `ACTION_SEND`；多选使用 `ACTION_SEND_MULTIPLE`。每个 URI 同时进入 `EXTRA_STREAM` 与 `ClipData`，授予接收方临时读取权限；相同格式保留具体 MIME，混合格式使用对应通配类型。
- 收藏按 `sourceId + comicId` 取得公开作品详情 URL，保留选中顺序。同名作品不会跨来源混用链接或下载文件。相对路径由书源根地址解析，拒绝非 HTTP(S)、登录凭据与控制字符。
- 收藏公开 URL 持久缓存；已缓存链接在离线或源移除后可分享。JS 源使用 `comic.loadInfo` 的 `url`，仅获取元数据，避免为分享获取所有页面。没有链接时说明对应作品与原因，整批不会退化为只发送标题。
- 准备时禁用重复点击并显示“准备中…”。准备完整成功后打开系统分享面板；出错保留选择并反馈对应作品。

## 原文件归属

存在原文件时，分享其完整字节；不会把 PDF 转成图片 CBZ，不会重新生成存在的 EPUB 或 TXT。应用私有文件直接由 FileProvider 包装，外部路径与临时 SAF 授权采用原字节临时副本；不扩大 provider 可访问目录。

新导入的本地漫画在自己的解压目录 `original/` 保存原归档。私有导入中间副本按原流程清理，原归档与漫画同生命周期一起删除。ZIP 字符集回退只清理解压页，不清理原件。

在线逐页下载通常没有整本源归档；旧版导入也可能已经删除原件。这两种情况按数据库页序打包 CBZ，图片字节保持不变，缺页明确失败。旧版删除的 PDF / CBZ 原字节无法凭空恢复。

临时分享文件保留 24 小时；新分享不会删除接收方尚未读取的文件。复制、归档、文本重建流式执行并响应取消，不堆积整本字符串。下载取消产生 socket IOException 时先在不可取消区域保存暂停状态，再传播协程取消，保留断点文件。

## 验证

- 39 项 JVM / Robolectric 通过：收藏分享 11、导入安全 12、来源匹配 4、下载协议 5、下载 Worker 7。
- 14 项 Android 35 x86_64 模拟器原生测试通过：单本字节、完整多附件 / 顺序 / 读取授权、MIME 聚合、编码 file URI、provider 外文件的私有副本、真实 content URI 导入后原归档保留、PDF 原格式、来源身份恢复、旧漫画页序、缺失源文件 / 缺页中止、临时文件保留、取消传播。
- `tools/ui-gate.ps1` 原检查与基线不变，font 503/549、radius 297/327、color 289/311、descNull 114/118、dp 2394/2528、sp 581/610、contentType 31/27，全通过。
- Debug 与设备测试包编译通过。Windows Robolectric 的 Android FileProvider `/` 根路径判断与 Windows canonical 路径不兼容，文件读验证转到真实 Android 环境；没有放宽生产路径规则。
- 工程指南覆盖 566 个 Kotlin / Java 源文件；新增文件遗漏 0，反向检查仅剩 2 个已明确删除的历史开关文件。

## 发布校验

正式包 **24,079,476 B**，arm64-v8a；1.2.6 / 207、ZIP CRC、16KB 对齐与 1603 项构建输入指纹一致性全部通过。

- APK SHA-256：`7ddf6a6477f944f5d0faf0d0ce3ec1dc75bf5f5b68958b90f0a8d424a654faeb`。
- 签名证书 SHA-256：`d2115e3cc5880b210a120558aaab03eb30ee5de2952a80f3376dda7e45501146`，与已发布 1.2.5 一致，可覆盖安装。
- [下载 v1.2.6 APK](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.2.6/Ciallo-Reader-v1.2.6.apk)。

详细日志、设备回执和 APK 校验位于本地 `artifacts/release-1.2.6/`，正式安装包在 GitHub v1.2.6 Release。

## 验证边界

没有用户真实手机与微信 / QQ 实际发送验证，不把 FileProvider 原生读取通过等同于所有接收应用兼容。单个接收应用可能只支持一种附件；用户可通过系统面板选择支持多文件的应用。源站链接可用性依赖其网络与存续，不提供公开作品链接的脚本会明确反馈，不能把 API / 封面 / 下载地址当作品详情。

链接协议维护参考：[Venera ComicDetails.url 使用实例](https://github.com/venera-app/venera-configs/blob/main/nhentai.js)、[AutoNovel 官方详情路由](https://github.com/auto-novel/auto-novel/blob/main/web/src/router.ts)。中文文库 `/books/{id}` 已通过网站书库真实链接核对。
