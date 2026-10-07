# library

路径：`app/src/main/java/com/example/library/`。

## 用途

书库聚合搜索、在线详情和漫画下载接线。

## 内容
- `BookShareHelper.kt`
- `FavoriteShareHelper.kt`：收藏公开详情链接与离线链接缓存。
- `SharedWorkLink.kt`：无公开网页来源的 Ciallo 详情链接编码与验证。
- `ComicAggregateSearch.kt`
- `ComicDownloadManager.kt`
- `ComicDownloadWorker.kt`
- `ComicLocalImporter.kt`
- `DownloadGlassCard.kt`
- `FormatPickerDialog.kt`
- `GenericCoverLoader.kt`
- `ImageBytes.kt`
- `LibraryError.kt`
- 其余 15 项按名称和同层模块组织。

## 维护提示

随 APK 打包的生产代码和资源应通过 `:app:assembleRelease` 验证；测试 fixtures 放在对应测试源集。
