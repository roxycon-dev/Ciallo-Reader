# releases

路径：`releases/`。

## 用途

本机生成的 Android APK 交付副本；版本与架构以包元数据为准。此目录不代表文件已发布到 GitHub Releases。

## 当前版本

| 文件 | 版本 | 架构 | 大小 | SHA-256 |
| --- | --- | --- | ---: | --- |
| [`Ciallo-Reader-v1.2.4.apk`](Ciallo-Reader-v1.2.4.apk) | 1.2.4 / 205 | arm64-v8a | 约 23 MiB，以 Release 附件为准 | [SHA256SUMS.txt](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.2.4/SHA256SUMS.txt) |

构建产物已通过签名、ZIP CRC 与 16KB 对齐校验；签名沿用官方 1.2.3 证书，可以覆盖安装。49 项专项回归通过，设备测试代码编译通过；本次没有连接 Android 设备，未执行真机安装测试。下载见 [v1.2.4 Release](https://github.com/roxycon-dev/Ciallo-Reader/releases/tag/v1.2.4)。

## 内容
- `Ciallo-Reader-v1.2.4.apk` — 当前 Release 构建（本地交付副本，不纳入 Git）
- `README.md`
