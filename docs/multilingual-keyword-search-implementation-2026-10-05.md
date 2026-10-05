# 多语言关键词搜索实施报告

> 本文记录第一版实现与当时验证。后续在线缓存、人物资料和用词可见性修复见[多语言搜索 v2](multilingual-keyword-search-v2-2026-10-05.md)，请按 v2 说明验收当前版本。

日期：2026-10-05；功能范围：多语言搜索路径。翻译引擎及各书源的搜索语法 / 解析没有改动。

## 实际行为

- 输入中文后立即用原词搜索，后台从常用词、作品、角色、作者和社团名称中找其他语言叫法；本地批次先发送，在线资料随后追加。
- 例如“眼镜”会分别搜索“眼镜”“glasses”“眼鏡”。不给关键词添加站点 namespace，不以输入中文为由添加中文版本过滤。
- 聚合漫画、聚合小说及单源使用同一扩展路径。漫画跨源 8 路、小说 4 路；同源串行、1.1 秒间隔和 60 秒成功结果缓存保持。
- 一轮含原词最多 6 个完整查询，各语言公平获得预算。慢源会重放已收到的扩展批次；原词已经有结果时仍执行预算内补搜。
- 书源返回的结果逐批显示；仅同源同 ID 去重，跨源及同名不同 ID 保留。不按标题、标签或语言删除结果。
- 搜索框下展示本轮查询词，点击可以展开；书源选择面板保留“多语言搜索”，新增独立“缺词时在线补充”开关。
- 匹配允许繁简折叠，发送去重保留汉字差异、标点及日文长音。有效单字原词也会搜索。

## 资料和存储

144 条项目自有常用词 / 专名种子、44,530 条 EhTagTranslation 名称资料、原有 AniList 本地作品标题索引。第三方名称资料 gzip 为 647,255 字节，许可与版本见 [资料说明](vendor-licenses/keyword-data.md)。词库译名的装饰 emoji 在建索引时移除，不改变原始外文关键词。

名称词库在独立 SQLite 中建立反向索引，放在缓存目录，完整词库不常驻内存；主 Room 数据库仍为 v12，书架、收藏等用户表没有新增迁移。在线查词结果用 AtomicFile 持久缓存，最多 256 个查询，完整名称默认 30 天、部分名称或无匹配 6 小时。缓存可重建，清理系统缓存后需要重新导入 / 查询。

缺失资料按需查询 Wikidata 的实体名称 / 别名及 Bangumi 的书籍作品资料。一次补词窗口 6 秒，失败不影响原词和已有结果；限流时暂停该提供者请求。缺词时最多每日尝试一次标签资料快照更新，格式、大小验证及导入事务确保失败时保留旧词库。无痕 / 隐私模式停用额外在线查词和新增查询持久缓存，仍能读取本地映射。

## 验证

最终逻辑执行：

```text
:app:testDebugUnitTest --offline
  --tests com.example.source.keyword.*
  --tests com.example.library.ComicAggregateSearchTest
  --tests com.example.library.SourceSearchCoordinatorTest
  --tests com.example.data.MultiLanguageSearchTest
  --tests com.example.data.AppDatabaseMigrationTest
:app:assembleRelease
```

| 测试类 | 通过 / 总数 |
| --- | --- |
| KeywordExpansionTest | 13 / 13 |
| KeywordLocalDataTest | 6 / 6 |
| OnlineKeywordLookupTest | 6 / 6 |
| ComicAggregateSearchTest | 13 / 13 |
| SourceSearchCoordinatorTest | 7 / 7 |
| MultiLanguageSearchTest | 4 / 4 |
| AppDatabaseMigrationTest | 1 / 1 |
| 合计 | **50 / 50，0 失败、0 错误、0 跳过** |

覆盖真实词库导入反查、原词先发、繁简 / 日文去重分离、多个条件保持完整、语言预算、迟到批次给慢源重放、元数据失败保留结果、取消后旧词不再发布、类型化登录错误、缓存跨实例保存及主数据库迁移。

在线服务自动化采用固定 HTTP 响应，不依赖实时命中数量。另用只读公开 API 验证 Wikidata 的中文“博丽灵梦”可返回对应英文、日文标签；Bangumi 作品查询、详情与别名字段可访问，观察到中文短名可能只召回外传，实施中保持匹配依据校验，不能将外传误写成原作等价名称。

第一次集成构建与上述 50 项测试一起通过（3 分 34 秒）。随后将新增 UI 的字号和间距改为现有主题 / DesignTokens，最终源码再次 `:app:assembleRelease --offline` 通过（2 分 59 秒）。仅调整 UI 样式，没有再次改动关键词逻辑。

源码覆盖检查：520 个 Kotlin / Java 文件、未登记 0；反向检查仅两项历史已标注删除的旧开关。UI gate 的全局旧基线仍报告既有计数超标；本次改动的 LibraryScreen 相对起始 HEAD 的 7 项指标增量全部为 0，没有抬高基线。

## APK

- 文件：[CialloReader-multilingual-search.apk](../artifacts/multilingual-search/CialloReader-multilingual-search.apk)
- 当前工作区构建，版本 `1.2.0 / 201`，仅 `arm64-v8a`，23,981,380 字节。
- SHA-256：`b07b09cb1b6c08f37875a8382a599de8eda6323604b71424d1476385ba527984`。
- APK v2 签名验证通过，签名者 `CN=Android Debug`；ZIP CRC 校验无错误。确认词库、种子、版本与许可声明已打包，词库内容与源资产逐字节一致。
- 构建包含任务开始前工作区已有的阅读器改动；本次没有提交、推送或替换 GitHub Release。

## 验证边界

本机没有已连接 Android 设备或现成 AVD，本轮未做真机安装、UI 截图验收及 E-Hentai 等真实账号 / 网络下的在线命中测试。50 项相关测试通过不等于全应用 547 项均执行通过；前一轮的全量测试记录保留在 PROJECT_GUIDE.md 第 36 章。

首次完整词库导入在 IO 线程执行，原词搜索不等它完成；未宣称真实手机上的索引耗时或 200ms 性能目标已达成。当前名称扩展覆盖的是资料已知词条，任意中文长句、所有冷门人名和每一种外文叫法不保证有资料。

用户编辑映射、点击学习、自由猜测名称、翻译兜底未加入。上游请求的下游登记尚待项目维护者处理，未代用户向上游发消息；许可及商业发行条件保留在资料说明。
