# design

路径：`app/src/main/java/com/example/ui/design/`。

## 用途

应用设计令牌与共用视觉规范。

## 内容
- `DesignTokens.kt`
- `README.md`

## 维护提示

随 APK 打包的生产代码和资源应通过 `:app:assembleRelease` 验证；测试 fixtures 放在对应测试源集。

阅读器的固定字号、行高、圆角和间距复用 `DesignTokens`，固定玻璃叠色与小说主题色复用 `ReadingPalette`；动态强调色仍由主题提供。正文用户字号不套用界面字号。纯装饰标记使用 `PanelDecorativeIcon`，可点击行与单选项保留文字标签、角色和选中状态，避免重复朗读。`tools/ui-gate.ps1` 使用原有基线检查散落字面量增长；集中令牌仍计入统计。
