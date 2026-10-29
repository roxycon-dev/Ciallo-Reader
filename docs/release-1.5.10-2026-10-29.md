# Ciallo Reader 1.5.10 · 小说下载断点续传

版本 **1.5.10 / 252**，Android 7.0+，ARM64。

修复 [Issue #4](https://github.com/roxycon-dev/Ciallo-Reader/issues/4) 反馈的番茄小说下载在 Wi-Fi 与移动网络切换后从头开始的问题。

## 下载与恢复

- 公开小说逐章生成 EPUB 时，持久保存已完成章节与插图。网络中断、后台任务重新执行或手动暂停后，继续下载会复用已校验的内容，避免重新请求整本小说。
- 网络约束导致系统停止任务时保持可调度状态；网络恢复后继续。短暂连接错误及 HTTP 408、429、5xx 使用有限的指数退避重试。
- 手动暂停保留断点，取消任务或成功入库后清理。重新打包本地章节无需重复等待网络节流间隔。
- 断点绑定书源、下载地址和完整目录，目录发生变化时重新获取。保留下载完成前的目录复查及 EPUB 完整性检查，不把缺章、缺图或权限错误当作成功。

## 验证范围

21 项相关 JVM / Robolectric 回归通过，覆盖番茄生产适配器、章节中断与新任务续传、进度恢复、手动暂停、目录变化、损坏断点、插图缓存、网络约束停止后的恢复及 EPUB 完整性。详细记录见[本轮验证](issue-4-novel-download-resume-2026-10-29.md)。

尚未在真实手机上进行 Wi-Fi／移动网络切换测试，也未测量源站接口速度。本次减少的是中断后的重复下载耗时；首次下载仍受逐章接口响应和请求节流影响，不承诺所有源站速度提高。

## 下载与升级

[ARM64 APK](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.5.10/Ciallo-Reader-v1.5.10.apk) · [SHA-256](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.5.10/SHA256SUMS.txt) · [验证记录](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.5.10/validation.json)

沿用 1.5.6 起的固定证书，包名保持 `com.aistudio.novelreader.kxmpzq`，可覆盖升级 1.5.6–1.5.9。旧签名版本需要先备份再迁移，参见[签名说明](app-signing.md)。证书 SHA-256：`3ee5fb4018a00457eb24cbbfc43e78967d24069b760a5d386d6c9454b3770794`。

最终 APK 签名、16 KiB ZIP 对齐和相对 1.5.9 的静态升级条件检查通过，权限集合保持一致。未在真机重测覆盖安装。

APK SHA-256：`e0fa1a554e7f720b8ac3acc623260a9fddf92f527bd6a87d7283984670efb188`，29,287,732 字节。
