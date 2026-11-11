# 《这个勇者是金钱至上主义者》180 话新源验收

验收日期：2026-10-13。已新增免登录的“爱漫画”源，使用现有 JS 宿主，独立于 Venera 源仓库。

## 已确认的作品与话数

- [作品页](https://www.alipaypay.com/961857393.html)。
- [第 180 话：鹰眼堡的女妖（四堡篇）](https://www.alipaypay.com/961857393/5846169.html)。源站目录省略正文话数，但本章第 12 张图直接标明“第180话”，不是将公告、特典数量当作话数。
- 公开章节接口 `/index.php/api/comic/chapter?mid=961857393` 返回 275 个条目，包含正文、公告、番外；275 不代表 275 话。该章节接口标称 87 张，阅读页确实包含 87 张。

## 接入与实际验证

参照 GitHub 上维护的 [Keiyoushi MCCMSWeb 适配器](https://github.com/keiyoushi/extensions-source/blob/main/lib-multisrc/mccms/src/eu/kanade/tachiyomi/multisrc/mccms/MCCMSWeb.kt) 的站点协议，新增 `app/src/main/assets/js_extra/aimanhua.js`，由 `JsSourceRepo` 的本地源列表加载。没有使用 Venera 的漫画源脚本，也没有另造阅读器或下载器。

- 使用公开章节 API 获取完整目录，避免详情页折叠列表只显示前 20 条。
- 只读取 `#comicContain` 中的正文图片，并核对目录标称页数；空图、缺页、需要授权的条目明确报错。
- 原图保留站点实际图床。WordPress 图床的部分无参数地址命中 HTTP 400 缓存；显式 `w=1080` 图片规格恢复有效图片。
- 为在线阅读和后台下载都设置 `Accept: image/jpeg`。默认下载 Accept 会协商 WebP，原生首次验收有 5 张失败；修复后整章通过。URL 虽以 `.avif` 命名，实际响应是可解码的 JPEG，不依赖设备的 AVIF 支持。
- 图片不附带登录凭据，沿用 MCCMS 适配器不发送 Referer 的行为；源站章节接口没有付费或登录标志。

Android 35 模拟器使用应用实际 QuickJS 桥、`ComicPageLoader`、`ComicDownloadManager` / WorkManager、Room 书架和本地阅读器验收：搜索得到目标作品，275 个目录条目，第 180 话 87 张图片全部下载，全部本地文件通过 Android 图片尺寸解码；首图、话数页、中间页、末页分别通过在线及离线解码。每次下载使用新的测试任务目录，没有将以前的下载当成冷下载通过。

原生回归共 3 项通过，耗时 42.067 秒：完整下载/离线阅读；搜索作品标识和 275 条目录完整性；正文图片范围及缺页拒绝。已有主机漫画桥回归 7 项通过。测试结果只代表本次作品、章节和网络，不保证第三方源永久可用。

原始证据位于被 Git 忽略的 `.source-verification/money-hero/`：`device-final-evidence.json`、`device-final-audit.log`、`device-first-failure.json`、`image-audit.json`、`image-audit-jpeg.json`、`jpeg-header-comparison.json`。主机的首次图片失败、网络抖动及原生下载失败记录均保留。

## 安装包

最终 `:app:assembleRelease` 成功，耗时 4 分 47 秒，保持 1.3.6 / 217。交付包：`releases/Ciallo-Reader-v1.3.6-爱漫画180话.apk`，28,922,511 字节，仅 ARM64，APK v2 签名与 ZIP CRC 校验通过；沿用项目签名证书 SHA-256 `d2115e3cc5880b210a120558aaab03eb30ee5de2952a80f3376dda7e45501146`。

APK SHA-256：`e81329f65fa033d0f671a1aa2fc745de1f4c3f61deab881ca33985aeb65cc24e`。已确认包内新源脚本与最终源码逐字节一致，未包含测试图片或审计资产。构建、签名和交付证据：`build-release-final.log`、`apk-signature.log`、`delivery.json`。临时模拟器代理已恢复，模拟器已关闭；未提交或发布远端。

## 其他候选

| 候选 | 实测结果 |
| --- | --- |
| 包子 / GoDa 系 | 当前公开目录最高正文第 160 话，不能满足要求 |
| 非常爱漫 | 当前作品目录最高第 160 话 |
| 瓜子漫画 | 188 个目录条目，最高正文第 135 话；不能误报为更新到 188 话 |
| manhuagui.cc | 同名站点，本作品最高编号第 118.5 话；不是标准漫画柜域名 |
| 六漫画 | 找到 Keiyoushi 解析，但当前搜索未找到目标作品 |
| COLAMANGA | 当前入口重定向后返回 522/523；Keiyoushi 已移除该适配器，不能作为有效源 |

## PicACG 为什么看起来无需登录

研究的是 PicACG 登录机制，作为用户指定的例外单独检查；没有使用其他 Venera 源来寻找新漫画源。

[PicACG 源实现](https://github.com/venera-app/venera-configs/blob/main/picacg.js) 的登录接口是 `/auth/sign-in`，成功后保存 token；搜索、目录、读图接口通过 `authorization: this.loadData('token')` 发送该 token。401 会触发 `account.reLogin()`，从已保存账号重新登录。客户端签名及 API key 是客户端协议的一部分，不等于用户授权 token。

[Venera 的登录包装器](https://github.com/venera-app/venera/blob/master/lib/foundation/comic_source/parser.dart) 登录成功后另外保存账号；[持久化实现](https://github.com/venera-app/venera/blob/master/lib/foundation/comic_source/comic_source.dart) 会从 `comic_source/<key>.data` 恢复源数据，并支持重新登录。因此“之后打开不需要手工登录”可以由已有会话解释；恢复或同步账号也是可能原因，未检查用户具体的 Venera 实例，不能确定其实际原因。

实网使用公开客户端协议发送空 `authorization` 请求，PicACG 返回 HTTP 401、`unauthorized`（错误 1005），记录在 `picacg-anonymous.json`。没有发现匿名完整阅读通道；没有绕过登录、共享账号或付费授权，也没有改掉本项目中 PicACG 的登录要求。
