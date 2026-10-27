# Ciallo Reader 1.5.8 · 漫画源搜索与读图修复

版本 **1.5.8 / 250**，Android 7.0+，ARM64。

修复紳士漫畫、19漫画和爱漫画在部分代理线路下搜索返回空响应、图片损坏及整章下载失败的问题。三个源的公开请求优先完整读取直连响应，必要时再回退；爱漫画图片也在确认完整响应体后才交给阅读器或下载器。

紳士漫畫已有可用漫画域名时不再等待非必要的域名发布页刷新。爱漫画正文及旧缓存地址避开失效图片代理，保留原作品与章节身份。下载与阅读统一使用源请求头，并修复续传时旧扩展名页面被误复用的问题。

同时包含聚合搜索超时和错误状态修复、拷贝漫画初始化修复，以及缓存源逐项故障隔离。

## 实网验证

最终在开启系统代理的 Android 模拟器上，使用真实生产搜索、在线阅读、后台下载与离线读图流程：

- 爱漫画：用户指定的《泛而不精的我被逐出了勇者队伍》，所选章节 **45/45页**。
- 19漫画：所选章节 **24/24页**。
- 紳士漫畫：所选章节 **22/22页**。

共 **91页全部以新任务目录下载、逐页解码并完成离线阅读**。其余已验证的源按用户要求不重复测试。Picacg、vomic 仍需账号，MYCOMIC 仍需网站验证；它们不计入本轮通过。源站授权、下架和缺图状态仍会如实提示。具体作品、测试范围与证据见[验收记录](comic-source-repair-2026-10-27.md)。

## 下载与升级

[ARM64 APK](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.5.8/Ciallo-Reader-v1.5.8.apk) · [SHA-256](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.5.8/SHA256SUMS.txt) · [验证记录](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.5.8/validation.json) · [混淆映射](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.5.8/mapping.txt.gz)

沿用1.5.6起的固定发布证书，可覆盖1.5.6和1.5.7。旧签名版本仍需按[签名迁移说明](app-signing.md)先备份再迁移。

发布证书 SHA-256：`3ee5fb4018a00457eb24cbbfc43e78967d24069b760a5d386d6c9454b3770794`。

APK SHA-256：`2a6cb9faea29c04b73a0f14398c208354f76aac1428afa3cbb5d33baae89e38d`，29280860 字节。

签名、ZIP 对齐、静态升级检查及独立模拟器的1.5.7→1.5.8实际覆盖升级通过；升级后测试书籍、两章正文与阅读进度保持。
