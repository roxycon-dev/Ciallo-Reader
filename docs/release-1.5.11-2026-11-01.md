# Ciallo Reader 1.5.11 · 批量导入本地书籍

版本 **1.5.11 / 253**，2026-11-01，Android 7.0+，ARM64。

实现 [Issue #5](https://github.com/roxycon-dev/Ciallo-Reader/issues/5) 提出的本地书籍批量导入。

## 使用方法

在书架选择目标分类，点击「导入新书」，在系统文件选择器内长按文件进入多选，再选择其余书籍并确认。也可以继续只选一本。不同系统文件管理器的多选按钮位置可能不同。

- 支持现有解析器已支持的本地书籍格式，复用单本导入的私有文件复制和事务保护。
- 逐本处理，显示已处理数量、总数和当前文件；避免同时解析多本大书造成内存峰值。
- 单本损坏或无法读取时继续其余文件；结束后汇总成功、失败数量，并展示最多五条失败详情。
- 全批次写入发起导入时选择的分类；重复返回的同一 URI 在当前批次仅处理一次。
- 导入状态放在 ViewModel，窗口旋转后继续显示；系统文件选择器打开期间保留目标分类。进程被系统终止后不会自动恢复未完成批次，可重新选择剩余文件。

## 下载与升级

[ARM64 APK](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.5.11/Ciallo-Reader-v1.5.11.apk) · [SHA-256](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.5.11/SHA256SUMS.txt) · [验证记录](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.5.11/validation.json)

沿用 1.5.6 起的固定证书及正式包名，可覆盖升级 1.5.6–1.5.10。更早签名版本请参见[迁移说明](app-signing.md)。

## 验证范围

**17 项 JVM／Robolectric 回归全部通过**，覆盖混合成功／失败批次、重复 URI、取消传播、空选择、失败详情数量限制，以及真实 TXT／损坏 EPUB 混合导入的数据库与分类结果。另运行现有导入安全回归。真机系统文件选择器多选和覆盖安装尚未实测。

最终 APK 签名、16 KiB ZIP 对齐及相对 1.5.10 的静态覆盖升级条件检查通过，权限集合保持一致。未在真机重测覆盖安装。

APK SHA-256：`4a0843d6e4186755d1cb103e37cc7ef745c3bb73f16018736d91804c6c59518f`，29,296,716 字节。
