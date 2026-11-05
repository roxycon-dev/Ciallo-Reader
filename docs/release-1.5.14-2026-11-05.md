# Ciallo Reader 1.5.14 · 统一状态反馈

版本 **1.5.14 / 256**，2026-11-05，Android 7.0+，ARM64。

- 下载入口常态保持箭头，完成记录不会让它永久变成勾号。新完成任务短暂确认后恢复；暂停、失败显示小标记，按钮始终打开下载中心。
- 修复右上数量徽标裁切，调整为裁剪层外的稳定布局槽，容纳 1、两位数与 99+。
- 收藏、书签、底部栏、阅读器状态、展开箭头与章节选择采用共用过渡。播放/暂停缩短为一致节奏，路径复用。
- 加载指示统一为主题色圆角进度环，保留原控件尺寸与颜色参数；未知进度每个指示只保留一个绘制时钟。
- 图标按压共用反馈，开关跟随当前主题；恢复已喜欢记录不再触发新喜欢的动画和触觉。

保留现有页面布局、毛玻璃、渐变材质、抽纸形变和纸面弹簧参数。减少动态效果时禁用循环/过渡，交互和状态语义保留。

## 实现与参考

共用实现集中到 `AppMotion`、`StateIcons.kt`、`AppProgressIndicator.kt` 和 `PullOutIconButton`。图标过渡期间只暴露当前操作的无障碍描述；下载完成的事件时钟独立于进度/历史更新，防止确认动画重播或停留。

参考了 Telegram 的[媒体状态图标](https://github.com/DrKLO/Telegram/blob/master/TMessagesProj/src/main/java/org/telegram/ui/Components/MediaActionDrawable.java)和[径向进度](https://github.com/DrKLO/Telegram/blob/master/TMessagesProj/src/main/java/org/telegram/ui/Components/RadialProgress2.java)，也查阅了 [Compose 圆形下载按钮示例](https://github.com/MohamedRejeb/Animated-Circular-Download-Button)。本版为独立 Compose 绘制实现，没有引入第三方 UI 运行库。既有播放形变几何的 Apache-2.0 来源署名保留。

## 验证

- 46 项 JVM / Robolectric 检查通过，0 失败、错误或跳过，覆盖状态、历史、取消、完成时限、减少动画、单一操作语义与原有控件动作。
- 18 项 Android 15 模拟器设备回归通过：下载入口状态序列、1/12/99+ 徽标边界、深浅主题共用控件、书库/发现同一下载中心，以及原有抽纸反复开合、菜单跳转和阅读设置。
- 真实渲染截图核查常态、出场、下载、暂停、失败、短确认和恢复箭头；UI gate 沿用基线通过。最后稳定槽与主题控件的 3 项专项复查通过。

未连接实体手机，不把模拟器回归描述为特定机型的帧率结论。没有修改下载后端、书源协议、数据库 schema 或文件归属。机器可读验证见 [QA 摘要](qa-1.5.14.json)。

正式包：1.5.14 / 256，ARM64，29312108 B，SHA-256 `912baf6fce761df7c19336597c848a454f34e910527c1659bb3b15235feac684`。沿用现有新签名，与官方 1.5.13 的覆盖升级条件、ZIP CRC、16 KiB 对齐均通过；738 个生产输入与冻结清单一致。

最终收紧徽标底色后，46 项 JVM 检查及 3 项下载入口/共用控件设备复查再次通过；1、12、99+ 原生截图逐张核对。
