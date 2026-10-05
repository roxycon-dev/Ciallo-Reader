# keyword

中文关键词跨语言搜索：本地常用词与专名映射、结构化资料在线补充、持久缓存和分批查询。

- `KeywordModels.kt`：概念、语言名称、匹配键、发送去重键及查询预算。
- `KeywordExpansion.kt`：先本地、后在线，20 秒内逐提供者发布，最多 6 个完整查询（包含原词）；停用时展示实际原因。
- `KeywordRepository.kt`：连接种子、标签词库、旧 AniList 标题索引、缓存和在线服务。
- `LocalKeywordIndex.kt`：项目自有小型种子词表。
- `KeywordDictionary.kt`：独立的可重建 SQLite 反向索引及原子词库更新。
- `KeywordCache.kt`：最多 512 个查询的持久原子缓存、按提供者重试状态与可靠反向别名。
- `OnlineKeywordLookup.kt`：Wikidata、Bangumi 作品 / 角色 / 人物及 AniList 验证别名查询，无翻译依赖；逐请求读取系统代理，仅服务限流抑制同域请求，失败显示具体原因。

书架分类 PIN 保护不全局禁用书库在线补词。全局无痕或关闭在线开关仍暂停补词和词典更新。搜索用词使用书源管理同款 GlassKitCard，在结果中随内容滚动；展开显示提供者状态，失败可手动重新查词，源码与验收见 `docs/multilingual-keyword-search-v5-2026-10-05.md`。

给书源发送普通字符串，不添加站点语法，不过滤返回结果。匹配可以繁简折叠，发送去重不能折叠汉字或删除日文长音。词库资料许可见 `app/src/main/assets/keyword_dictionary_notice.txt` 和 `docs/vendor-licenses/keyword-ehtag-manifest.json`。

手动重新查词跳过普通失败 / 未收录缓存的重试等待，保留服务端限流与隐私限制。缓存只保存已验证名称及资料查询状态，不保存漫画搜索结果。

中文人名中间点分隔的名字部分支持作为简称（2–6 个汉字），仅角色 / 人物适用；优先完整名称，多个实体同简称不猜测。本地有独立 name_parts 索引；在线读取结构化中文全名。缓存格式 v3 保留旧名称，但不沿用旧规则的空结果 / 重试决定。
