# keyword tests

- `KeywordExpansionTest.kt`：查询预算、语言分配、分批补词、取消、降级、多关键词及原词保留。
- `KeywordLocalDataTest.kt`：真实词库反查、持久缓存、种子资料、旧标题索引排序与离线搜索入口。
- `OnlineKeywordLookupTest.kt`：结构化名称解析、部分语言补充、无关条目拒绝、备用来源及限流。

在线服务测试使用 OkHttp 拦截器返回固定响应，不依赖实时网站命中数量。
