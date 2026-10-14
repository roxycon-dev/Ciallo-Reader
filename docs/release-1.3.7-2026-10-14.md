# Ciallo Reader 1.3.7 · 发现与书源修复（实际链路已通过）

版本 1.3.7 / 218，Android 7.0+、arm64。新增发现入口，并集中修复书库现有源的发现、分类及元数据。用户要求的 MYCOMIC 实际验证至阅读链路已通过。发布包已生成，沿用原签名；[下载 1.3.7](https://github.com/roxycon-dev/Ciallo-Reader/releases/tag/v1.3.7)。

发现支持聚合/单源、四独立维度、标准三级分类与原站分类、三布局、无限分页、快捷追漫、随机阅读和设置。没有原始讨论/点击/时间信息的源明确标为缺失，不将普通推荐包装成统计。跨源合并使用可信身份去重和稳定源内排序；分类保留未知原站标签，作者/角色命名空间不误归题材，成人分类默认隐藏。

修复 WNACG 的请求身份、卡片选择器和真实分页；漫蛙吧分类 URL 和 JSON 请求头；H-Comic 请求身份并移除恒空的伪榜单能力；Vomic 结构化 Flight/RSC 解析、引号标题与原始元数据；嗶哩发现目录身份；Goda 现有 Cronet HTTP/2 路径。禁漫只读 GET 使用发布方已知线路有限切换，验证解密 JSON 后学习线路，401 不切线，写入请求保持原逻辑。旧缓存同样应用补丁 42。

爱漫画原域名当前线路断连。其原站 HTML 的 `Mcpath.url` 声明了 `mh.iqtao.cn`；同一作品 ID、标题、作者和章节协议核对后接入此 HTTPS 线路，保留 canonical 作品/章节 ID，使既有书架与进度继续匹配。实际生产 JS 抽样得到首页/分类 30 条，第二页各 30 条新 ID；《顶级气运，悄悄修炼千年》返回 284 条章节目录，抽选章节 20 张图片地址齐全，首尾图片均 HTTP 200 且 JPEG 完整解码（1080×843、1080×777）。这不是所有章节/作品逐一验收。

MYCOMIC 的桌面直接/系统代理 HTTP 请求均被 Cloudflare 拦截。本版公开 HTML 读取走与站点验证相同的 Android WebView、UA 和 CookieManager，避免验证后切换另一种 TLS 客户端；仅作用于同源 HTTPS GET，拒绝站外导航、写入、字节图片请求，页面大小、20 秒等待及取消均有限制；不注入网页可调用的 JS 原生桥，不关闭 TLS 验证。挑战、HTTP 错误和空白页不会变成成功结果。首次提示验证时需在「书源管理 → MYCOMIC → 验证」完成原站验证。用户随后明确授权仅验证 MYCOMIC 使用模拟器。Android 35 / Google WebView 124.0.6367.219 上，真实 `verifyWebsite → discoverCatalog/discover → getDetail → getChapters → getChapterImages → ComicPageLoader` 全链路通过，耗时 62.675 秒；30 本目录，抽样作品《A PRESTO破碎的记忆》16 章，所选章节 61 页，首尾图片均实际解码为 780×1157。使用生产 `patchScript`，脚本 SHA256 `369c18cd76633fe475285888595f1ff4631be4f86eea38842fe5a59a5daeaf87` 与桌面生产导出一致；没有注入凭据、复制过盾 Cookie 或自动点击 CAPTCHA。[设备回执](mycomic-native-chain-2026-10-14.json)。

较早在线报告曾因桌面 DOM 文档/元素 ID 混用、请求头大小写处理错误产生误报，已加勘误；旧证据保留，见 [修复审查](discover-repair-2026-10-14.md)。其他 18 源近期成功抽样保留，不重复在线测试。本轮只新增爱漫画及 MYCOMIC 诊断。

## 验证和边界

最终选定 96 项回归全部通过，无失败、错误或跳过；Release/R8 构建及 MYCOMIC 专项 Android 测试源码编译成功。签名与 1.3.6 相同、ZIP CRC、ZIP/原生库 16 KiB 对齐、35 个打包资产逐字节核对、912 项构建输入稳定性核对通过。MYCOMIC 专项 Android 设备测试实际执行并通过（1 项）。首次设备运行仅因测试夹具路径不存在而失败，尚未发起站点请求；修正为私有测试文件后重编测试包并运行成功。生产代码在 Release 构建后未变；唯一后续源码变化是该设备测试的夹具路径和脚本哈希报告。初轮 85 项中 81 通过，4 项导航测试受不同 Robolectric TLS provider 跨类缓存影响；Release Kotlin 编译受到默认 daemon 堆不足影响。后续使用已有逐类隔离测试设置，并将编译独立为进程内 3 GiB 堆，初轮失败不隐藏。

遵照用户最终授权，仅为 MYCOMIC 启动现有 Android 模拟器并完成链路检查，没有重复测试其他健康源。未宣称玻璃视觉真机验收或业界排名。已有稳定 Release 未改；完整 MangaJaNai 权重仍从已有不可变 1.3.6 模型附件下载，没有重打或替换模型包。

参考：[原站声明线路与作品详情](https://mh.iqtao.cn/69008588.html)、[Venera 官方源配置](https://github.com/venera-app/venera-configs)、[Keiyoushi MCCMS 实现](https://github.com/keiyoushi/extensions-source/blob/main/lib-multisrc/mccms/src/eu/kanade/tachiyomi/multisrc/mccms/MCCMSWeb.kt)。

发布 APK：`artifacts/release-1.3.7/Ciallo-Reader-v1.3.7.apk`，28,939,839 B；SHA256 `0b55d08d916f80e5342ecb451ba6375e8f12bd8ec69c9a0ceba41a201acc9c74`。回执 `artifacts/release-1.3.7/verification.json`；复现核验 `python tools/verify-discover-candidate.py`。该脚本不会推送或发布。
