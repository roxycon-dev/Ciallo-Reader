# Ciallo Reader 1.5.5 · 2026-10-26

版本 **1.5.5 / 247**。

恢复原正式版包名 **`com.aistudio.novelreader.kxmpzq`**，沿用原固定签名，修正 1.5.4 改用对照包身份导致原正式版无法覆盖升级的问题。1.5.2 等同包名、同签名正式版可以直接覆盖安装，保留应用数据。不同历史签名的版本不适用。

## 保留的功能

- 高、极致画质保留现有抽纸变形、液态控件和挤压开关；均衡及以下使用普通控件与基础过渡。
- 保留 1.5.4 的启动异常保护与本地启动诊断导出。
- 成人源仍默认启用，安装权限、安装器调用和原更新安装流程保持不变。

## 对照包用户

包名为 **`com.roxycon.ciallo.reader.probe`** 的 1.5.3 对照包及 1.5.4，与原正式版是独立应用，无法相互覆盖。请先在「设置 → 设备互传与备份」导出，再安装本版并导入；确认数据迁移成功后再处理对照包。保留旧正式版的用户可直接更新原正式版。

## 构建与校验

使用原固定签名构建 ARM64 正式 APK，发布资产提供 SHA-256 与覆盖升级验证记录。本次按用户要求不进行报毒复测，不声称本版已经通过厂商安全检测。

[ARM64 APK](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.5.5/Ciallo-Reader-v1.5.5.apk) · [SHA-256](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.5.5/SHA256SUMS.txt) · [验证记录](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.5.5/validation.json)
