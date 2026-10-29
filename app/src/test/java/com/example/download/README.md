# download

路径：`app/src/test/java/com/example/download/`。

## 用途

续传、完整性检查、取消和下载入库测试。

## 内容
- `BackendDownloadPolicyTest.kt`
- `BackendDownloadWorkerTest.kt`
- `GuestNovelResumeTest.kt`：番茄逐章下载中断、暂停续传、目录变化、断点损坏及有限网络重试
- `CorruptedFileTest.kt`
- `README.md`

## 维护提示

记录回归输入和边界；模拟器或单次网络样本不能泛化为所有设备和源站。
