# ncnn

采用 Tencent [ncnn 20260526 / e54f7b1](https://github.com/Tencent/ncnn/releases/tag/20260526) 官方 Android Vulkan shared 包，SDK 头文件和 arm64 / x86_64 `libncnn.so` 未修改。默认 APK 仅包含 arm64，`-PincludeX86` 可构建模拟器版。

归档 SHA-256：`eb205b332274974511890903828451ae7a4c19c309f21431536e0a8c9f3dd0c1`。使用 NDK 29；JNI 层由本项目实现，保持 FP32 并保存设备管线缓存。软件以 [BSD 3-Clause 及随附第三方条款](LICENSE.txt) 分发，许可证同时放入 APK。
