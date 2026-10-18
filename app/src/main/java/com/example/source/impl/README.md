# impl

路径：`app/src/main/java/com/example/source/impl/`。

## 用途

内置小说或漫画源的站点适配。

## 内容
- `AutoNovelSource.kt`
- `GuestNovelTransport.kt`：公开请求的一次可取消网络重试
- `IxdzsSource.kt`
- `JsonBookSource.kt`
- `MangaDexSource.kt`
- `MockBookSource.kt`
- `PublicNovelSource.kt`：全本小说网、话本小说、SF轻小说的免登录公开正文；未通过整本审计的适配不注册
- `README.md`
- `Wenku8LibrarySource.kt`

## 维护提示

随 APK 打包的生产代码和资源应通过 `:app:assembleRelease` 验证；测试 fixtures 放在对应测试源集。
