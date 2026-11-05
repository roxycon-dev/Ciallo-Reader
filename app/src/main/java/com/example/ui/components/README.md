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

## 1.4.15 轻量柔性路径

API 33+ 有实际来源的弹层在原窗口 RenderEffect 内使用单次内容采样；128 前向点生成复用的 64 行逆向表，AGSL 常量索引平衡树选择三次插值段。没有截图、来源隐藏或额外渲染器；停稳卸载效果，旧平台与全屏 / 小容器保留稳定路径。背景 Provider 必须通过 DrawScope.record 转移绘图上下文，不能用显式 density/layoutDirection 的底层重载调用外层 drawContent，否则隐藏来源会画到窗口上。

## 1.5.13 抽纸计时与背景过渡

`PaperSpring` 为解析解，每帧一次消费真实经过时间，不截断为 33 ms，也不补算多步。原弹簧参数、64 行逆表、128 前向分段和单次内容采样保留；固定的 flutter/shade 三角项复用。

`LocalPaperBackdropOwner` 对应窗口生命周期，避免面板子节点或嵌套窗口另起背景时钟。原生模糊、原生压暗及 Compose scrim 共用 `paperBackdropProgress` 的端点平滑曲线。`WindowBackdrop.kt` 中 `GlassWindowBlur` 采用各有效 owner 的最大半径，半物理像素去重并缓存至多 128 个 RenderEffect；子窗口退场不清父窗口。禁止重新在内容 SideEffect 中每次重组重置原生 dim、布局及窗口动画参数。

## 1.5.14 统一状态反馈

- `StateIcons.kt` 提供有限时长的状态图标、原位旋转的展开箭头和选中标记。动态图标在过渡时只暴露当前无障碍语义。
- `AppProgressIndicator.kt` 统一已知/未知进度：圆角端点、主题色、平滑进度，单个绘制时钟。`ChasingDots.kt` 保留旧入口兼容，旧 `ChasingDotsTransition.kt` 已删除。
- `PullOutIconButton` 与 `AppIconButton` 共用按压反馈；纸面来源矩形不随内部图标缩放。`PlayPauseMorph.kt` 复用路径并在绘制阶段读取进度。尺寸、材质、毛玻璃、抽纸形变及纸面弹簧参数继续保留。
- 动效令牌集中到 `AppMotion`，减少动态效果时跳过循环和形变。徽标必须放在图标按钮圆形裁剪层之外，并使用稳定布局槽，不用负偏移把文字挤出容器。
