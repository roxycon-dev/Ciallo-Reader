# impl

路径：`app/src/main/java/com/example/source/impl/`。

## 用途

内置小说或漫画源的站点适配。

## 内容
- `AutoNovelSource.kt`
- `GuestNovelTransport.kt`：公开 GET 的有限重试（最多两次），保持取消、权限错误不重试
- `FanqieGateway.kt`：番茄公开搜索与目录、书荒殿索引补充、张老师API正文；有限样本验证，不承诺全部作品或章节覆盖
- `IxdzsSource.kt`
- `JsonBookSource.kt`
- `MangaDexSource.kt`
- `MockBookSource.kt`
- `PublicNovelSource.kt`：全本小说网、话本小说、SF轻小说和番茄公开聚合；逐章下载遇到失败时拒绝生成残缺电子书，验证范围见对应审计报告
- `README.md`
- `Wenku8LibrarySource.kt`

## 维护提示

随 APK 打包的生产代码和资源应通过 `:app:assembleRelease` 验证；测试 fixtures 放在对应测试源集。
