# JaNai 移动版

- 原始模型：[the-database/MangaJaNai](https://github.com/the-database/MangaJaNai)，[CC BY-NC 4.0 许可](LICENSE.txt)，包括署名与非商业使用要求。
- 当前使用原作者同仓库 [3.0.0 预发布](https://github.com/the-database/MangaJaNai/releases/tag/3.0.0) 中的 **IllustrationJaNai V3denoise SPAN-S 2x / 30k**，面向轻度劣化的彩色插画；与旧版 V1 黑白漫画 ESRGAN 的训练和效果不同。
- 固定修订：`d2f8f92`。原始归档 `IllustrationJaNai_V3denoise_onnx.zip`，SHA-256：`86d393d5c55a7ac0df986cd42e79a0351ecf89b370752f79d4fd0a9cf3f2aa58`。
- 上游文件：`2x_IllustrationJaNai_V3denoise_SPAN_S_30k_fp16_1x3xHxW_dyn-HW_strong_fp16_op23.onnx`，851,846 字节，SHA-256：`cd0344ebae36281206827954b730e0f944ce961dbbe059a41a083014f3e7c03a`。
- 移动端派生：原 FP16 权重和值转换为 FP32，兼容的 7 种算子改用相同定义的 opset 18，修正 2x 输出符号尺寸名称；未重新训练。模型随 APK 分发，1,673,288 字节，SHA-256：`718b8670ab396a5174e5b4b38f9d3947beaac3afbe9647d494d65925180a2a7b`。
- 转换脚本：[prepare-janai-mobile.py](../../../tools/prepare-janai-mobile.py)。INT8 实验有明显误差，未随 APK 分发。生产版本使用 FP32 CPU / XNNPACK。
- 图像处理已修改：按实测速度限制神经网络输入，再将网络修正量融合回原始细节；输入最多 90 万像素，128 px 核心与 24 px 边缘。速度优先会改变旧版画质，不能视为原 ESRGAN 等价优化。

完整许可和署名随 APK 分发；离线复制到应用私有目录并校验大小和 SHA-256，校验通过后清理旧 V1 私有模型。原作者许可文件取自提交 `d2f8f92`。应用自身许可不覆盖此第三方模型。

复现：

```text
python tools/prepare-janai-mobile.py IllustrationJaNai_V3denoise_onnx.zip REPORT_DIR
```
