# mangatranslate

路径：`app/src/main/java/com/example/mangatranslate/`。

## 用途

漫画 OCR、气泡检测、机器翻译及模型内存控制。

识别继续使用手机端 PP-OCR / YOLO，按需下载模型；只把文字传给云端。翻译保留自定义大模型 API 与免费免 Key 腾讯机翻两个入口；免费机翻不是免 Key 大模型。

`TranslationFailure.kt` 提供不包含密钥、服务响应正文和接口地址的错误原因。永久拒绝立即停止，腾讯瞬时失败每批最多重试一次、整批 16 秒；大模型每批最多重试一次、整页 28 秒。OCR 结果与译文分开缓存，网络重试复用识别结果；过期预取取消，译名表按漫画隔离。大模型瞬时故障可降级机翻并显示原因，降级结果不写入 AI 译文缓存。

缩放到相同尺寸可能返回原 Bitmap；回收前必须检查对象身份。识别与气泡模型像素通过 `getPixels` 批量提取，避免逐像素 JNI 调用。检测异常不能伪装为无文字的成功页。

## 内容
- `BubbleDetector.kt`
- `BubblePipeline.kt`
- `LlmBubbleTranslator.kt`
- `MangaOcr.kt`
- `MangaTranslationCore.kt`
- `PageMemoryBudget.kt`
- `PageRegionDetector.kt`
- `PageRegionTiling.kt`
- `README.md`
- `TextBlockMerger.kt`
- `TranslationFailure.kt`
- 其余 3 项按名称和同层模块组织。

## 维护提示

随 APK 打包的生产代码和资源应通过 `:app:assembleRelease` 验证；测试 fixtures 放在对应测试源集。
