# releases

路径：`releases/`。

## 用途

本机生成的 Android APK 交付副本；版本与架构以包元数据为准。此目录不代表文件已发布到 GitHub Releases。

## 当前版本

| 文件 | 版本 | 架构 | 大小 | SHA-256 |
| --- | --- | --- | ---: | --- |
| [`Ciallo-Reader-v1.3.1.apk`](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.3.1/Ciallo-Reader-v1.3.1.apk) | 1.3.1 / 212 | arm64-v8a | 24,124,439 B | [SHA256SUMS.txt](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.3.1/SHA256SUMS.txt) |

构建产物已通过签名、ZIP CRC 与 16 KiB ZIP 对齐校验；签名沿用 1.3.0 证书，可以覆盖安装。47 个套件、327 项 JVM / Robolectric 回归通过，设备测试代码编译通过；本次未执行真机安装测试。[v1.3.1 Release](https://github.com/roxycon-dev/Ciallo-Reader/releases/tag/v1.3.1) · [发布验证](../docs/release-1.3.1-2026-10-11.md)。

## 内容
- `Ciallo-Reader-v1.3.1.apk` — 当前 Release 构建（本地交付副本，不纳入 Git）
- `SHA256SUMS-v1.3.1.txt` — 当前交付副本的 SHA-256
- `README.md`
