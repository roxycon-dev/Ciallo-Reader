# components

路径：`app/src/main/java/com/example/ui/components/`。

## 用途

跨页面复用的 Compose 控件。

## 内容
- `AcrylicDialog.kt`
- `AppBottomTabBar.kt`
- `AppButton.kt`
- `AppErrorSnackbar.kt`
- `AppSnackbar.kt`
- `AppSwitch.kt`
- `AppToast.kt`
- `CardTweaks.kt`
- `ChasingDots.kt`
- `ChasingDotsTransition.kt`
- 其余 26 项按名称和同层模块组织。

## 维护提示

随 APK 打包的生产代码和资源应通过 `:app:assembleRelease` 验证；测试 fixtures 放在对应测试源集。

## 1.4.11 公共交互

所有滑动条使用 `LiquidSlider.kt`；弹层通过 `PullOutMotion.kt`、`PullOutHosts.kt` 和 `PullOutButtons.kt` 自动捕获触发来源并共享网格动效。参数集中于 `PullOutConfig` / `LiquidSliderConfig`，业务界面不单独定义动效或滑条样式。

## 1.4.12 纸面性能与时序

`PullOutMotion.kt` 负责来源与唯一物理生命周期，`PullOutRender.kt` 负责硬件网格和限幅的后台纹理准备；所有宿主继续共用封装。不得重新引入每帧软件位图、逐帧整棵面板重组或独立的关闭计时器。普通按钮仅登记坐标；关闭后先移除旧宿主再运行后续动作。

## 1.4.14 稳定性回退

以本节为准：抽纸已统一简化为 `StablePaperSurface` 常规图层过渡。`PullOutMotion.kt` 仅保留坐标来源、唯一弹簧时钟和内容生命周期；`PullOutRender.kt` 不再捕获或隐藏来源、不录制子显示列表、不读回位图。`PaperSnapshotCapture.kt` 已删除。业务调用和关闭器保持统一；不得为追求形变重新接管列表卡片的 RenderNode。
