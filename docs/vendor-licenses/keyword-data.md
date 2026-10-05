# 多语言搜索名称资料

`app/src/main/assets/keyword_ehtag.tsv.gzip` 源自 [EhTagTranslation 译文数据库](https://github.com/EhTagTranslation/Database)，署名 EhTagTranslation 全体编辑者，采用 [CC BY-NC-SA 3.0 中国大陆](https://github.com/EhTagTranslation/Database/blob/master/LICENSE.md)。

派生处理：提取命名空间、原始标签文本和译名；去掉描述、图片及链接，转为 gzip TSV。运行时查词索引清理译名里的装饰 emoji，保持原始外文关键词。派生资料继续遵循上述许可，不与代码或项目自有种子词表的许可混同。来源版本与转换记录在 `keyword-ehtag-manifest.json`。

数据库格式版本 7；源提交 `bb0560ea091d620bdffb0eea518d1007af8ff7c9`；44,530 条资料，647,255 字节 gzip。APK 包含可读的署名、许可链接与派生说明 `keyword_dictionary_notice.txt`。

上游 README 请求下游提交项目简介 / 地址的登记 Issue；本次未向上游发消息，登记仍待项目维护者处理。本记录不代表获得上游背书。商业发行前按资料许可和项目发行用途核验可用范围。

在线服务：

- [Wikidata 数据许可](https://www.wikidata.org/wiki/Wikidata:Copyright)：CC0；仅按需查询名称和别名。
- [Bangumi API](https://bangumi.github.io/api/)：仅按需查询书籍作品名称及明确别名，不批量爬取内容。
- 旧 AniList 内置标题资料继续作为本地提供者；本轮没有新增 AniList 全量下载任务。

在线资料不默认导出成公开全量词库；本地查词缓存为可清除、可重建资料。
