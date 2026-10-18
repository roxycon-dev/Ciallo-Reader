# 四部漫画最新话查源与验收（2026-10-18）

已新增本地内置源 **嬉皮漫画**（`xipi`）和 **19漫画**（`nineteen`）。四部作品均已通过免登录搜索、完整目录、在线阅读链路、WorkManager 整章下载、Room 书架入库和离线读图验证。勇者 **180.5话《鹰身女妖&天使》两张图完整通过**，另加测180话全部87张。总计五个章节、143张图片下载及本地解码通过。

## 已通过的作品

| 用户搜索名 | 书源 | 站内作品名与链接 | 验收章节 | 图片 | 目录条目 |
| --- | --- | --- | --- | --- | --- |
| 这个勇者是金钱至上主义者 | 嬉皮漫画 | [作品页](https://m.hipmh.com/works/bTozMDg0MA) | [第180.5话：鹰身女妖&天使](https://reader.hipmh.top/chapter/bTozMDg0MC1jOjE5MjkzOA-MzA4NDA6MjczLjAw) | 2 | 267 |
| 同上（加测） | 嬉皮漫画 | 同上 | 第180话：鹰眼堡的女妖（四堡篇） | 87 | 267 |
| 间谍过家家 | 19漫画 | [间谍过家家](https://m.sxchex.com/comic/70922) | [第141话](https://m.sxchex.com/chapter/1674318) | 23 | 201 |
| 葬送的芙莉莲 | 19漫画 | [葬送者芙莉莲](https://m.sxchex.com/comic/68250) | [第147话](https://m.sxchex.com/chapter/1428912) | 16 | 162 |
| 海贼王 | 19漫画 | [ONE PIECE航海王](https://m.sxchex.com/comic/71176) | [第1195话](https://m.sxchex.com/chapter/1690857) | 15 | 650 |

目录条目包括单行本、番外等，不代表正文章节编号；上述目录也不保证每个历史章节都免费、完整。验收对象是上表指定章节。另直接查看了三部日漫的首张正文图，图内分别标明 MISSION:141、第一百四十七话、第1195话；勇者180.5的两张图片也已逐张查看。

## 接入方式

- `app/src/main/assets/js_extra/nineteen.js` 是随应用分发的新适配器，已加入 `JsSourceRepo.LOCAL_EXTRA_SOURCES`，无需导入外部源仓库或账号。
- 从站点公开 `/search?key=…` 搜索，兼容“海贼王→航海王”“葬送的芙莉莲→芙莉莲”等站内命名差异。搜索结果保留站点真实作品名。
- 从公开 `/api/comic/chapter?mid=…` 获取完整目录。该站返回的 `pnum` 通常为 0，不把它误作正文页数。
- 阅读时核对目录归属、`readPic` 中的作品/章节 ID 和授权标记，仅提取 `.rd-article__pic` 下正文图片。实际图片数必须与阅读器的总页数一致，页序连续、URL 不重复；空图、缺页、串章及需要登录/购买的章节明确报错。
- 在线及后台下载使用同样的图片请求头，保留站点 Referer；没有执行网页广告脚本，没有使用账号、token、共享登录数据或付费凭据。
- “最新更新”使用站点公开页面；当前页面一次包含多个日期的作品列表，没有伪造翻页或排名。

### 嬉皮漫画与180.5话的编号核对

新增 `app/src/main/assets/js_extra/xipi.js`，从 GitHub 当前维护的 [Keiyoushi XipiManhua 适配器](https://github.com/keiyoushi/extensions-source/tree/cfc019928453a4650669d0514723360629c4af56/src/zh/xipimanhua) 移植公开 API 和阅读器图片列表协议。Apache 2.0 许可及归属说明已随 APK 打包。

搜索、作品详情、六页目录和章节图片接口均无需登录。目录有267个实际条目；站点的 `chapter_number` 是包括公告、特典的条目序号，不能当作正文章节号。应用保留原始标题，不把该站第273个编号误写成漫画273话。

[B漫官方作品详情](https://manga.bilibili.com/m/detail/mc32835) 的公开页面数据明确列出：章节2152720，`short_title` 为“第180.5话”，`title` 为“鹰身女妖&天使”，发布时间为2026-10-03 21:00:01。嬉皮目录中的同名章节为192938，作品30840，前一章为180话同名标题“鹰眼堡的女妖（四堡篇）”。官方编号对应记录保存在 `hero-expanded/official-chapter-reference.json`；官方自身的授权标记没有被修改，阅读及下载使用嬉皮公开接口。

图片列表按公开网页客户端相同协议还原并移除其占位条目，使用 BigInt 保留异或计算精度；校验作品/章节归属、原始标题、分段标记、图片路径及重复页。抓取目录时最多三个请求并行，核对总页数、总条目数和重复 ID，任何缺页或错误归属均报错。

应用中切换到“嬉皮漫画”后搜索勇者，选择目录 **“鹰身女妖&天使”** 即为180.5话；保留了用户输入“这个勇者是个金钱至上主义者”的搜索兼容。

## 验证结果

1. 桌面真实 HTTP 请求下载四部指定最新话全部56张图片，Pillow 完整解码56/56通过。
2. Android 35 / x86_64 模拟器运行 `NineteenDeviceTest`，使用实际 QuickJS、`JsComicSource`、`ComicPageLoader`、`ComicDownloadManager` / `ComicDownloadWorker`、Room 和本地图片文件。每话在线读取首/中/末三张（180.5话读取两张），下载全部页并逐文件解码，再用本地阅读器读取对应页。每次采用新的下载任务目录，未将上次下载复用为整章下载成功。五个章节总计143张全部通过。
3. 同一原生测试覆盖：排除广告图片、拒绝缺页/重复图/错误页序、拒绝串章和授权章节；用归档的真实180.5话响应校验图片还原和占位条目移除，拒绝损坏数据及不安全数值。最终 **OK (4 tests)，69.454秒**。
4. 原有 JS 宿主回归7项，以及新增嬉皮图片协议/编号校验2项，合计 **9项通过**。
5. Debug 和 Release 构建均成功。Release APK 内两个新增脚本与工作区文件逐字节一致，DEX 包含新源注册，许可与归属声明随包分发；ZIP CRC、APK v2 签名及既有签名证书指纹校验通过。

首次19漫画原生验收保留为失败记录：模拟器默认联网检测不可达，WorkManager 的 VALIDATED 网络约束使任务排队；在实际可达的华为204检测地址配置后，间谍整话成功，随后的一次图片HTTP/2超时终止首轮。嬉皮首轮的180.5话已通过，补验180话时一次图片HTTP/2超时；保持生产实现不变，重新执行全部四项测试、新建各章下载任务后全部通过。测试未设置模拟器HTTP代理；结束前恢复设备联网检测设置。

实网记录在 Git 忽略目录 `.source-verification/latest-comics/`。19漫画初轮证据位于该目录；最终两源构建及原生验收证据位于 `hero-expanded/`，包括 `official-chapter-reference.json`、`180-5-image-audit.json`、`device-first-xipi.json`、`device-test.log`、`device-test-retry.log`、`device-final-xipi.json`、`device-final-nineteen.json`、`debug-build.log`、`release-build.log`、`apk-signature.log` 和 `delivery.json`。这些结果只表示验收当时、当前线路和指定章节的可用性。

复核命令：

```powershell
node tools/verify-comic-source-host.cjs
node tools/verify-xipi-source.cjs
# 构建并安装当前 debug APK 与 androidTest APK 后，在 Android 设备执行：
adb shell am instrument -w -r -e class com.example.source.js.NineteenDeviceTest -e liveLatestComics true com.aistudio.novelreader.kxmpzq.debug.test/androidx.test.runner.AndroidJUnitRunner
```

## 其他候选的排除记录

| 候选 | 本次结果 |
| --- | --- |
| 爱漫画旧作品 `961857393` | 发布者线路 `mh.iqtao.cn` 的旧详情重定向到 `/err/comic`，旧目录接口不再返回章节；旧版180话记录不能证明今天可用 |
| 爱漫画搜索后的作品 `24294` | 公共目录最高正文为第160话，不能补足180.5 |
| KK酷看漫画 `30593` | 标注195条，实际混有公告，正文最高120话；不能当成195话 |
| 19漫画 `73245` | 有大量授权章节，未提供所需180.5话，不用于满足勇者更新要求 |
| 漫蛙 `8626` | 目录仍停在160话附近，另有公告，无180.5 |
| 爱看漫画 | 搜索索引仅到156话附近，本次直连详情返回502 |
| 喜漫漫画 | 本作品目录旧、会员专享/免费额度限制，不满足要求 |
| COLAMANGA | 当前入口重定向后的站点返回523 |
| 拷贝漫画 | 当前测试的公开入口返回服务器升级页面，不能验收 |
| u022 | 间谍141话标23张，匿名HTML第一页仅5张、第二页仅末3张，未拿到完整图片，不接入 |
| 漫画160 | 有最新话目录，但本次图片线路或搜索接口不可用，未通过全流程，不接入 |
| 漫画人 / MangaBZ | 检索页有147话，实测阅读页未提供可用正文数据，不作为通过的来源 |

上述排除记录保留，便于区分已验证的嬉皮180.5话与仅有旧目录、索引或登录门槛的候选。

## 最初本地验收包（1.4.6）

`releases/Ciallo-Reader-v1.4.6-勇者180.5与三部最新话.apk`，沿用当前项目版本1.4.6 / 229；ARM64，29,092,528字节。

SHA-256：`3e3f5d9d9b32fcc3b0e5416cec5007f3277104251bb65685066e7ee8bc17ae8f`。

签名证书 SHA-256：`d2115e3cc5880b210a120558aaab03eb30ee5de2952a80f3376dda7e45501146`。

这是最初本地验收时使用的包，尚未接入分类，未作为正式Release发布。最终安装包使用独立版本1.4.7 / 230，包含动态分类及1.4.6互传修复，见 [1.4.7发布记录](release-1.4.7-2026-10-18.md)。安装新版后，勇者选择“嬉皮漫画”，其他三部选择“19漫画”，搜索原名即可。

## 1.4.7动态分类与最终验收

19漫画从源站分类页读取121个入口，涵盖分类、标签、类型、品质、地区、版权、资费和进度。嬉皮从当前导航读取9个地区／题材入口，并在选中后从对应页面解析实际分类编号。均接入应用统一分类映射、状态筛选、原站排序和分页；嬉皮另有原站新作入口。

最终Android实网运行 **OK (6 tests)，54.652秒**，重新下载五个章节全部143张图片并逐文件解码，在线与离线阅读通过。新增测试确认真实分类、统一「冒险」映射、完结筛选、原站榜单和不同第二页；同时覆盖动态名称／编号变化及无效入口。嬉皮完结冒险分类6项，仅一页；完整冒险目录第一、二页分别18和6项，按源站真实总页数停止。

桌面检查共12项通过：宿主7项、嬉皮图片协议2项、发现路由及HTML请求头3项。实测发现并修复了嬉皮HTML分类请求误用JSON请求头的问题；修正测试中对单页目录的错误假设后，六项整体重跑通过。首次模拟器运行在用例前启动超时，预编译后正常；失败记录未覆盖或删除。

最终包 `releases/Ciallo-Reader-v1.4.7.apk`，29,092,660字节，ARM64，固定签名证书与上述一致。SHA-256：`26a2b58e657a8ae8b29960bdb9dd040ea77ba6e48437826b2ca4a46da3afc933`。两源脚本和许可文件与工作区逐字节一致，DEX注册、ZIP CRC与APK v2签名通过。

最终证据目录 `.source-verification/latest-comics/discovery-release/` 包含完整测试、两源下载报告、发现分类报告、构建与签名日志、生产源码哈希快照 `delivery.json`。发布脚本另记录上传资产哈希、公开链接回下载验结果于 `publication.json`。正式下载见 [v1.4.7 Release](https://github.com/roxycon-dev/Ciallo-Reader/releases/tag/v1.4.7)。
