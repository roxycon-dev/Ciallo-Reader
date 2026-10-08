# Ciallo Reader 1.2.8 · 2026-10-09

版本 `1.2.8 / 209`，Android 7.0+、arm64-v8a，沿用既有正式签名，可覆盖安装 1.2.7 并保留数据。

修复增强大图放大后一直加载，四档画质增强及普通锐化统一重构：保留原始细字与线条，加入 EASU 边缘重建、保色抑噪、原始像素分块 CNN 和内置 ONNX CPU 图。当前页缓存优先，放大直接使用共用正式加载器；双页独立高清绘制，超时、失败和低内存均有可重试的结束状态。

详细算法、参考图质量数据与限制见 [增强重构记录](comic-enhancement-rebuild-2026-10-08.md)。280 项漫画 JVM / Robolectric 回归全部通过，UI gate 通过；发布准备时逐项核对已验证源码与资源，仅版本号发生变化。本轮按用户要求未运行模拟器或设备测试。

发布页：[v1.2.8](https://github.com/roxycon-dev/Ciallo-Reader/releases/tag/v1.2.8)。安装包和 SHA256SUMS.txt 由本次正式构建上传，最终构建数据见下方；完整回执归档 `artifacts/release-1.2.8/`。


## 正式构建验证

- Release 构建、R8 混淆与资源精简通过；安装包实际元数据为 `com.aistudio.novelreader.kxmpzq`、`1.2.8 / 209`、最低 API 24、仅 arm64-v8a。
- APK `24,094,767` B；原签名证书 SHA256 `d2115e3cc5880b210a120558aaab03eb30ee5de2952a80f3376dda7e45501146`，签名、ZIP CRC、16 KiB ZIP 对齐均通过。
- 两份内置 ONNX 图及 MIT 许可与源码字节一致；811 项构建输入在构建前后不变。
- 34 个套件、280 项 JVM / Robolectric 回归，无失败、错误或跳过。已验证源码和资源与本次构建一致，仅版本号递增；未重复宣称执行设备测试。
- APK SHA256：`8aeba76847513868157c6c8e5ab1e28a5f4f03eb871332b4e0ba7b597004379f`。GitHub 附件 `Ciallo-Reader-v1.2.8.apk` 与 `SHA256SUMS.txt`，上传后核对服务端大小和 SHA256。
