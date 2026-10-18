# source

路径：`app/src/androidTest/java/com/example/source/`。

## 用途

漫画与小说书源、网络、JS 生命周期和解析规则测试。

## 内容
- `js/`
- `AutoNovelSourceDeviceTest.kt`
- `IxdzsSourceDeviceTest.kt`
- `PublicNovelSourceDeviceTest.kt`：真实搜索、正式 WorkManager 整本下载、Room 入库及逐段内容核对；使用 `livePublicNovel=true`
- `GuestNovelIntegrityDeviceTest.kt`：缺章、空章、乱码和损坏/缺失插图的拒绝路径
- `PublicNovelReaderDeviceTest.kt`：下载后实际本地阅读器显示与截图；须先完成实网下载用例
- `NovelSearchInteractionDeviceTest.kt`
- `README.md`
- `SourceIsolationDeviceTest.kt`
- `Wenku8LibraryDeviceTest.kt`
- `WholeBookNovelUpdateDeviceTest.kt`
- `ZLibraryPublicDetailDeviceTest.kt`

## 维护提示

记录回归输入和边界；模拟器或单次网络样本不能泛化为所有设备和源站。
