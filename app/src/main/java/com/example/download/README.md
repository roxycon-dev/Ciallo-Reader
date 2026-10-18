# download

路径：`app/src/main/java/com/example/download/`。

## 用途

小说下载任务、续传策略与文件检查。

## 内容
- `DownloadFileValidator.kt`
- `DownloadManager.kt`
- `DownloadProgressBroadcaster.kt`
- `DownloadRequest.kt`
- `DownloadState.kt`
- `DownloadTaskDao.kt`
- `DownloadTaskEntity.kt`
- `DownloadTransferPolicy.kt`
- `DownloadWorker.kt`
- `GuestNovelEpub.kt`：逐章生成含插图的 EPUB，任一章失败不入库
- `GuestNovelIntegrity.kt`：公开小说的文件 CRC、正文/插图引用、编码及可检测缺章检查
- `NovelDownloadStore.kt`
- 其余 2 项按名称和同层模块组织。

## 维护提示

随 APK 打包的生产代码和资源应通过 `:app:assembleRelease` 验证；测试 fixtures 放在对应测试源集。
