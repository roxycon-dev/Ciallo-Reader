# Wenku8 UI / 交互 / 动效移植方案

> 目标分支：`feature/wenku8-ui-port`（从当前 detached HEAD 工作树创建，不动 `main`）
> 参考文档：`docs/Wenku8_UI_前端实现分析.md`（commit ebcd7f1，GPL-3.0，仅借鉴设计语言与算法，不复制源码）

## 一、现有项目判断

- 技术栈：Kotlin / Jetpack Compose / Material 3 / Room / WorkManager；Android 原生，不是 Flutter。
- 现有主界面：`MainActivity` 五 Tab（书库、发现、书架、统计、设置）+ 浮动玻璃底栏；页面各自使用
  `GlassCard`、`TabScreenHeader`、`PullOut*` 系列抽纸动效。
- 现有阅读器：`ReaderScreen` / `PageTurnContainer` / `PageCurlReaderContainer` 已有自研分页、
  仿真卷页、触控热区、抽纸弹层。阅读器正文样式与业务逻辑本次不改。
- 现有降级：`RenderQuality`（极简/流畅/均衡/高/极致）、`LocalReduceMotion`、触觉开关、
  实时玻璃开关。高对比度与帧率上限需要补充。

## 二、技术栈映射

| 文档能力 | Flutter 实现 | 本项目映射 |
|-|-|-|
| 设计令牌 | `archive_design.dart` | `WenkuTokens.kt` + Material color scheme |
| 动效令牌 | `NovelMotion` | `WenkuMotion.kt`（同数值，Compose `CubicBezierEasing` / `SpringSpec`） |
| 列表揭示 | 自定义 `RenderObject` | `Modifier.wenkuReveal` 在 draw 阶段读动画进度，单控制器驱动 |
| 斜切平面转场 | `ArchivePlaneTransition` | `WenkuPlaneTabSwitcher`：双层 Compose，Path 斜切裁剪 + 位移 |
| 玻璃 | Impeller shader | 现有 `Backdrop` 真实模糊 + 中性纸面；不启用 specular/glow/dispersion |
| Hero 封面 | `PaperCoverRectTween` | 继续使用 SharedTransition，曲线/时长换为 1000ms 文档时间轴 |
| 3D 书架 | 自绘 RenderBox + cabinet projection | `ArchiveShelf` 自绘 Canvas + 命中/语义映射，公式按文档第 8 节 |
| Dock 跟手/形变 | 三个无界 AnimationController | `Animatable` + `VelocityTracker` + graphicsLayer，draw 阶段读取 |
| 阅读器翻页 | 620ms 时钟 + 提交物理 | 调 `PageTurnContainer` 的提交距离/速度阈值；保留现有卷页引擎 |
| 纸页卷曲 | `reader_paper_geometry.dart` 缺失 | 不伪造公式；现有 Harism 卷页保留，只统一时长/阈值 |

## 三、保留 / 移植 / 近似

### 保留
- 五 Tab 信息架构、所有页面入口、书源/下载/隐私/统计/设置业务。
- 阅读器正文排版、分页算法、选择/批注/词典/查找、TTS、漫画引擎。
- 品牌绿（用户主题色）仍作为强调色；只把大面积纸面与玻璃改为中性纸/墨。
- 现有真实毛玻璃采样链（Backdrop），不引入新的截图回读。

### 原样移植（数值）
- 色板：`#F4F3EF / #191919`、`#FDFCFA / #242424`、`#2D2D2D`、`#3A3A3A`、
  `#3C3C3C`、`#646B62 / #B7B7B7`。
- 圆角：内容面板 4，封面 3，图标按钮 24，文字按钮 22，玻璃 28。
- 时长：120 / 320 / 420 / 620 / 1000 / 840 / 340 / 300 / 440。
- 曲线：easeOutCubic、easeInCubic、`Cubic(.2,1.08,.34,1)`、`Cubic(.18,.72,.18,1)`。
- 弹簧：mass .64 / stiffness 270 / damping 19（换算为等阻尼比）。
- jelly、列表 8 带延迟、Dock 跟手 .8、速度形变、100ms 回弹、投射、3D 书架几何、
  阅读器提交物理常量。
- 控件规格：46×46 图标按钮、54 高药丸、最小 64×48 文字按钮、Sheet 拖拽阈值。

### 近似实现（标注差异）
- 玻璃折射：Compose/Backdrop 没有 Impeller 的解析玻璃 shader；使用现有实时高斯模糊 + 中性着色，
  关闭高光/辉光/色散。
- 斜切转场：Compose 没有 Flutter 的自定义 RenderObject 裁剪；用 `drawWithContent` 的 Path 裁剪
  与位移实现同一条斜切边，逐帧只触发重绘。
- 3D 书架：用 Canvas cabinet projection 自绘，封面纹理经 Coil painter 绘制；命中测试与语义
  由与绘制共用的几何表生成。若设备/画质降级则退化为横向封面轮播（中间放大）。
- 封面飞行：SharedTransition 不暴露 Flutter 的 18 次二分反解；保留系统 Hero 插值，只对齐时长与
  进入/退出曲线。
- 纸页卷曲：文档缺失 `reader_paper_geometry.dart`，保留现有卷页渲染；不冒充原公式。
- 帧率限制：Compose 无 Flutter 的全局 `TickerMode` 帧率设置；用系统“减少动效”、画质档位
  关闭装饰动画，并在高刷设备上限制到 60fps 的可选降级。

## 四、风险点

1. `MainActivity` 与 `HomeScreen` 体量大，改动必须局部化，避免影响阅读器。
2. 现有工作树有大量未提交改动，不能执行清理/回滚类命令。
3. 3D 书架坐标与命中/语义必须共用一份几何；否则会出现“看得见点不到”。
4. 玻璃卡片全局中性化可能影响阅读器内的 GlassCard，需用 CompositionLocal 限定主界面。
5. 构建使用 JDK 21 + Gradle 9.3 / AGP 8.7.3，存在版本兼容风险；先验证再改构建配置。
6. Debug 包使用 `.debug` 包名和内置 `debug.keystore`，与正式版签名不同，不覆盖用户数据。
