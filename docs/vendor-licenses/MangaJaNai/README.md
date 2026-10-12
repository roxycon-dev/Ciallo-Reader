# JaNai 模型

完整版使用 **2x MangaJaNai 1200p V1 ESRGAN 70k**，原作者 the-database，ONNX 转换来自 [haesslerian/MangaJaNai_V1_ONNX](https://huggingface.co/haesslerian/MangaJaNai_V1_ONNX/tree/2f67ff30209f2e0c75b7c55e6f3ffc3adbc960f7)。固定修订 `2f67ff30209f2e0c75b7c55e6f3ffc3adbc960f7`，67,191,396 字节，SHA-256 `c985d6b78ed1a659867cd5697ce80743ad9ddd73868f463e69c824f15f169eb0`。

1.3.6 通过 [pnnx 20260526](https://github.com/Tencent/ncnn/blob/master/tools/onnx/README.md) 转为 ncnn；原始 pixel-unshuffle 在 JNI 中按相同索引实现，351 层卷积权重和偏置逐字节一致，保持 FP32。下载包使用无损 ZIP，未量化、裁剪网络或降低推理输入分辨率。GPU 改用更大的分块，边缘上下文与浮点舍入可改变部分像素，不能称为旧分块输出逐像素完全相同。最终显示输出仍受既有 400 万像素 / 3200 长边限制。设备真实图块和密集测试块必须通过误差及速度校验，否则使用原 ONNX CPU 路径。

完整版约 76 MB 首次下载，CPU 和 Vulkan 格式都随独立模型包分发，APK 仅增加推理引擎。模型下载包随附署名与 [CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/) 许可链接，完整许可在 APK 内。转换复现：

```text
python tools/prepare-mangajanai-full.py ORIGINAL_MODEL.onnx PNNX_EXECUTABLE OUTPUT_DIR
```

以下轻量版继续保留，设置和结果缓存独立。

- 原始模型：[the-database/MangaJaNai](https://github.com/the-database/MangaJaNai)，[CC BY-NC 4.0 许可](LICENSE.txt)，包括署名与非商业使用要求。
- 当前使用原作者同仓库 [3.0.0 预发布](https://github.com/the-database/MangaJaNai/releases/tag/3.0.0) 中的 **IllustrationJaNai V3denoise SPAN-S 2x / 30k**，面向轻度劣化的彩色插画；与旧版 V1 黑白漫画 ESRGAN 的训练和效果不同。
- 固定修订：`d2f8f92`。原始归档 `IllustrationJaNai_V3denoise_onnx.zip`，SHA-256：`86d393d5c55a7ac0df986cd42e79a0351ecf89b370752f79d4fd0a9cf3f2aa58`。
- 上游文件：`2x_IllustrationJaNai_V3denoise_SPAN_S_30k_fp16_1x3xHxW_dyn-HW_strong_fp16_op23.onnx`，851,846 字节，SHA-256：`cd0344ebae36281206827954b730e0f944ce961dbbe059a41a083014f3e7c03a`。
- 移动端派生：原 FP16 权重和值转换为 FP32，兼容的 7 种算子改用相同定义的 opset 18，修正 2x 输出符号尺寸名称；未重新训练。模型随 APK 分发，1,673,288 字节，SHA-256：`718b8670ab396a5174e5b4b38f9d3947beaac3afbe9647d494d65925180a2a7b`。
- 转换脚本：[prepare-janai-mobile.py](../../../tools/prepare-janai-mobile.py)。INT8 实验有明显误差，未随 APK 分发。生产版本使用 FP32 CPU / XNNPACK。
- 图像处理已修改：按实测速度限制神经网络输入，再将网络修正量融合回原始细节；输入最多 90 万像素，128 px 核心与 24 px 边缘。速度优先会改变旧版画质，不能视为原 ESRGAN 等价优化。

完整许可和署名随 APK 分发；离线复制到应用私有目录并校验大小和 SHA-256，保留旧 V1 私有模型。原作者许可文件取自提交 `d2f8f92`。应用自身许可不覆盖此第三方模型。

复现：

```text
python tools/prepare-janai-mobile.py IllustrationJaNai_V3denoise_onnx.zip REPORT_DIR
```
