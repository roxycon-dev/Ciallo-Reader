# 爱丽丝书屋（alicesw）书源接入记录

日期：2026-11-06　范围：内置书源 `alicesw`（源代码与解析层）

## 一、为什么不是"填一个网址"就完事

爱丽丝书屋同时在跑多条线路，而且线路的可达性在国内网络下并不一致。2026-11-06 实测：

| 域名 | 结果 |
| --- | --- |
| `www.alicesw.com` | 连接超时（TCP 不通） |
| `www.alicesw.tw` | TLS 握手被重置 |
| `www.alicesw1.homes` ~ `www.alicesw3.homes` | TLS 握手被重置（同一 IP 38.46.221.232） |
| `alicesw4.homes`（含 www） | **HTTP 200，正文正常**，首页 82 KB、搜索页 39 KB |
| `alicesw.click` / `alicesw.net` / `alicesw.club` | 200，但只是"发布页"，无任何书籍内容 |
| `alicesw1.quest` | 200，"线路选择"页，把三条线路域名倒写进脚本 |
| `xn--vcsx64d.als1007.space` | 200，跳转页，指向 `alicesw1.quest` |
| `www.alicesw.cn` / `www.alicesw.icu` | TLS 通，但 302 到 `securetrackingnode.com` 广告跳转，**不是源站** |
| `www.alicesw.xyz` | 停放页，只有夸克网盘推广 |

结论有两条：一是**不需要翻墙**，`alicesw4.homes` 这类线路直连可用；二是**不能写死一个域名**——同一天里对同一域名出现过"先 RST、后 200"的抖动，域名也会轮换。所以接入方式是"线路池"而不是"单域名"，与 Z-Library 的多节点思路一致。

## 二、页面结构（实测）

| 页面 | 地址 | 关键选择器 |
| --- | --- | --- |
| 搜索 | `/search?q={kw}&f=title\|author\|tag\|_all` | `div.list-group div.list-group-item`，标题 `h5 a`，状态 `h5 small`，`p.mb-1` 作者/字数/浏览，`p.content-txt` 简介，`p.timedesc` 更新时间 |
| 详情 | `/novel/{作品id}.html` | `div.novel_title`、`div.novel_info p`、`div.jianjie p`、`div.tags_list a`、`div.box_intro img` |
| 目录 | `/other/chapters/id/{作品id}.html` | `ul.mulu_list li a`（列表顺序即阅读顺序） |
| 正文 | `/book/{书库id}/{章节hash}.html` | `div.read-content > p`，158 段 / 9072 字实测完整，免登录、无分页 |

两个容易踩的点：

1. **作品 id 与书库 id 不同**。详情页是 `/novel/35647.html`，但章节链接指向 `/book/37187/...`；章节标识因此保存为 `书库id/章节hash`，不能用作品 id 拼正文地址。
2. **搜索结果标题带序号前缀**（`1. 我是美母骑士…`），入库前必须剥掉，否则书名被污染。

## 三、实现

| 文件 | 作用 |
| --- | --- |
| `source/impl/AliceSwParser.kt` | 纯解析：搜索列表、详情、目录、正文；不碰网络，便于离线回归 |
| `source/impl/AliceSwDiscovery.kt` | 线路页纯文本抽取（倒写的 `r('semoh.2wsecila.www//:sptth')` + 明文 `https://…`），不执行页面脚本 |
| `source/impl/AliceSwEndpoints.kt` | 线路池：并行探活、首条可用即用、失败冷却 5 分钟、成功线路缓存 12 小时、兜底走线路页动态发现 |
| `source/impl/AliceSwSource.kt` | 书源本体：搜索/详情/目录/正文/下载信息，单次请求失败自动换线重试一次 |
| `download/AliceSwEpub.kt` | 整本打包：逐章抓取生成 EPUB，下载前后比对目录，缺章即整体作废 |
| `source/NovelInfo.kt` | `WholeBookNovelSources` 加入 `alicesw`，接入整本下载与更新流程 |
| `library/LibraryViewModel.kt` | 与书阁同位置注册，始终启用，不跟随成人源开关 |
| `download/DownloadWorker.kt` | 新增 `alicesw` 分支，复用上传进度回调 |

线路选路顺序：上次成功线路 → 预置四条线路 → 线路页动态发现。探活校验"返回页面确实是本站"（含 `alicesw` 或「爱丽丝书屋」），避免把停放域名或广告跳转当可用线路。

## 四、发现页：排行与分类

排行、分类、「最近更新」三处用的是同一套卡片，所以共用一套解析：

| 入口 | 地址 | 条数 / 翻页 |
| --- | --- | --- |
| 日榜 | `/other/rank_hits/order/hits_day.html` | 50 条，不分页 |
| 周榜／月榜／总榜 | `hits_week.html` / `hits_month.html` / `hits.html` | 各 50 条，不分页 |
| 最近更新 | `/all/order/update_time+desc.html` | 50 条，`?page=N` |
| 分类 | `/lists/{2–3 位 id}.html` | 50 条，`?page=N` |

卡片结构（`div.rec_rullist > ul`）：`li.one` 名次、`li.sev` 分类、`li.two` 书名、`li.three` 最新章节、`li.four` 作者、`li.five` 字数、`li.six` 日期（排行页带时分）、`li.diyhot` 热度。

映射到发现页的口径：

- 榜单维度只声明站点真有的四档：`ranking → [日, 周, 月, 总]`，不造「飙升／新作」；`updates → [最新]`；热议和点击没有数据，路由返回 null 并给出提示。
- 名次取 `li.one`，不按热度重排；热度写进 `clicks`，日期换算成时间戳写进 `updatedAt`，`nativeOrder` 保留原站顺序。
- 分类入口只认 2–3 位数字 id：首页还挂着 `/lists/31156.html` 这类已失效的多位数字链接（实测返回「提示信息」页），当成分类会把用户带到错误页。
- 续页游标存绝对地址；线路换域名时只保留路径与查询串，重新套到当前线路上。

## 五、已知限制

- **搜索只返回首 10 条**：站点没有分页参数，`&page=2` 返回同一批结果；这是源站能力，不是实现省略。
- **热议／点击维度无数据**：站点只提供四档排行（本质按点击热度）与最近更新，其余维度会返回「没有该维度的数据」。
- **正文含站点广告位**：正文容器本身干净，但页面其它区域是推广链接，解析只取 `div.read-content`。
- **内容分级**：站点为成人向小说站，接入方式与书阁一致，源站自带"人物均为 18 岁以上"声明。

## 六、验证

已执行（本机，2026-11-06）：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*AliceSw*" :app:assembleDebug
```

```
BUILD SUCCESSFUL in 6m 30s
AliceSwDiscoverTest   tests=7 failures=0 errors=0
AliceSwDiscoveryTest  tests=2 failures=0 errors=0
AliceSwParserTest     tests=7 failures=0 errors=0
```

产物：`app/build/outputs/apk/debug/app-debug.apk`，50.35 MB，包名 `com.aistudio.novelreader.kxmpzq.debug`，versionCode 256 / versionName 1.5.14，compileSdk 35、minSdk 24，APK Signature Scheme v2 验签通过（Android Debug 证书）。反查 dex 能看到 `alicesw` 与「爱丽丝书屋」字样，确认书源与发现页代码进了包。

离线回归（没装 SDK 也能跑，用 kotlinc + JUnit 直接编译解析层）：

```
python ciallo/tools/verify_parsers.py
→ OK (9 tests)
```

联网完整性核验（同一批工具脚本）：

- `python ciallo/tools/check_chapter_links.py toc_*.html`：四本样书目录共 227 条 `/book/` 链接，全部符合章节正则（13 位 hash），没有因正则过窄被静默丢弃的章节。
- `python ciallo/tools/check_toc_match.py 32255 32844`：目录页条数与详情页「章 节：N」一致（50=50、120=120），目录页没有 `page=` 分页，长书不会被截断。

首轮 10 项用例跑出 2 项失败，均为真实缺陷并已修复：标题带序号前缀、`字 数：… · 章 节：11` 复合行只解析出第一个字段；第三轮又抓出封面 `https://` 前缀被漏判。

未执行，须补：

- **未上真机**：搜索→详情→阅读→整本下载入库，以及发现页排行／分类切换，都没有在设备上跑过；APK 只验证到编译通过、单元测试通过、安装包结构与签名合法。
- 本机构建环境是临时搭的：JDK 17（Temurin）＋ Android SDK（platform 35、build-tools 35.0.0、NDK 29）。由于工作区路径过长触发 CMake 的 260 字符限制，SDK 放在 `C:\asdk`，并在 `C:\cr` 建了指向项目的目录联接。仓库 `gradle.properties` 里的 `org.gradle.java.home` 是作者机器路径，本机构建时临时指向 JDK 17，构建完已还原。

## 七、回归方式

```powershell
# 解析层（离线，不需要 Android SDK）
python ciallo/tools/verify_parsers.py

# 联网完整性：目录是否被截断、章节链接是否全部可解析
python ciallo/tools/check_toc_match.py 32255 32844
python ciallo/tools/check_chapter_links.py toc_32255.html

# 线路是否还通（域名轮换时先跑这个）
python ciallo/tools/probe_hosts.py

# 有 Android SDK 后
./gradlew :app:testDebugUnitTest --tests '*AliceSw*'
```

站点改版时先看 `AliceSwParserTest` 哪一条变红，再按"二、页面结构"的表对选择器。
