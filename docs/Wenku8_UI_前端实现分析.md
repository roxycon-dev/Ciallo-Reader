# Wenku8 Flutter 前端实现分析

> 分析对象：`https://github.com/mikufoxxx/Wenku8\_Flutter`
> 本地快照：`C:/Users/GuanXingRen/Downloads/novel-reader (1)/novel-reader/docs/Wenku8\_UI\_前端实现分析.md`
> Commit：`ebcd7f1`（`Refine mobile activity layout and unavailable data states`）
> 版本：`0.5.6+2277`
> 许可证：GPL-3.0
> 规模：`lib/` 下约 278 个 Dart 文件，约 10 万行量级

\---

## 阅读说明

本文只做分析与复刻指导，不包含对原仓库代码的复制，也没有修改原仓库或你的项目。

文中的证据等级：

|标记|含义|
|-|-|
|✅|直接阅读源码确认，包含公式或常量|
|◐|通过文件结构、调用点、文档确认，未逐行展开|
|△|源码缺失或未完全展开，根据接口/上下文推断|

源码快照存在缺口，必须先说明，避免照着不存在的实现复刻：

|缺失文件|被谁引用|影响|
|-|-|-|
|`lib/reader\_paper\_geometry.dart`|`reader\_spine\_turn.dart:5`|纸页卷曲网格的精确公式不可得，只能推断接口契约|
|`lib/local\_book\_batch\_page.dart`|`local\_library\_page.dart`、`pages.dart`|本地书批量操作页不可见|
|`lib/reading\_report\_page.dart`|`reading\_statistics\_page.dart`|阅读报告页排版不可见|
|`lib/reading\_widget\_reference.dart`|`reading\_widget\_cards.dart` 等|阅读组件参考页不可见|
|`lib/reading\_widget\_reference\_capture.dart`|同上|参考页截图捕获逻辑不可见|

另外，文档存在滞后：`docs/ICON\_SYSTEM.md` 写 87 个图标，`ArchiveSymbol` 实际有 89 项。

\---

## 目录

1. [整体架构](#1-整体架构)
2. [设计系统](#2-设计系统)
3. [动效系统](#3-动效系统)
4. [玻璃材质系统](#4-玻璃材质系统)
5. [外壳、导航与全局交互](#5-外壳导航与全局交互)
6. [发现页](#6-发现页)
7. [书架页](#7-书架页)
8. [3D 书架算法](#8-3d-书架算法)
9. [详情页](#9-详情页)
10. [阅读器](#10-阅读器)
11. [二级页面与工具](#11-二级页面与工具)
12. [性能与可访问性](#12-性能与可访问性)
13. [复刻路线图](#13-复刻路线图)
14. [关键常量速查](#14-关键常量速查)
15. [关键文件地图](#15-关键文件地图)

\---

## 1\. 整体架构

### 1.1 技术栈

* Flutter / Dart SDK `^3.11.3`
* Material 3 + Cupertino 混合
* 自绘 `RenderObject` / `RenderBox`
* Impeller 运行时 shader（玻璃、模糊）
* 无 Provider / Riverpod / Bloc，状态层为 `ChangeNotifier` / `InheritedWidget` / `ValueNotifier`
* 依赖包括 `liquid\_glass\_easy`（本地 `third\_party`）、`tategaki`、`characters`、`flutter\_markdown\_plus`、`archive`、`html`、`csslib`、`just\_audio`、`audio\_service`

### 1.2 启动顺序

入口在 `lib/main.dart`：

1. `WidgetsFlutterBinding.ensureInitialized()`
2. 加载 `LibraryState`：书架、历史、设置、阅读偏好
3. 刷新动态调色板 / 系统色调
4. 初始化网络与仓库层（在线 `WenkuRepository` / 离线 `OfflineNovelRepository`）
5. `NativeEffects.prepare()`：预编译玻璃 shader 与高斯管线
6. 创建 `WenkuFeatures`、`ListeningController`（TTS）
7. 挂载 `NovelApp`

`main.dart` 内还有一个**路由队列**：启动早期收到的通知/深链目标不会立即压栈，而是等首帧和状态恢复完成后再推，避免路由早于 `Navigator` 就绪。

### 1.3 状态组织

* 页面自己持有 `AnimationController`
* 跨页面共享用 `ChangeNotifier`
* 主题、玻璃外观、性能开关、TickerMode 用 `InheritedWidget` 作用域
* 仓库层用 `Future` / `Stream` 驱动列表
* 阅读器内部有独立的排版缓存、分页缓存、相邻章节预取

### 1.4 工程特点

* 大量 `@visibleForTesting` 调试计数器（布局次数、着色器构建次数、几何计算次数）
* 关键动画路径都避免 `setState`
* 自绘控件同时处理绘制、命中、语义、裁剪
* 每个性能敏感模块都有编译期或运行期开关

\---

## 2\. 设计系统

### 2.1 色板

核心常量在 `lib/archive\_design.dart`：

|语义|亮色|暗色|
|-|-|-|
|背景|`#F4F3EF`|`#191919`|
|面板|`#FDFCFA`|`#242424`|
|乳白玻璃|`#FEFDF8`|`#F6F6F6`|
|抬起层|—|`#2D2D2D`|
|高抬起|—|`#3A3A3A`|
|描边|—|`#3C3C3C`|
|主墨|近黑|`#F2F2F2`|
|次墨|`#646B62`|`#B7B7B7`|

设计取向：

* 亮色偏暖灰纸，暗色是中性炭黑，不纯黑
* 面板圆角 `4`，封面圆角 `3`
* 没有大面积高饱和色，主题色只做点缀
* 纸张、档案、墨迹、玻璃是统一隐喻

### 2.2 `novelTheme`

`lib/app\_ui.dart` 中的 `novelTheme`：

* 默认 `ColorScheme.fromSeed(..., dynamicSchemeVariant: monochrome)`
* 主动覆盖 `primary` 为近黑/近白，避免 Material 3 主题色抢纸感
* `surfaceTint: Colors.transparent`，禁止色调叠加
* 暗色单独重写 `surfaceDim / surfaceContainer / outline` 等层级
* 支持三种调色板来源：

  * `original`：内置纸张色
  * `system`：Android 5 套系统色调
  * `custom`：用户选色，`tonalSpot` 生成
* 注册 `AppTonalColors` 主题扩展
* `inputDecorationTheme`、`snackBarTheme` 都跟随 `ArchiveDesign.panelRadius`

### 2.3 字体

* 展示字体：`ReadingDisplay` / `ReadingSerifDisplay`
* 正文：系统字体或下载字体
* 多套 CJK 字体资产，覆盖竖排、假名、注音
* `font\_catalog.dart` / `font\_download.dart` 管理字体列表与下载

### 2.4 图标系统

`lib/archive\_icons.dart` 定义 89 个 `ArchiveSymbol`，每项带中文标签与分组：

* 导航
* 阅读
* 听书
* 操作
* 文件
* 系统

图标不是直接使用 Cupertino 图标，而是：

* `CupertinoIcons` 到 `ArchiveSymbol` 的兼容映射
* `lib/archive\_icon\_art.dart` 用 `Path` / `cubicTo` 自绘
* 同一份路径同时用于 Flutter 绘制与 SVG 导出
* 保证展示图与真机一致

`lib/archive\_icon\_motion.dart` 定义了**三段纸片手势**：

```dart
duration = 640ms
lead    = spring(phase)
follow  = spring((phase - .055) / .945)
trail   = spring((phase - .11) / .89)

\_spring 关键帧：
times  = \[0, .10, .36, .64, .82, 1.0]
values = \[0, -.12, 1.0, -.20, .065, 0]
```

效果不是简单缩放，而是三张纸先后被拨动、回弹、停住。适合复刻到 Tab、操作按钮、图标切换。

\---

## 3\. 动效系统

### 3.1 `NovelMotion`

`lib/motion\_ui.dart`：

```dart
contact      = 120ms
enter        = 420ms
exit         = 320ms
coverFlight  = 1000ms
coverReturn  = 840ms

ease         = Curves.easeOutCubic
retract      = Curves.easeInCubic
settle       = Cubic(.2, 1.08, .34, 1)
spring       = SpringDescription(mass: .64, stiffness: 270, damping: 19)

jelly(t)     = sin(3πt) \* (1-t)^2
```

设计规则：

* 进入 420ms，退出 320ms，不对称
* `settle` 带约 8% 过冲，用于按压回弹、菜单展开、浮层落位
* `jelly` 只用于横向伸展，振幅随 `(1-t)^2` 衰减
* 弹簧阻尼比接近 1，几乎不振荡；弹性主要来自 `jelly`

### 3.2 列表入场

`LoadedContentSequence` + `LoadedContentItem`：

* 共享 `AnimationController`，时长 420ms
* 每行延迟 `start = clamp(order, 0, 8) \* .055`
* 结束 `start + .52`
* 最多 8 个延迟带，长列表不会等几秒
* `RevealFromTop`：裁剪矩形 + 位移

  * 纵向：`(-8, -6)`
  * 横向：`(-10, 0)`
* `LoadingHandoff` 负责骨架与真实内容交接
* 追加页面时旧批次变静态，不重播、不重挂载图片解码

### 3.3 `ArchiveRevealRender`

`lib/archive\_motion.dart`：

```dart
progress = Interval(start, end, curve: easeOutCubic).transform(t)
viewport = Rect(0, 0, w \* progress, h)       // 横向
displacement = travel \* (1 - progress)
```

* `pushClipRect` + `pushTransform`
* 复用 `LayerHandle`
* 动画帧不重新 layout
* `markNeedsSemanticsUpdate()` 只在跨过 `0.05` 阈值时触发
* 反向播放路径对称，不因状态改变跳变

### 3.4 区块/页面转场

`ArchivePlaneTransition`：

```dart
progress = Cubic(.18, .72, .18, 1).transform(t)

incoming: (-24\*d\*(1-p), 12\*(1-p))
outgoing: ( 10\*d\*p,      -4\*p)
sheet:    (-18\*(1-p), (H + clearance)\*(1-p))
```

裁剪路径是斜切平面：

```dart
slant = min(180, width \* .46)
edge  = (width + slant) \* progress
path:
  (0, 0)
  (edge, 0)
  (edge - slant, H)
  (0, H)
```

像一张斜放的纸从边缘推进，不是普通水平擦除。

### 3.5 Hero 封面飞行

`PaperFlightPose` 分三层：

* **travel**

```dart
  easeInOutCubic(((progress - .12) / .46).clamp(0, 1))
  ```

* **heroTimeline**

  * Hero 自身会加 `fastOutSlowIn`
  * 用 18 次二分反解 smoothstep
  * 再套 `Cubic(.4, 0, .2, 1)` 的 x 分量
  * 避免双重缓动
* **depthAt(row, column)**

```dart
  peel     = smooth((progress - .025 - row \* .11) / .11)
  landing  = ((progress - .58) / .42).clamp(0, 1)
  contact  = (1 - row) \* .66 + column \* .34
  floating = 1 - smooth((landing - contact \* .70) / .30)
  breeze   = sin((row \* 1.3 - progress \* 1.6) \* 2π)

  depth = peel \* floating \*
          (17 + 7\*sin(row\*π) + 3\*breeze\*sin(progress\*π))
  ```

* **lift**

```dart
  18 \* smooth(progress / .18) \*
  (1 - smooth((progress - .42) / .16))
  ```

* **roll**

```dart
  -.045 \* sin(progress \* π) \*
  (1 - smooth((progress - .42) / .16))
  ```

两端深度严格为 0，落地后没有持续 ticker。

\---

## 4\. 玻璃材质系统

### 4.1 `LiquidGlass`

`lib/app\_ui.dart`：

* 默认圆角 28，厚度 20
* 按下 `AnimatedScale` 到 `.97`
* 时长：进入 120ms，回弹 420ms
* 曲线：`NovelMotion.settle`
* 高对比度时退化为不透明 `surface`，厚度/模糊/发射全为 0
* 正常时：

```dart
  emission = (dark ? .024 : .010) \* (1 + .12 \* activation)
  tint     = GlassAppearance.tintOf(...)
  ```

* `splashFactory: NoSplash`
* 点击播放 haptic，驱动 `ArchiveIconContact`
* 没有发光描边、glyph shadow、mask blur

### 4.2 光学封装

`lib/easy\_glass.dart` 使用 `liquid\_glass\_easy`：

```dart
specular: intensity 0, thickness 0
glow:     intensity 0, thickness 0
dispersion: 0
refractionIndex: 1 + (n - 1) \* .055 + activation \* .006
refractionWidth: thickness
blur: blur
color: alphaBlend(white @ emission, tint)
```

关键选择：

* 关闭高光、辉光、色散
* 折射率只取真实玻璃偏移量的一小部分
* `activation` 只让折射率增加 `.006`
* 嵌套控件通过 `LiquidGlassBatch` 共享父级表面的一次采样
* `EasyScrollEdge` 用原生高斯 + 层内 alpha ramp 做滚动边缘渐隐

### 4.3 采样预算

`lib/glass\_sampling\_budget.dart` 会**证明**一块未激活圆角玻璃需要采样多大范围：

* 折射面按 128 个区间采样
* `slope(u) = tan(asin(u) - asin(eta\*u))`
* 用区间端点界定 `thickness \* (3 + z) \* slope`
* 加入 1-Lipschitz SDF、四分之一像素抗锯齿、相邻区间光线长度差
* 保守返回 `thickness \* 4 + 2` 以内的偏移
* 按 `(cornerRadius, thickness, refractiveIndex)` 缓存 64 条

滚动时玻璃只需改变原点，不需要重新计算采样范围。

### 4.4 渲染准备

`lib/native\_effects.dart`：

* 使用 `ui.ImageFilter.shader`（Impeller）
* 不做 CPU 位图模糊、不做屏幕截图
* 启动时生成 1024×1 的 sRGB→linear LUT
* 用两个 8 位通道保存 16 位精度
* 预热 `sigma = 3/8/16/32` 的高斯管线
* 预热 `analytic\_glass.frag` 与 `continuous\_blur.frag` 的真实组合滤镜
* 编译期开关：

  * `EASY\_LIQUID\_GLASS`
  * `NATIVE\_SCROLL\_EDGE\_BLUR`
  * `GLASS\_BATCH`
  * `GLASS\_CULL\_UNUSED\_PIXELS`
  * `GLASS\_HALO\_CACHE`

### 4.5 控件规格

|控件|规格|
|-|-|
|`GlassIconButton`|46×46，紧凑 48 宽，圆角 24，图标 21|
|`GlassPillButton`|高 54，厚度 16，主/次 7:5|
|`GlassTextButton`|圆角 22，最小 64×48；选中混色 10%，切换到 selection haptic|
|`ContentPanel`|圆角 4，0.7px 描边，按下 `.975`|
|`JellyTapSurface`|按下 `.985`|
|菜单|宽 248，浮层最大宽 440，遮罩黑色 16%|
|Sheet|拖拽关闭阈值：速度 > 550 或位移 > 高度 24%；把手 32×3；回弹 420ms|

### 4.6 封面

* `NovelCover`：解码宽度 `clamp(1, 1536)`，`ResizeImage`，`gaplessPlayback`
* `CoverArrival` 处理首帧到达
* `Hero` 使用 `PaperCoverRectTween`
* `CoverGlow`：灰度矩阵 + 30px 模糊 + 渐变遮罩

\---

## 5\. 外壳、导航与全局交互

### 5.1 `HomeShell`

`lib/pages.dart`：

* 底部 4 个 Tab：发现、书架、本地、我的
* 每个 Tab 独立 `ScrollController`，切换保留位置
* `\_visibleTabs` + `\_visitedTabs` 懒加载
* 顶部 `FrostedHeader` + `ArchiveHeaderBar`
* 底部 `GlassDock` + `DockUnderlay`
* 搜索展开：

  * 搜索框宽度 `46 + (maxWidth - 46) \* ease(t)`
  * 叠加 `jelly(t)` 横向摆动
  * Masthead 和 Dock 同时淡出
  * `IgnorePointer` + `ExcludeSemantics` 防止误触
* 三个搜索作用域：全站、书架、本地

### 5.2 头部与滚动

* `ArchiveHeaderBar`：标题居中，左右 gutter 对齐图标按钮
* `FrostedHeader`：toolbar 56 + feather 56
* `DockUnderlay`：底部向上的 progressive blur，不添加不透明 footer
* `NovelAndroidScrollPhysics`：

  * 保留 iOS 边缘回弹
  * `dragStartDistanceMotionThreshold = null`
  * `carriedMomentum = 0`
  * 去掉 Android 额外重启死区和惯性加速

### 5.3 底部 Dock

`DockLayout`：

```dart
itemWidth       = 184 / 3 ≈ 61.33
horizontalInset = 6
height          = iOS ? 50 : 54
compact         = iOS 横屏矮屏 ? 44 : height
```

`GlassDock` 的交互算法：

* 三个无界 `AnimationController`：`\_position`、`\_press`、`\_stretch`
* 一个 420ms 的 `\_membership` 控制显隐/重排
* 按下立即响应，不等 tap 识别：

```dart
  \_press.animateTo(1, duration: 120ms, curve: easeOutCubic)
  \_spring(\_position, \_fingerPosition(x))
  ```

* 拖拽跟手：

```dart
  \_position.value += (finger - \_position.value) \* .8
  ```

* 形变来自速度：

```dart
  target = (speed \* .028).clamp(-24, 24)
  \_stretch.value += (target - \_stretch.value) \* .55
  // 100ms 无输入后弹回 0
  ```

* 松手投射：

```dart
  projected = fingerPosition(lastX) + (velocity \* .055).clamp(-.22, .22)
  ```

* 整胶囊形变：

```dart
  translate(flow \* .16, -pressure \* .6)
  scaleX = 1 + pressure \* .022 + |flow| \* .00125
  scaleY = 1 + pressure \* .065 - |flow| \* .0006
  ```

* 玻璃 `thickness = 16 + pressure \* 2`
* `touchPosition` 按选中位置归一化
* 弹簧统一 `NovelMotion.spring`，速度 clamp `±5`

效果：胶囊会呼吸、会被手指带走、会因速度前后拉伸，静止时完全回到原位。

### 5.4 宽屏

`lib/library\_workspace.dart`：

```dart
wide = width >= 1000 \&\& height >= 500
```

进入双栏工作区：左侧目录/筛选，右侧内容；否则单栏。

\---

## 6\. 发现页

`lib/pages.dart` 中的 `DiscoverPage`。

### 6.1 结构

1. `LoadedContentSequence` 包裹内容
2. `ArchiveFileCarousel`：横向 3D 书架
3. `BookCollectionSliver`：列表/宫格
4. 分类、标签、出版状态、排序筛选
5. 无限加载
6. 空/错误/全屏蔽状态
7. `LibrarySkeleton` 同构骨架

### 6.2 加载

```dart
extentAfter < max(800, viewportDimension \* 1.5)
```

满足时 `loadMore()`。

### 6.3 状态隔离

筛选变化时用：

```dart
KeyedSubtree(
  key: ValueKey((sort, tags, publication, mode)),
  child: CustomScrollView(...)
)
```

避免旧滚动状态串页。

### 6.4 视觉

* 区块编号：`01 / SYNOPSIS`
* 返回最近更新按钮
* 拦截项计数与说明

\---

## 7\. 书架页

`lib/pages.dart` 中的 `ShelfPage`。

### 7.1 结构

* 上半：3D 书架预览
* 下半：列表/宫格
* 收藏数达到 `minimumBooks` 才显示 3D 书架
* 每行 `FutureBuilder` 解析元数据
* 未解析显示 `BookRowSkeleton` / `BookGridSkeleton`
* 每行包 `LoadedContentItem`
* 排序：`recentRead`、`recentAdded`、`title`
* 分区工具条：分类、筛选、视图、密度
* 独立搜索：书名、作者、ID

### 7.2 布局

* `BookCollectionLayout` 计算 cacheExtent 和列数
* `BookGridDensity` 支持自动/紧凑/舒适
* 列表/宫格切换不重建数据层

\---

## 8\. 3D 书架算法

实现集中在：

* `lib/archive\_wave.dart`
* `lib/archive\_shelf\_geometry.dart`
* `lib/archive\_scroll\_simulation.dart`
* `lib/archive\_shelf\_loading.dart`

### 8.1 几何常量

```dart
maxViewportWidth = 520
depthX = .66
depthY = -.30
rowStep = 2.5

spineFor(w)   = clamp(w \* .043, 14, 18)
pitchFor(w)   = spine + 11
coverFor(w)   = clamp(w \* .32, 100, 128)
bodyHeight(c) = (c - 20) \* 166 / 118 + 54
footprint     = spine + cover \* .66
pullDistance  = cover \* .84
baseY         = cover \* .30 + height \* .60 + 12
rowHeight     = baseY + height + 60
railY(d)      = d \* 2.5 + d² \* .45,  d ∈ \[-7, 7]
```

原点：

```dart
pull = pullDistance \* weight \* (1.24 - .24 \* weight)
lift = smoothstep(weight)

origin = (
  -pull \* .66,
  baseY - pull \* depthY - height \* .60 \* lift
)
```

封面不是平面，而是“书脊 + 封面 + 顶面”三个面共顶点，用 cabinet projection 拼出厚度。

### 8.2 波动

`\_WavePose`：

```dart
focus     = repeatFill ? edgeSlots + logicalFocus % count : logicalFocus
poolCount = count + edgeSlots \* 2
edgeSlots = ceil((w/2 + footprint + pull \* .66) / pitch) + 2

weight(i):
  d = |i - focus|
  if d >= 1.48 → 0
  w = cos(d / 1.48 \* π/2)
  return w²
```

* 中心书完全抬起
* 左右 1.48 个 pitch 内衰减到 0
* `cos²` 边缘过渡更软
* 每本书的“翻开程度”就是 weight

入场墨迹：

```dart
inkStart(i)    = clamp((i - focus + 8) / 24, 0, .65)
inkProgress(i) = Interval(inkStart, inkStart + .35, easeOutCubic)
```

加载悬浮：

```dart
phase = frameMicros / 1.7s - lane \* .075
crest = (1 + sin(phase \* 2π)) / 2
lift  = -9 \* crest² \* remaining
```

一条从左到右的波峰，加载完成后与真实墨迹共享同一个 pose。

### 8.3 渲染

`ArchiveBoxDepthRender` 每本书两个子节点：书脊、封面。

```dart
\_base:
  \[0,2] = .66
  \[1,2] = -.30
  \[1,0] = .10
  \[0,3] = origin.dx
  \[1,3] = origin.dy

\_coverTransform:
  \[0,0] = .66
  \[1,0] = -.30
  \[0,3] = origin.dx + spine
  \[1,3] = origin.dy + spine \* .10
```

顶面多边形：

```dart
origin
origin + (spine, spine \* .10)
origin + (spine, spine \* .10) + (cover \* .66, cover \* -.30)
origin + (cover \* .66, cover \* -.30)
```

细节：

* 顶面渐变：亮色 `#E3DFD3 → milk`，暗色 `darkRaisedHigh → darkRaised`
* 描边 0.4px，透明度 0.24
* 命中测试：封面/书脊 → 顶面路径
* `applyPaintTransform` 把矩阵传给子节点
* `LayerHandle<TransformLayer>` 复用图层
* 不可见书做 culling
* `semanticBounds` 用投影后的精确范围

### 8.4 滚动物理

`lib/archive\_scroll\_simulation.dart`：

* 摩擦：`v(t) = v0 \* e^(-4.4t)`
* 目标索引：`round((position + velocity / 4.4) / pitch)`
* 保护“跑道”，不允许落到当前可视区域之外
* 交接速度：`4 \* pitch`
* 落地：

```dart
  target - (target - handoff) \* remaining³
  dx = handoff \* remaining²
  ```

* 静止弹簧：`mass 1, stiffness 144, damping 24`
* 容差：`.01 / .1`

效果：书架甩得动、停得稳、不越界。

\---

## 9\. 详情页

`lib/pages.dart` 中的 `DetailPage`。

### 9.1 加载与 Hero

* `DetailLoaderPage` 先占住路由帧
* 等封面 Hero 飞行和详情数据完成后再替换内容
* 避免 Hero 中途换树

### 9.2 封面

* `CoverHeroTag`
* `PaperCoverRectTween`
* 详情页封面有透视、弯曲、遮挡参数

### 9.3 内容结构

* `ArchiveSection` 分块
* 编号：`01 / SYNOPSIS`
* 章节列表、简介、标签、相关作品、评论
* 同一个 `CustomScrollView`

### 9.4 底部动作 Dock

* 宽屏：次按钮:主按钮 = 5:7
* 窄屏或长文案：自动堆叠
* `glassActionDockGeometry` 统一计算高度和间距
* 主按钮状态：

  * 继续阅读
  * 开始阅读
  * 重试加载目录
  * 暂无可读章节
* 次按钮：

  * 加入书架
  * 移出书架
  * 配置本地书籍

\---

## 10\. 阅读器

阅读器是项目里最复杂的板块。入口在 `lib/pages.dart` 的 `ReaderPage`，核心渲染在 `lib/reader\_paged\_view.dart`。

### 10.1 阅读设置

`lib/reader\_preferences.dart`：

* 纸张
* 四边页边距
* 横排/竖排
* 翻页效果
* 翻页速度
* 字重
* 点击布局
* 段距、缩进、两端对齐
* 系统状态栏隐藏
* 页码显示
* 听书相关设置

### 10.2 分页算法

`lib/reader\_pagination.dart`：

1. 用 `ui.ParagraphBuilder` 按整页宽度排版每个段落
2. `paragraph.computeLineMetrics()` 获取所有行
3. 建立“源文本偏移 → 行号”映射：

```dart
   while (low < high) {
     middle = (low + high) \~/ 2;
     at = paragraph.getLineNumberAt(middle);
     if (at != null \&\& at < line) low = middle + 1;
     else high = middle;
   }
   ```

   注释说明：`getLineBoundary` 每次会复制整个原生行度量向量，代价高；二分 `getLineNumberAt` 更快，且能处理 bidi 和超大字形。

4. 每 4ms 主动 `await Future.delayed(Duration.zero)`，把长章节排版让出主线程
5. 测量结果按 `(content, width, style, mode, ...)` 缓存，最多保留 2 份
6. 维护：

   * `ReaderTextAnchor(paragraph, offset)`
   * 页首锚点
   * 图片/插图块
   * 竖排列缓存 `\_VerticalColumn` / `\_ReaderVerticalPageCache`
7. 恢复位置用 `pageFor(anchor)`，不用百分比
8. 排版设置变化时保留“当前阅读行在顶部”，通过 `pageBreakAnchor` 二分断行

### 10.3 竖排

`lib/reader\_vertical\_text.dart` 是专用 `RenderBox`：

* 按 grapheme cluster 生成 `ReaderVerticalGlyph`
* 处理注音、下划线、选择、命中测试
* 缓存字形到列/页
* 支持横排段落嵌入竖排
* 竖排的翻页方向、列顺序、点击方向都会反转

### 10.4 翻页容器

`ReaderPagedView`：

* `AnimationController` 固定 620ms
* `PageView`：

  * `pageSnapping: false`
  * 自定义 `ReaderPagePhysics`
  * 章节边界额外 1 个 edge slot
  * `clipBehavior: Clip.none`
* `\_frameSize` 大于实际视口
* 单页/双页由 `layout.count` 决定
* 双页：

```dart
  gap = (pages\[1].left - pages\[0].right) / 2
  ```

* 竖排：

```dart
  fromRight = direction > 0
  ```

  左右槽位映射翻转

### 10.5 六种翻页效果

|效果|时长|实现|
|-|-|-|
|`staggered` 段落接续|620ms|段落/列按空间顺序延迟平移，整页墨迹不淡出|
|`none` 无动画|0|直接跳页|
|`slide` 平移|300ms|整页 `SlideTransition`|
|`cover` 覆盖|340ms|新页从一侧滑入，旧页不动，边缘投影|
|`paper` 纸页翻动|440ms|Snapshot + 网格变形 + 纹理采样|
|`fade` 渐隐|300ms|两层纸张透明度交叉|

速度倍率：

* 舒缓 `1.35`
* 标准 `1.0`
* 利落 `0.7`

#### 段落接续（默认）

`lib/reader\_turn\_motion.dart`：

* 总时长 620ms
* 每个 item：

```dart
  start = (slot + .06 + .56 \* rank) / slots
  end   = start + .38 / slots
  curve = easeOutCubic
  ```

* `rank` 优先用空间位置：

  * 横排：`(outgoing ? top : bottom) / leafHeight`
  * 竖排：按列的 left/right 计算
  * 前进从右到左，后退反转
* 位移是整段墨迹平移：

```dart
  relay:
    distance = outgoing ? -t : 1 - t
    offset   = travelWidth \* direction \* distance

  非 relay:
    outgoing (-16, -4) \* t
    incoming (36, 8) \* (1 - t)
  ```

* `relay` 时透明度恒为 1，避免两层文字交叠发灰
* 拖拽时直接 scrub 同一个 `AnimationController`，松手不重播

效果：段落像被整体推走，而不是淡出。

#### 覆盖 / 渐隐 / 平移

`lib/reader\_sheet\_motion.dart`：

* `cover`：

  * 位移 `±width \* (1-p)` / `±width \* (-p)`
  * 边缘投影宽度 `min(22, width \* .055)`
  * 投影 alpha `.16`
* `fade`：

  * `opacity = incoming ? p : 1-p`
  * 整页绘制纸张底色后再绘制内容
* `slide`：

  * 章节切换用 `SlideTransition`
* 纸页折痕（`paper` 的简化分支）：

  * `bend = sign \* min(24, width \* .045) \* sin(πp)`
  * `fold = sign \* min(28, width \* .065) \* sin(πp)`
  * 阴影扩散 `min(26, width \* .07)`
  * 折痕渐变 stops `\[0, .48, 1]`

#### 纸页翻动

代码可确认的管线：

1. `ReaderSpineTurn` 用 `SnapshotWidget` 把当前页捕获成纹理
2. `ReaderPaperClock` 把 `animation.value` 和触摸 Y 坐标打包成一份 `ReaderPaperGeometry`，同一帧所有页共享
3. `ReaderPaperPainter` 计算：

   * 铰链位置 `\_hinge(size)`
   * 镜像矩阵 `translate(hinge, 0) \* scale(sign, 1)`
   * 可见区域 = 页面矩形减去遮挡路径
   * 用 `pushClipPath(true, ...)` 裁剪
4. 阴影：遮挡路径偏移 `(sign \* lift \* 3, 1)`，alpha `.20 \* lift`，模糊 `3 + lift \* 5`
5. 网格：`ui.Vertices.raw` + `ui.ImageShader` + `BlendMode.modulate`
6. 每个顶点颜色按法线做 `1 - |normal|` 的明暗
7. UV 在背面镜像
8. `FilterQuality.medium`
9. 单页背面 `ink alpha = .075 \* lift`

**缺口说明**：

`reader\_paper\_geometry.dart` 不存在，因此 `outline / positions / normals / uv / indices / lift` 的具体生成公式无法确认。根据调用方可以推断契约：

```dart
ReaderPaperMesh {
  positions,
  normals,
  uv,
  indices,
  outline,
}

ReaderPaperGeometry {
  front,
  back,
  frontInk,
  backInk,
  sheet,
  lift,
}
```

输入：

* `progress ∈ \[0, 1]`
* `touchY ∈ \[0, 1]`

输出：

* 正面网格
* 背面网格
* 正面墨迹网格
* 背面墨迹网格
* 遮挡轮廓
* 抬升量

复刻时需自行实现：

* 平面网格 → 沿对角线卷曲
* 以触摸点或页面边缘为轴
* 用 `sin` / `cubic` 计算每列 Z 位移
* 有限差分求法线
* 背面镜像 UV
* 用 `outline` 做裁剪和阴影

注意：不要照搬消费端源码当作完整实现，因为生成端缺失。

### 10.6 翻页物理

`lib/reader\_page\_physics.dart`：

```dart
commitDistance = clamp(viewport \* .1, 28, 48)

reversing = |v| >= 360 \&\& sign(v) != sign(distance)
fast      = |v| >= 360 \&\& |distance| >= 8
commit    = !reversing \&\&
            (|distance| >= commitDistance || fast)

destination = commit ? start + sign(distance) : start
```

* 速度门槛 360 px/s
* 距离门槛按视口 10%，夹在 28–48px
* 回拉速度足够大时，即使距离已过阈值也回原位
* 弹簧速度 clamp `±2400`，再乘速度倍率
* `\_ReaderPageSnap` 到达目标后直接钉住，避免过冲露出下一页文字
* 边界章节未准备好时，用 `pageBounds` 夹住滚动范围

### 10.7 阅读器控件

底部两层：

1. **进度卡**：章节标题、页码、进度条、上一章/下一章、查找
2. **玻璃 Dock**：目录、书签、外观、操作、听书等 6 个动作

动画：

* `ReaderChromeLayer` 从下方滑入

  * 进度卡：`Offset(0, 1.28)`
  * Dock：`Offset(0, 1)`
* 缩放从 `.90` / `.96` 到 1
* 曲线 `NovelMotion.settle`
* 进度卡和 Dock 使用不同 `Interval`，先出进度、后出 Dock

### 10.8 点击与触摸

`lib/pages.dart`：

```dart
x = localPosition.dx / width
y = localPosition.dy / height

standard:
  x < .28 → -1
  x > .72 → +1
  else     → 0

rightHand:
  x > .4 → (y < .3 ? -1 : +1)
  else   → 0

leftHand:
  x < .6 → (y < .3 ? -1 : +1)
  else   → 0

tapBothSidesNext:
  x < .28 || x > .72 → +1
  else              → 0
```

* 竖排标准布局方向反转
* 中央点击切换控件显隐
* 听书时中央点击有“可逆预览”：先临时显示控件，若手势被其他识别器赢走则回滚

### 10.9 自动滚动 / 自动翻页

* 分页模式用定时器逐页推进
* 滚动模式用 `\_autoScroll` 控制器
* 任意触摸、选择、打开控件会暂停
* 暂停后显示“继续自动滚动/翻页”玻璃按钮

### 10.10 触摸热区统计

`lib/reader\_touch\_surface.dart`：

* 只监听 `PointerDown`
* 不添加手势识别器，不消费事件
* 只记录触摸/手写笔，不记录鼠标
* 归一化坐标 `x/w, y/h`
* 记录横竖屏
* 退后台、键盘弹出、路由不在前台时 flush

用于触摸指纹和阅读热区分析。

### 10.11 滚动模式

相关文件：

* `reader\_scroll\_sliver.dart`
* `reader\_scroll\_stream.dart`
* `reader\_scroll\_index.dart`
* `reader\_scroll\_location.dart`
* `reader\_follow\_scroll.dart`
* `reader\_line\_reflow.dart`

机制：

* 当前章、上一章、下一章同时存在
* 滚动到边界增量加载
* 行高重排后通过锚点恢复位置
* 听书时滚动跟随当前朗读段落

### 10.12 选择、批注、词典、查找

* `reader\_selection.dart`
* `reader\_annotations.dart`
* `reader\_annotation\_editor.dart`
* `reader\_annotation\_range.dart`
* `reader\_dictionary.dart`
* `reader\_book\_find.dart`
* `reader\_find.dart`
* `reader\_underline.dart`
* `reader\_landing\_highlight.dart`
* `reader\_speech\_highlight.dart`

这些模块把文本选择、下划线、批注、词典、章内查找、听书高亮统一到同一套文本坐标系统。

\---

## 11\. 二级页面与工具

### 11.1 我的

`lib/my\_page.dart`：

* 统计入口
* 阅读记录
* 摘录
* 设置
* 云书库
* 账号
* 备份

档案条目式列表，配合 `ArchiveSection` 和玻璃按钮。

### 11.2 设置

`lib/pages.dart` 的 `SettingsPage` 是分组入口。具体页：

* `palette\_settings\_page.dart`：主题色、系统色、图片取色
* `font\_settings\_page.dart`：字体选择、下载
* `haptic\_settings\_page.dart`：触感强度/开关
* `frame\_rate\_settings.dart`：帧率
* `performance\_preferences.dart`：背景模糊、过渡动效开关

#### 图片取色

`lib/palette\_image.dart`：

1. 图片解码到最长边 256
2. 过滤 alpha < 128 的像素
3. 在 isolate 里运行 `QuantizerCelebi().quantize(pixels, 64)`
4. 找主色
5. `Score.score(quantized, desired: 6)` 生成 6 个候选种子
6. 点击图片任意位置可用 `colorAt()` 取像素色

### 11.3 阅读统计

`reading\_statistics\_page.dart` + `reading\_statistics\_charts.dart` + `reading\_statistics\_shared\_charts.dart`。

自绘图表：

* `\_RetainedBars`：柱状图
* `\_ActivityRingPainter`：`canvas.drawArc` 环形活动图
* `\_RhythmPainter`：阅读节奏折线/面积
* `\_CalendarPainter`：阅读日历热力图
* `CustomPainterSemantics`：图表读屏语义

`reading\_report\_page.dart` 缺失，报告页具体排版不可见；尺寸、字体、范围、选项模块可见。

### 11.4 阅读记录 / 摘录卡片

`lib/reading\_moment\_reveal.dart` 的 `ReadingMomentRevealPainter` 是一个“档案袋打开”动画：

* `opening = easeInOutCubic(phase(0, .45))`
* 档案袋先开
* 卡片以 `easeOutCubic(flight)` 向外飞出
* 摘录文字以 `easeOutCubic(burst)` 展开
* 自定义 `\_DossierRevealClipper` 裁剪

导出支持图片/分享，`reading\_moment\_export.dart`。

### 11.5 本地书库

`lib/local\_library\_page.dart`：

* 导入 TXT / EPUB 等
* 编码检测与转换
* 元数据编辑
* 封面提取
* 批量操作（`local\_book\_batch\_page.dart` 缺失）
* 导入设置、重复项处理、目录选择

### 11.6 词典 / 书评 / 更新

* `dictionary\_sheet.dart`：划词词典面板
* `wenku\_reviews.dart` / `review\_pages.dart`：书评列表与详情
* `updates/`：更新日志/版本信息

\---

## 12\. 性能与可访问性

### 12.1 动画不重建

* 3D 书架、纸页翻动、玻璃、揭示、加载墨迹全部用 `RenderObject` + `markNeedsPaint()`
* 动画帧中不调用 `setState`
* `RepaintBoundary` 包住文本页
* `TransformLayer` / `ClipPathLayer` / `OpacityLayer` 用 `LayerHandle` 复用
* 快照纹理只在翻页期间生成，结束即释放

### 12.2 性能开关

`lib/performance\_preferences.dart`：

* 关闭背景模糊 → 玻璃退化为纯色
* 关闭过渡动效 → `RevealFromTop` 直接返回 child
* 高对比度 → 玻璃变不透明
* `disableAnimations` / `TickerMode` → 动画归零
* 帧率设置可限制到 60/90/120

### 12.3 加载与内存

* 封面按目标宽度解码，最长边 1536
* 列表 `cacheExtent` 按列数和视口计算
* 长列表最多 8 个入场延迟带
* 图片取色在 isolate
* shader 启动预热
* 玻璃 halo 缓存
* 图表保留 `CustomPainter` 实例

### 12.4 无障碍

* 每本自绘书籍都有 `SemanticsConfiguration`

  * `isButton`
  * `isSelected`
  * `label`
  * `hint`
  * `onTap`
* 图表有 `CustomPainterSemantics`
* 玻璃控件保留 46/48px 命中区
* 高对比度、减少动效、文本缩放都有分支
* Dock 搜索展开时 `ExcludeSemantics`

\---

## 13\. 复刻路线图

按“收益 / 风险”排序。

### 第一优先级：设计令牌与动效令牌

先把这些落成常量：

* 颜色：纸张/墨/玻璃暗色层级
* 圆角：4 / 22 / 24 / 28
* 时长：120 / 300 / 320 / 420 / 620 / 1000
* 曲线：`easeOutCubic`、`easeInCubic`、`Cubic(.2,1.08,.34,1)`
* 弹簧：`mass .64 / stiffness 270 / damping 19`
* 列表延迟：`clamp(index, 0, 8) \* .055`，窗口 `.52`

这一步能解决“每个页面动效不一致”的问题。

### 第二优先级：玻璃材质 + 基础控件

* `LiquidGlass` 的退化策略
* 按下 `.97` + 120ms + `settle`
* 玻璃图标按钮 46×46、圆角 24
* 文字按钮最小 64×48
* 菜单/Sheet 拖拽关闭阈值
* 不要加高光描边、色散、glyph shadow

### 第三优先级：外壳与列表揭示

* 底部 4 Tab Dock：

  * 跟手 80%
  * 速度形变
  * 100ms 静止回弹
  * 速度投射
* 头部 56 + feather
* `LoadedContentSequence` + `RevealFromTop` + 骨架交接
* 滚动边缘 progressive blur
* 宽屏 1000×500 双栏

### 第四优先级：3D 书架

1. 静态几何：spine / cover / top 三面
2. weight 波动与 origin/lift
3. 滚动摩擦/吸附
4. 加载墨迹与无限循环

时间有限时，可先做“横向封面轮播 + 中间放大”，不要一上来就 cabinet projection。

### 第五优先级：阅读器

1. 分页锚点与排版缓存
2. 段落接续翻页
3. 翻页物理
4. 点击热区与中央控件切换
5. 覆盖/渐变/平移
6. 纸页翻动（最后做，且需自行实现缺失的网格）

### 第六优先级：二级页面

设置、统计、摘录、词典、书评等复用前面的令牌和控件即可。

\---

## 14\. 关键常量速查

|项目|值|
|-|-|
|进入 / 退出|420ms / 320ms|
|按下接触|120ms|
|翻页时钟|620ms|
|纸页翻动|440ms|
|覆盖 / 渐变 / 平移|340 / 300 / 300ms|
|速度倍率|1.35 / 1.0 / 0.7|
|弹簧|mass .64, stiffness 270, damping 19|
|落位曲线|`Cubic(.2, 1.08, .34, 1)`|
|Jelly|`sin(3πt)(1-t)²`|
|列表延迟|`clamp(order,0,8) \* .055`，窗口 `.52`|
|Dock 跟手|80%|
|Dock 形变|`speed \* .028`，clamp ±24，lerp .55|
|Dock 投射|`velocity \* .055`，clamp ±.22|
|翻页提交距离|`clamp(viewport\*.1, 28, 48)`|
|翻页速度门槛|360|
|书架摩擦|`e^-4.4t`|
|书架吸附|`Spring(mass 1, k 144, d 24)`|
|书架波半径|1.48 pitch|
|书架投影|depthX .66, depthY -.30|
|玻璃折射|`1 + (n-1)\*.055 + activation\*.006`|
|玻璃发射|亮 .010 / 暗 .024|
|玻璃厚度|16–20，按下 +2|
|图标手势|640ms，lead/follow/trail 三段|
|宽屏断点|1000×500|
|封面解码|最长边 ≤1536|
|图标数量|89|

\---

## 15\. 关键文件地图

### 入口与壳

|文件|作用|
|-|-|
|`lib/main.dart`|启动、状态装配、路由队列|
|`lib/app\_ui.dart`|主题、玻璃、按钮、菜单、Sheet、封面、路由|
|`lib/app\_interactions.dart`|`pushNovelPage` 等导航封装|
|`lib/pages.dart`|HomeShell、发现、书架、设置、搜索、详情、阅读器|
|`lib/library\_workspace.dart`|宽屏双栏工作区|
|`lib/dock\_layout.dart`|Dock 尺寸与滚动留白|
|`lib/glass\_dock.dart`|玻璃 Dock 交互|
|`lib/app\_haptics.dart`|触感词汇与开关|

### 动效与渲染

|文件|作用|
|-|-|
|`lib/motion\_ui.dart`|动效令牌、列表入场、纸页飞行、菜单伸展|
|`lib/archive\_motion.dart`|揭示裁剪、斜切转场|
|`lib/archive\_icon\_motion.dart`|图标三段纸片手势|
|`lib/archive\_icon\_art.dart`|图标路径与 SVG 导出|
|`lib/native\_effects.dart`|玻璃 shader、LUT、预热、批处理|
|`lib/easy\_glass.dart`|玻璃光学参数封装|
|`lib/glass\_sampling\_budget.dart`|玻璃采样范围证明与缓存|
|`lib/performance\_preferences.dart`|性能开关|
|`lib/frame\_rate\_settings.dart`|帧率设置|

### 3D 书架

|文件|作用|
|-|-|
|`lib/archive\_wave.dart`|3D 书架场景、书盒渲染、波动|
|`lib/archive\_shelf\_geometry.dart`|投影几何|
|`lib/archive\_scroll\_simulation.dart`|摩擦、吸附、弹簧|
|`lib/archive\_shelf\_loading.dart`|加载墨迹波|
|`lib/archive\_spine\_title.dart`|书脊文字|
|`lib/archive\_progress.dart`|传输/进度按钮|

### 阅读器

|文件|作用|
|-|-|
|`lib/reader\_paged\_view.dart`|分页容器、翻页、家具、效果分发|
|`lib/reader\_pagination.dart`|排版与分页、锚点|
|`lib/reader\_vertical\_text.dart`|竖排自绘|
|`lib/reader\_page\_physics.dart`|翻页物理|
|`lib/reader\_turn\_motion.dart`|段落接续时钟与位移|
|`lib/reader\_sheet\_motion.dart`|覆盖/渐隐/折痕|
|`lib/reader\_spine\_turn.dart`|纸页快照与网格绘制|
|`lib/reader\_paper\_geometry.dart`|**缺失**|
|`lib/reader\_preferences.dart`|阅读设置|
|`lib/reader\_scroll\_sliver.dart`|滚动模式|
|`lib/reader\_touch\_surface.dart`|触摸热区采样|
|`lib/reader\_selection.dart`|选择|
|`lib/reader\_annotations.dart`|批注|
|`lib/reader\_dictionary.dart`|词典|
|`lib/reader\_book\_find.dart`|章内查找|

### 其他

|文件|作用|
|-|-|
|`lib/palette\_image.dart`|图片取色|
|`lib/reading\_statistics\_charts.dart`|统计图表|
|`lib/reading\_moment\_reveal.dart`|摘录卡片揭示|
|`lib/local\_library\_page.dart`|本地书库|
|`lib/dictionary\_sheet.dart`|词典面板|
|`lib/wenku\_reviews.dart`|书评|
|`lib/book\_collection\_grid.dart`|书籍宫格|
|`lib/book\_grid\_density.dart`|网格密度|

\---

## 附录 A：复刻时的反模式

1. **不要每个页面各写一套动画曲线**

   * 统一到 `NovelMotion` 词汇
2. **不要用 `AnimatedOpacity` 做玻璃出现**

   * 玻璃应使用裁剪/位移；透明层会让背景采样变脏
3. **不要用 `setState` 驱动逐帧动画**

   * 用 `RenderObject` + `markNeedsPaint`
4. **不要给玻璃加高光描边和彩色色散**

   * 这个项目刻意关闭了 specular / glow / dispersion
5. **不要直接用 `PageView` 默认物理**

   * 它的提交距离、速度门槛、回拉逻辑都经过调校
6. **不要在自绘控件里只处理绘制**

   * 命中测试、语义、裁剪必须和绘制共用几何
7. **不要把 3D 书架做成真正的 3D 引擎**

   * cabinet projection 足够，成本低得多
8. **不要忽略退化路径**

   * 高对比度、减少动效、关模糊、TickerMode 都要有分支

\---

## 附录 B：许可证提醒

原仓库是 **GPL-3.0**。

* 直接复制代码到你的项目，会让你的项目受到 GPL 分发义务约束
* 学习算法、复刻交互、重写实现，与直接搬代码是不同性质
* 如果只是借鉴设计语言和动效节奏，建议独立实现
* 若要复用具体代码，请先确认你的项目许可证策略

\---

*文档结束。*

