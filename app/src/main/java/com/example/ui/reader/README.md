# reader

路径：`app/src/main/java/com/example/ui/reader/`。

## 用途

小说阅读器、排版和阅读设置 UI。

## 内容
- `NovelImageFullscreen.kt`
- `NovelInlineImages.kt`
- `README.md`

## 维护提示

随 APK 打包的生产代码和资源应通过 `:app:assembleRelease` 验证；测试 fixtures 放在对应测试源集。

## 1.4.11 触控分区

`TouchZones.kt` 保存模型与手势判定；`TouchZoneGestures.kt` 仲裁小说及漫画的自定义手势；`TouchZoneEditor.kt` 提供矩形、范围条及动态校准。未自定义的布局沿用原有引擎。

编辑层直接叠加在原阅读场景上，按翻页引擎真实 viewport 的 window 坐标定位，工具不参与正文排版。试读临时使用草稿，取消不写入持久配置；漫画编辑入口先关闭设置面板再显示同层编辑器。
