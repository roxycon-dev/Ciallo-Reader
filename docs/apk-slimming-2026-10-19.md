# APK 资源清理记录（2026-10-19）

本轮目标是减小 APK，保留业务代码、历史兼容代码和功能。未清理宣传素材、项目缓存、历史安装包，也未升级依赖或修改版本号。

## 实测结果

对工作区当前同一份业务源码分别执行清理前、清理后的 `:app:assembleRelease`，均成功：

| 项目 | 字节数 |
| --- | ---: |
| 清理前 APK | 29,136,916 |
| 清理后 APK | 29,112,104 |
| APK 减少 | 24,812（约 24.2 KiB，0.085%） |
| 删除的图片源文件 | 42,895（10 个文件） |

正式版原本已经开启 R8 和资源压缩。旧图片在基线资源分析中均为 `reachable=false`，因此删除它们主要清理源目录；实际 APK 减重主要来自排除依赖附带的开发文件，不能将图片源文件大小计作 APK 减重。

## 删除的无引用图片

- `app/src/main/res/drawable/roxy_bookmark.xml`
- `app/src/main/res/drawable/roxy_happy.xml`
- `app/src/main/res/drawable/roxy_idle.xml`
- `app/src/main/res/drawable/roxy_move.xml`
- `app/src/main/res/drawable/roxy_sad.xml`
- `app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_legacy.webp`（5 份）

全项目源码、Manifest 和 XML 资源中未发现这些名称的引用，也未发现通过 `getIdentifier` 动态加载它们。当前看板娘仍使用 `mascot_*.webp`；启动图和现用应用图标保留。

## 排除的依赖打包残留

在 `app/build.gradle.kts` 的 `packaging.resources` 中，精确排除 `META-INF/README.md`、`kotlin-tooling-metadata.json` 和 `google/protobuf/*.proto`，实际移除 12 个 APK 条目。Protobuf 生成类和运行库保留，依赖源文件也未删除。

第三方授权文件、音频署名、服务注册、证书、词典、书源脚本、模型、原生库和基线配置文件均保留。开发工具的 `.mimosa` 目录在基线 APK 中本来就未打包，本轮未操作它。

## 验证

- 清理前后两次 `:app:assembleRelease` 均通过，包括正式版资源处理、R8、资源压缩、Lint Vital 和签名检查。
- 对 505 个现有业务及原生源码文件计算 SHA-256，内容全部未变；用户原有的未提交修改保留。
- 对比 APK 中 51 个运行资源和原生库，文件内容逐字节相同（构建生成的 `assets/dexopt/` 基线配置另计）。
- 现用 PNG、WebP 图片和 OTF 字体的内容及数量一致；两个 APK 的 ZIP 完整性检查通过。
- `apksigner verify --print-certs` 通过，证书 SHA-256 与清理前及 `signing/release-cert.sha256` 一致。
- 本轮未安装到设备验证交互行为。

输出 APK：`app/build/outputs/apk/release/app-release.apk`。本地对照数据保存在忽略目录 `artifacts/` 下的 `apk-slim-baseline.json`、`apk-slim-removed-resources.json` 和 `apk-slim-verification.json`；构建日志为 `apk-slim-baseline-build.log` 和 `apk-slim-build.log`。
