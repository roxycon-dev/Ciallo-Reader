# releases

路径：`releases/`。

## 用途

本机生成的 Android APK 交付副本；版本与架构以包元数据为准。此目录不代表文件已发布到 GitHub Releases。

## 当前版本

| 文件 | 版本 | 架构 | 大小 | SHA-256 |
| --- | --- | --- | ---: | --- |
| [`Ciallo-Reader-v1.3.0.apk`](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.3.0/Ciallo-Reader-v1.3.0.apk) | 1.3.0 / 211 | arm64-v8a | 24,099,067 B | [SHA256SUMS.txt](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.3.0/SHA256SUMS.txt) |

构建产物已通过签名、ZIP CRC 与 16 KiB ZIP 对齐校验；签名沿用 1.2.9 证书，可以覆盖安装。290 项漫画回归通过，设备测试代码编译通过；本次未执行真机安装测试。[v1.3.0 Release](https://github.com/roxycon-dev/Ciallo-Reader/releases/tag/v1.3.0) · [修复与验证](../docs/comic-curl-enhancement-paging-1.3.0-2026-10-09.md)。

## 内容
- `Ciallo-Reader-v1.3.0.apk` — 当前 Release 构建（本地交付副本，不纳入 Git）
- `SHA256SUMS-v1.3.0.txt` — 当前交付副本的 SHA-256
- `README.md`
