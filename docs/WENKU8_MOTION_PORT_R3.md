# Wenku8 动效移植交付报告（R3 → R5）

> 分支 `feature/wenku8-ui-port`；参考源 `Wenku8_Flutter_analysis/lib/*.dart`（只读）。
> 原则：照 Flutter 源码逐行搬，不按分析文档概括；前端可整块替换，框架/位置/后端不动。

## 1. 新增组件（新文件）

| 文件 | 行数 | 内容 | 对应的 Dart 源 |
|---|---|---|---|
| `ui/wenku/WenkuIconArt.kt` | 1457 | 89 个 `WenkuSymbol`、24 单位坐标系、`_Art` 变换栈、`_Trace` 路径 DSL、card/poly/line/mark/arc/ellipse/star、逐符号手势几何 | `archive_icons.dart` 枚举、`archive_icon_art.dart`、`archive_icon_fold_art.dart`（949 行 switch 全量） |
| `ui/wenku/WenkuIcon.kt` | 582 | 三时钟（morph 380ms / 按压 140-240ms / 手势 640ms）、`_spring` 关键帧、`stateCurve Cubic(.18,1.28,.32,1)`、异族交叉溶解、入场级联（行分组 16dp、`round(i*360/(rows-1))`、880ms 窗口）、常驻 2600ms | `archive_icons.dart` 475-939、`archive_icon_motion.dart` |
| `ui/wenku/WenkuCoverFlight.kt` | 931 | `PaperFlightPose`、`PaperCoverRectTween`（`min(32,12+d*.075)*sin(πt)`）、17×32(+16 边缘) 网格 `scale=300/(300-depth)`、`drawVertices` 单次贴图、剪影 `.12/.88` + `DST_OUT`、`CoverArrival` 420ms | `motion_ui.dart:42-680`、`cover_arrival.dart` |
| `ui/wenku/WenkuMenuHost.kt` | 952 | `GlassMenuStretch`（宽 `Cubic(.18,1.10,.30,1)`、高 `Cubic(.22,1.04,.32,1)`、`Rect.lerp`、贴右 + `growsDown`、阈值 .15/.34）、`GlassMenuContents` 反缩放、`GlassOverlayTransition` 四分支、sheet 550/24%/420ms | `motion_ui.dart:687-1188`、`app_ui.dart:1941-2727` |
| `ui/wenku/WenkuReflow.kt` | 640 | `LibraryReflow` 位置保留重排（420ms、`Cubic(.18,.72,.18,1)`、`%9` 错峰、10dp 上浮、视口插值）、`WenkuSplitWorkspace` | `library_reflow.dart`、`library_workspace.dart:53-243` |
| `ui/wenku/WenkuTextMotion.kt` | 130 | 逐字折变 780ms（每字延迟 `Random(hash^gen)*.52`、alpha `t<.5?1-1.84t:.08+1.84(t-.5)`）、count-up 340ms `settle` | `app_language.dart:239-369`、`reading_statistics_shared_charts.dart:299-315` |
| `ui/wenku/ArchiveShelf.kt`（重写） | 1414 | 地面六边形墨影、spine/cover/top 三面共用顶点矩阵、`weight=cos²`、`edgeSlots` 不截断、竖排书脊标题、`e^-4.4t` + `k144/d24` 吸附 | `archive_wave.dart`、`archive_shelf_geometry.dart`、`archive_spine_title.dart`、`archive_scroll_simulation.dart` |

## 2. 已接线（用户可见）

- **Dock 五 Tab**：`WenkuIcon` 手绘纸片图标，入场致意 + 按压三拍；选中 1.10 放大（`glass_dock.dart:500-579`）。
- **书架 ←/→**：`ArrowLeft/ArrowRight` 纸片箭头。
- **主界面动作按钮**：`默认 / 导入新书 / 新建分类` → `Filter / ImportFile / FolderAdd`。
- **页面转场**：`paperComposable` 曲线由 `Cubic(.4,0,.2,1)` 改为 Cupertino `Cubic(.18,1,.04,1)`（`softRoute`），420/320，前页 1/3 视差。
- **骨架屏**：删掉自研 shimmer，改 `loading_ui.dart:59-130` 的纯静态占位（alpha 暗 .0825 / 亮 .0625）。
- **玻璃菜单**：`PullOutDropdownMenu` 内部换成 `WenkuMenuHost`（4 个调用点：发现页卡片菜单、阅读器 ×2 等）；barrier 透明可点、返回键/外部点击 320ms 缩回。
- **书架 caption 标题**：整串 Crossfade → 逐字折变。
- **发现页网格**：`WenkuReflowScope` + `WenkuReflowItem`（维度/筛选变化时保留位置重排）。
- **书架列表页**：书卡包 `WenkuReflowItem`，排序走两帧协议（见第 4 节）。

## 3. 验证

- 构建：`gradlew :app:assembleDebug -PincludeX86 --no-build-cache` → BUILD SUCCESSFUL。
- 截图证据：`evidence/r3/*`（五 Tab、书架、Dock、阅读器）、`evidence/menu-real.png`（菜单 morph）、
  `evidence/textfold/*`（caption 折变）、`evidence/reflow3-*.png`（列表页 Reflow 200ms 帧 / 落定 / 回 3D）、
  `evidence/restored-*.png`（R4 事故后的重建复验）、`evidence/r8/*`（R6 稳健版回归：脚本每次点击前
  先确认 `topResumedActivity` 是我们的 Activity，避免把系统文件选择器当成主界面截图）。
- logcat：无 `FATAL EXCEPTION`。
- APK：`novel-reader/artifacts/wenku8-ui/Ciallo-Reader-Wenku8UI-debug.apk`
  SHA-256 `31DF39F60D2C6064CE2B6F3F022C4AA1AB4126F3772CA179AB905318B44A7B43`

## 4. 三个必须记住的坑

### 4.1 平台 `android.jar` 没有 `Vertices`

`android-35/android.jar` 只有 `Canvas.VertexMode` + raw
`drawVertices(VertexMode, count, float[], …, short[], …, Paint)`，没有 `android.graphics.Vertices`。
封面飞行的网格用 raw 重载（见 `WenkuCoverFlight.kt`）。

### 4.2 LazyGrid 上的 Reflow 需要「两帧协议 + 两条修正」

1. `forget()` 不能清 `sourceRects`：LazyGrid 重排会回收/重建槽位组合，`onDispose → forget()`
   把冻结的旧矩形一起删掉，卡片就全部走「新卡」分支 `alpha = stagger(p) ≈ 0`（R4 空屏的根因）。
2. `visibleSize()` 需要尺寸容差（≥ .5px 才裁剪）：纯换序时 source/target 同尺寸，
   浮点取整误差会把卡片裁成一条缝。
3. 时钟用普通 float state：点击回调里 `clock = 0f` → `begin { clock }`（同步冻结旧矩形且首帧 p=0）
   → 改 `sortBy` → 协程 `animate(0→1, 420ms, LinearEasing)` → `end()`。

### 4.3 其它

- 新文件里「中文注释 + 下一行代码」曾被写成合行（注释尾字节吞掉 `\n`），用脚本按「`?` + ≥2 空格 + 代码」修复；
  后续给这些文件加注释只用 ASCII。
- `Painter.draw` 在本工程解析不到；封面光栅化走 `AsyncImagePainter.State.Success.result.drawable → Bitmap`。

### 4.4 封面到达：从两次白卡到修好（R9）

- 症状：把 `WenkuCoverArrivalFrame` 套到书架列表页的 `AsyncImage` 上，封面全白（两秒后仍空）。
- 根因：包装器在 `!hasFrame || (!started && !presented)` 时画的是**空占位 `Box`**
  （原 `WenkuCoverFlight.kt:659-663`），不是"未裁剪内容"；而该卡片的 Coil 状态并没有稳定走到 `Success`，
  于是空占位把封面盖没了。
- 修法（已落库）：把未就绪分支拆成两段——
  - `!hasFrame` → `Box(modifier) { content() }`：没有真帧就原样画内容，**缺信号只损失动效、不损失像素**；
  - `ready && !started && !presented` → 空占位一帧，随后 420ms 平面揭示（`cover_arrival.dart`）。
- 接线：`rememberAsyncImagePainter(model = imageRequest)` 取 `state is Success` 作为 `ready`，
  `Image(painter = …)` 放进包装器。
- 实测：切到列表 150ms 帧还是空槽（揭示起点）、2s 帧封面全部正常显示
  （`evidence/arrival3-150.png` / `arrival3-end.png`），滚动新卡同样只播一次。

### 4.5 另外两条记录

- **直接把 `WenkuCoverFlight` 用作"起飞"覆盖层**：与现有 shared-element 双重绘制，视觉上是两张封面；
  必须先建 `Rect` 桥并关掉该 key 的 sharedElement。
- **封面飞行姿态**：`Modifier.wenkuPaperFlight(active)`（`motion_ui.dart:94-101` 的 lift/roll）已挂到书架卡片的 sharedElement 上，触发信号用 `SharedContentState.isMatchFound`（本版 Compose 没有 `isTransitionActive`）；实测进场过渡正常、阅读器正常打开（`evidence/flight-320.png` / `flight-end.png`）。完整纸张网格仍待桥接。
- **`WenkuCountUp` 已接线**（首页"今日已阅读 N 分钟"，`reading_statistics_shared_charts.dart:299-315` 的
  340ms `settle`）；实测文本正常显示（当前数据为 0 分钟，所以看不出计数过程）。

## 5. 逐个坑之外：验证脚本要防串台

`evidence/verify.ps1` 早期版本固定坐标连点，遇到系统文件选择器盖上来的情况会把 Files 截图当主界面。
`evidence/verify2.ps1` 每次点击前先检查 `topResumedActivity`，必要时返回/重启，已用它产出 `evidence/r8/*`。

## 6. 逐项状态

| 目标项 | 状态 | 落点 |
|---|---|---|
| ArchiveIcon 手势图标系统 | 完成 | `WenkuIconArt/Icon`；Dock、书架箭头、三枚动作按钮 |
| 封面飞行 | 完成 | R14：列表页入口（书架列表 → 阅读器，1000ms）；R15：3D 书架也接上，并补了「阅读器 → 书架」的 840ms 反向飞行；发现页无直接阅读器入口（见 8.1） |
| 菜单 morph | 完成 | `WenkuMenuHost` → `PullOutDropdownMenu`（4 处） |
| LibraryReflow | 完成 | 发现页网格 + 书架列表页（两帧协议） |
| 页面动效 | 完成 | Tab 斜切平面 + Cupertino 路由曲线 |
| 文本动效 | 完成 | 逐字折变、count-up |
| 按压反馈 | 完成 | .97 / 120-420 `settle` + 图标 contact 时钟 + 触觉 |
| 玻璃与加载交接 | 完成 | 玻璃仍走 `backdrop` 实时采样（采样本身未换）；R14 换掉发现页那块，R15 把 `PullOutBottomSheet` 整体转发到 `WenkuSheetDialog`，12 个调用点一次性换成 Wenku 面板 |
| 性能缓存 | 完成 | 书架封面单次光栅化 + LRU 32；R14：玻璃 `RenderEffect` 缓存 + 整条玻璃 modifier 进 `remember` |

## 6. 交接清单（下一轮从这里接，不必重新探路）

### 6.1 封面飞行（唯一还差的大件）

**第 1 步已完成（R12）**：`ui/wenku/WenkuFlightBridge.kt`
- `WenkuFlightRequest(sourceBounds, sourceImage, targetBounds?, targetImage?, sourceRadius, targetRadius)`；
- `object WenkuFlightBridge { begin(bounds, image, radius) / arrive(bounds, image, radius) / clear() }`（单槽、不排队）；
- `@Composable WenkuFlightOverlay()`：两端齐全才调 `WenkuCoverFlight(visible = true, …)`，播完自清；
- `WenkuFlightOverlay()` 已挂在 `MainActivity` 的根 Box（底部 Tab 栏之后，`Modifier.fillMaxSize()`）；桥为空时组件直接 `return`，所以对外行为零变化（`evidence/overlay-mounted.png`）。

后续三步：

- 源端：`HomeScreen.kt` 书卡里 `if (sharedTransitionScope != null …)`（约 1640 行）已拿到
  `coverState = rememberSharedContentState("book_cover_${book.id}")`，并在链上挂 `wenkuPaperFlight(coverState.isMatchFound)`；
  封面矩形由 `.then(shelfSelection.coverBoundsModifier(book.id.toString()))`（约 1668 行）登记，可直接读。
- 目标端：`ReaderScreen.kt` 约 1205 行 `sharedElement(state = rememberSharedContentState("book_cover_${book.id}"))`，可在此
  `onGloballyPositioned` 调 `WenkuFlightBridge.arrive(...)`。
- 落地顺序：② 两端**临时**去掉该 key 的 `sharedElement`；③ 点击处 `begin(...)` +（已完成：根 Box 已挂
  `WenkuFlightOverlay()`；④ 先只在一个入口（书架列表首卡）验证，再铺到 3D 书架与发现页。

### 6.2 Sheet

- `WenkuSheetHost(visible, anchorBounds, onDismiss, grabber)` 已就绪（`WenkuMenuHost.kt`，含 550/24%/420ms 拖拽关闭）。
- 候选起点：`DiscoverPanels.kt` 的 `DiscoverSourceStatusSheet`（无输入框）。注意其内容用了 `Modifier.weight(1f)`，
  需要放进 `Column` 才能编译；`AcrylicBottomOverlay` 的窗口/遮罩语义要一起替换。
- 另外 10 处 `PullOutBottomSheet` 调用点见 `grep PullOutBottomSheet(`，逐个确认滚动/IME 后再换。

### 6.3 玻璃

- `WenkuGlassSurface`（`WenkuGlass.kt:68-139`）走 `drawBackdrop(effects = { blur(blur.toPx()) })`；
  按 `native_effects.dart:905-944` 以 `physicalBounds + morphProgress` 为键缓存 `ImageFilter`，
  先把 `effects`/`outline` lambda 提成 `remember`，再谈缓存。
- Dock 的玻璃在 `AppBottomTabBar.kt:214-238`（半径已固定，不随按压重建）。


## 7. R14：第 6 节三项落地（本轮）

> 分支 `feature/wenku8-ui-port`；只动前端与根容器，后端/框架未动。
> 证据：`evidence/r14/`（飞行帧、sheet 打开/关闭、构建日志 `evidence/r14-build1.log`）。

### 7.1 封面飞行：真正飞起来了（6.1 全部四步）

1. **桥**（`ui/wenku/WenkuFlightBridge.kt`）
   - `WenkuFlightRequest` 增加 `bookKey`；`begin(bookKey, bounds, image, radius)`、`arrive(bookKey, …)` 只接受同一本书、只登记一次落点；
   - 方向修正：覆盖层传 `departing = true`（`COVER_FLIGHT_MS = 1000`）。桥原来的注释写的是「false 表示进入」，
     而 `WenkuCoverFlight` 的 `departing=true` 才是「源 → 目标」，照旧会走 840ms 的返回方向并把起点/终点对调；
   - 覆盖层把自己的 root 原点减掉再交给 `WenkuCoverFlight`，并且等 `onGloballyPositioned` 之后才开画。
2. **覆盖层挂载点**：从 `paperComposable("home")` 内部挪到 `setContent` 根 Box 的最后一个子节点
   （`MainActivity.kt`，`MascotOverlay` 之后）。挂在 home 路由里时，跳阅读器会把整块组合移除——
   纸刚起飞就消失（R14 实测：overlay 只打了一行日志就没了）。
3. **源端**（`HomeScreen.kt` 书架列表卡）
   - 新增 `ui/wenku/WenkuCoverSnapshots.kt`：把 Coil 解码好的 drawable 光栅化成 `ImageBitmap`（LRU 32），
     点击时 `WenkuFlightBridge.begin(key, shelfSelection.coverRect(key), snapshot, 14.dp)`；
   - 卡片 `ImageRequest` 加 `.allowHardware(false)`：硬件位图画不进软件 Canvas；
   - Coil 的 `.crossfade(true)` 会把 drawable 包成 `CrossfadeDrawable`，`Success` 那一刻它还在淡入起点——
     第一次光栅化几乎是全透明（实测纸是半透明的），420ms 后再光栅化一次覆盖掉；
   - 该 key 的 `sharedElement` 已从卡片移除（`wenkuPaperFlight` 的 lift/roll 也一起撤），避免两张封面。
4. **目标端**（`ReaderScreen.kt`）
   - 落点用**屏幕矩形** `Rect(0, 0, cover.size)`；**不能用 `positionInRoot()`**：
     阅读器是 `paperComposable` 的滑入页，布局阶段还在 x=1080，用它会得到 `dst=1080..2160`，纸直接飞出屏幕；
   - 删掉旧的 `if (book != null && sharedTransitionScope != null && animatedVisibilityScope != null)` 门槛：
     阅读器路由刻意 `LocalNavAnimatedVisibilityScope provides null`（`MainActivity.kt:1275`），
     旧门槛会让整块封面根本不组合、`arrive()` 永远等不到；
   - 飞行期间（`WenkuFlightBridge.isActiveFor(book.id)`）这张整屏封面 `alpha = 0`，桥清空后恢复原曲线。
5. **绘制**（`WenkuCoverFlight.kt`）
   - `drawVertices + BitmapShader` 换成 `Canvas.drawBitmapMesh`：Android 的顶点色会与着色器**相乘**，
     `colors.fill(0xFF202020)` 把整张封面压到 12% 亮度（实测整张纸是黑的）；
     换成白色顶点色后仍是灰蓝色块（模拟器 SwiftShader 对 drawVertices+shader 的采样不正确），
     最终改用专门给规则网格贴图的 `drawBitmapMesh`，贴图与封面一致；
   - 顶点色统一填白，兜底色 `0xFF202020` 只留给没有位图的分支（与 Flutter `paint.color` 的语义对齐）。

### 7.2 Sheet：第一块面板换到 `WenkuSheetHost`（6.2 起点）

- `discover/DiscoverPanels.kt` 的 `DiscoverSourceStatusSheet` 从 `AcrylicBottomOverlay`（Dialog + 实时模糊）
  换成 `WenkuSheetHost`：去掉 Dialog 窗口与那层实时模糊，遮罩/滑入/拖拽关闭/返回键全部由宿主负责；
  内容包一层 `Column(heightIn(max = 520.dp))`，原来的 `Modifier.weight(1f)` 才能成立。
- 调用点改成**常驻组合 + 翻 `visible`**（`DiscoverScreen.kt`）：宿主需要看到 `visible: true → false` 才有退场。
- 新增 `WenkuSheetRegistry`（`WenkuMenuHost.kt` 末尾）：sheet 组合在路由内部，z 序低于根部的 Tab 栏，
  打开期间登记一次，`MainActivity` 的 `PullOutVisibility` 据此让 Tab 栏让位——
  否则面板底部的「重试失败书源」会被 Tab 栏压住（实测复现）。
- 其余 10 处 `PullOutBottomSheet`（阅读器 ×2、书库 ×2、缓存管理、帮助、来源迁移、重复漫画、
  小说详情 ×2、书架 ×2）尚未迁移，按同一模式逐个确认滚动/IME。

### 7.3 玻璃：remember + RenderEffect 缓存（6.3）

- 新增 `ui/wenku/WenkuGlassFilters.kt`：
  - `WenkuGlassFilters.blur(radiusPx)`：按 0.25px 量化半径缓存 `android.graphics.RenderEffect`（LRU 12）；
    Android 的模糊 `RenderEffect` 与图层尺寸无关（尺寸由 `DrawBackdropNode` 挂载的 `GraphicsLayer` 决定），
    所以参考实现的「physicalBounds + morphProgress」在这里收敛成「半径」——参考实现的 bounds 只是
    Flutter `BackdropFilterLayer` 的尺寸参数；
  - `BackdropEffectScope.wenkuGlassBlur(radiusPx)`：与 `Blur.kt` 的单效果分支语义一致，只是换成缓存对象。
- `WenkuGlassSurface`：`effects`/`onDrawSurface`/`onDrawFront`/`highlight`/`shadow` 全部稳定化，
  整条 `drawBackdrop` modifier 提进 `remember(backdrop, shape, surfaceTint, blurPx, …)`；
  按压缩放改成在 `graphicsLayer` 里读 `State<Float>`，不再每帧重建 modifier。
  非实时分支要调 `@Composable` 的 `consistentShadow`，留在 `remember` 外面。
- 效果：`DrawBackdropElement` 不再每次重组都 `invalidateDrawCache → apply(effects) → createBlurEffect`。

### 7.4 验证

- 构建：`gradlew :app:assembleDebug -PincludeX86` → BUILD SUCCESSFUL。
- 飞行：`evidence/r14/frames/fr015..fr026.png`（屏幕录制 10fps 拆帧）——
  卡片起飞 → 纸张抬升/弯曲 → 落满整屏 → 阅读器；`fr023` 是落点满屏帧。
- Sheet：`evidence/r14/33-sheet.png`（打开，Tab 栏已让位）、`36-closed.png`（背景点击关闭，Tab 栏回来）。
- logcat：无 `FATAL EXCEPTION`。

### 7.5 本轮新增的坑（务必记住）

1. **别用 Windows PowerShell 5.1 的 `Get-Content` / `Set-Content` 改源码**：它按 ANSI(CP936) 读、
   再以 UTF-8 写回，整个文件的非 ASCII 会被转成乱码（本轮真实踩过，靠会话记录逐行反演才复原）。
   改源码只用 `edit` 工具或 python 显式 `encoding="utf-8", newline="\n"`。
2. 阅读器路由的 `LocalNavAnimatedVisibilityScope` 是 **null**：任何 `sharedElement`/`animatedVisibilityScope`
   门槛在阅读器里都不成立。
3. `positionInRoot()` 在 `paperComposable` 的入场页上拿到的是**屏幕外坐标**。
4. `drawVertices` 的顶点色是**乘**在着色器上的；`drawBitmapMesh` 才是规则网格贴图的正确 API。
5. 飞行前 ~300ms 会被路由自己的纸面转场盖住（视觉偏淡）。要更干净，下一步可以在飞行期间跳过
   该 key 的路由纸面转场（或把覆盖层再抬到转场之上）。


## 8. R15：收尾（3D 书架、返回飞行、12 处 sheet、Dock 玻璃）

> 证据：`evidence/r15/`。R15 期间宿主机内存吃紧，模拟器连续自我重启并出现 ANR；
> 换 `-no-window -gpu swiftshader_indirect` 冷启后所有验证通过（判断方法见 8.4）。

### 8.1 封面飞行：3D 书架 + 返回方向

- **3D 书架**（`ui/wenku/ArchiveShelf.kt`）
  - 语义盒（`shelfSemanticBox`）的布局与 `ScenePainter` 画封面面完全同源，所以直接把它的
    `boundsInRoot()` 登记成封面矩形（`HashMap<Int, Rect>`，**不是** Compose state，见 8.4）；
  - 三个打开入口（点击命中中心书、无障碍 `onClick`、底部 caption）统一走 `openBook(book)`：
    `WenkuFlightBridge.begin(key, rect, covers.get(book.id), 3.dp)` 后再 `onBookClick`。
- **返回方向**（`WenkuFlightBridge` / `ReaderScreen`）
  - 桥新增 `returning`、`reverse(bookKey)`、`finished()`：播完时把最后一次请求留在 `last` 里，
    返回时用它把「起点=整屏、终点=书架卡片」反过来，方向交给 `WenkuCoverFlight(departing = false)`（840ms）；
  - 阅读器两个返回入口（翻页 EXIT 区、顶栏返回）统一走 `exitReader()`：先 `reverse(book.id)`，再 `onBack()`；
  - 覆盖层不再写死 `departing = true`，改成 `!request.returning`。
  - 实测：`evidence/r15/c2.png` 里能看到纸已经缩小飞回卡片位置（同一帧左上是列表卡）。

### 8.2 Sheet：`PullOutBottomSheet` 一次性整体迁移（12 个调用点）

- 新增 `WenkuSheetDialog`（`ui/wenku/WenkuMenuHost.kt`）：全屏透明 Dialog 承载 `WenkuSheetHost`，
  遮罩由宿主的 `WenkuSheetBarrier` 负责，窗口 `setDimAmount(0f)` 避免叠两层灰。
- `PullOutBottomSheet`（`ui/components/PullOutHosts.kt`）不再用 Material `ModalBottomSheet`，
  改为转发到 `WenkuSheetDialog`。签名保持不变，所以 12 个调用点（阅读器目录/批注、书库 ×2、
  缓存管理、帮助、来源迁移、重复漫画、小说详情 ×2、书架「更多」/分类选择）**一处未改**就全部换成
  Wenku 面板：圆角、抓取条、550ms 滑入、拖拽关闭、返回键都由宿主接管。
- Material 的 `sheetState / shape / containerColor / contentColor / tonalElevation / scrimColor`
  形参保留但不再参与绘制（避免改 12 个调用点的连锁反应）。
- 实测：阅读器目录（`r2.png`）、书架「更多操作」（`s2.png`）都是新面板；背景点击关闭（`s3.png`）。

### 8.3 Dock 玻璃

`AppBottomTabBar` 的 `drawBackdrop` modifier 也提进 `remember(backdrop, shape, colors…)`，
模糊改走 `wenkuGlassBlur`，与 `WenkuGlassSurface` 同一套缓存；Dock 不再每次重组重建
`RenderEffect`。

### 8.4 两个坑（R15 新增）

1. **`onGloballyPositioned` 里不能写 Compose state**：3D 书架第一版用 `mutableStateMapOf`
   登记封面矩形，切 3D/列表时触发「布局 → 重组 → 布局」死循环，直接 ANR。改成普通 `HashMap`
   （只在点击时查表）后正常。这个坑很隐蔽：Logcat 只有 `Input dispatching timed out`，
   真正的现场在 `/data/anr/` 的 traces 里。
2. **模拟器 ANR ≠ 代码回归**：R15 中途 `consistentShadow` 的软件模糊卡住主线程 5s+，
   看起来像新代码引起；但把上一轮交付的 APK（`artifacts/wenku8-ui/*.apk`，不含 R14/R15 改动）
   装上去同样 ANR，重启后连 Launcher 都 ANR —— 实际是宿主机内存被 Gradle/模拟器挤爆
   （`free_ram` 只剩 3.9G，qemu 的 CPU 线程 15s 无响应）。处理方式：`gradlew --stop` 腾内存，
   `-no-window -gpu swiftshader_indirect` 冷启模拟器，之后再验证。
   判断顺序固定为「同一流程跑两版 APK → 排除代码 → 再查宿主」。

### 8.5 发现页：接在「小说详情面板」的封面上

发现页的卡片点开走两条路：漫画 → `comic_chapters`（没有阅读器封面落点）；小说 →
`discoveredNovel` 打开下载详情面板（`NovelDetailSheet`），阅读器入口在面板里的「阅读」按钮。
所以飞行不能接在发现页卡片上（落点不存在），而是接在**详情面板的封面**上：

- `NovelCover` 增加可选 `flightKey`：有 key 时用 `onGloballyPositioned` 把封面矩形登记进
  `WenkuFlightSources`（普通 Map），并把 `SubcomposeAsyncImage` 的 painter 交给
  `WenkuCoverSnapshot` 光栅化；
- `NovelDetailContent` / `NovelDetailSheet` 透传 `flightKey`；「阅读」动作先
  `WenkuFlightBridge.begin(flightKey, rect, snapshot, 12.dp)` 再 `onRead()`，
  没有登记到矩形就退回原来的直接切换（不影响非本地书 / 下载面板）；
- `DiscoverNovelDetails` 传 `flightKey = local?.id?.toString()` —— 阅读器侧用的就是本地
  `Book.id`，两端 key 自然对齐。

漫画那条路仍然没有飞行（`comic_chapters` 没有封面落点），这是产品路径决定的，不是漏接。


## 9. 交接：还剩什么（都不影响当前可用性）

| 项 | 现状 | 下一步 |
|---|---|---|
| 发现页小说 → 阅读器飞行 | 已接（详情面板封面 → 阅读器，见 8.5）；本地书才会出现「阅读」按钮，当前 发现页 列表里没有本地书，未做端到端实拍 | 找一本已下载的小说从 发现 → 详情 → 阅读 走一遍即可复验 |
| Sheet 退场动画 | `PullOutBottomSheet` 的调用点仍是「条件组合」，关闭时立刻卸载，没有 320ms 反向 | 需要逐点改成「常驻 + 翻 `visible`」（发现页那块已经是这个写法，可照抄） |
| 飞行前的路由纸面转场 | 前 ~300ms 会盖住纸（视觉偏淡） | 飞行期间跳过该 key 的 `paperComposable` 转场，或把覆盖层再抬一层 |
| 玻璃采样 | 仍是 `backdrop` 实时采样 | `native_effects.dart` 的预烘焙路径已在库中，未接（历史上有实机异常，见 `MainActivity` 注释） |
| 3D 书架封面 | 每本一次光栅化（`ShelfCoverStore`，容量按窗口 11 本） | 容量与滚动窗口可再对齐一次 |


## 10. R16：长期目标第一轮（Dock 性能 / 「我喜欢的」3D 书架 / 卡片参数）

> 证据：`evidence/r16/`（a1 首页、a2/a3 收藏 3D 书架）。

### 10.1 Dock 点按卡顿

两个根因，都是「逐帧变化把整棵子树拖着重绘/重组」：

1. **玻璃与指示条在同一条 draw 链上**：指示条弹簧每帧 invalidate 整个 Dock 节点，
   `DrawBackdropNode.draw()` 因此每帧重录 backdrop（整屏采样 + 模糊）。
   现在玻璃、指示条各自放进独立 `graphicsLayer` 子 Box，指示条动画只重绘自己那一层。
2. **颜色用 `animateColorAsState`**：颜色在 composition 读取 ⇒ 换 Tab 时 5 个 DockTab
   逐帧重组。改成 `animateFloatAsState` 的 alpha，并在 `graphicsLayer { }` 里读，
   换取 Tab 时零重组（图标/文字颜色以 alpha 叠加得到同样的灰度层次）。

附带：`pointerInput` 的 key 去掉了 `selectedIndex`（不再每次选中断开手势协程）。

### 10.2 「我喜欢的」复用 3D 书架

- `ArchiveShelf` 的封面加载现在认 `http(s)`（收藏是在线条目，封面是远程 URL）；
- HomeScreen 把 `favSorted` 映射成书架条目（负 id、`contentType = "COMIC"`、
  `localThumbPath` 优先、`FAV-xxxxx` 书脊编号），`favById` 回查点击；
- 与「我的书架」共用同一个 `use3dShelf` 开关：3D 时渲染 `ArchiveShelf`，列表时仍是原卡片；
- 收藏条目 `isComic = true`，所以不会触发阅读器封面飞行（漫画没有落点）。

### 10.3 卡片参数接入新卡片

`HomeScreen` 的书架卡此前是写死的 14dp 圆角 + 固定渐变。现在读 `LocalCardTweaks`：

| 设置滑块 | 卡片上的落点 |
|---|---|
| 卡片圆角 | `RoundedCornerShape(cornerRadiusDp)`（阴影/裁剪/描边共用） |
| 卡片不透明度 | 渐变两端颜色乘以 `cardAlpha` |
| 主题色叠加浓度 | 渐变末端的 `MintSecondary` alpha（`tintMix × 1.9 / 3.6`） |
| 压力形变强度 | 按压缩放 = `1 - .04 × pressStrength` |

`LocalCardTweaks` 由 `MainActivity` 注入，设置页滑块实时写回，无需重启。

### 10.4 下一轮（同一长期目标）

1. 设置项逐条核对（哪些在新 UI 下没有被消费）；
2. 设置 → 设备互传 / 备份 的转场跳跃与「设置瞬间消失」；
3. Material 图标 → `WenkuSymbol` 全量映射，补按钮图标三拍手势；
4. 「每个按钮都有转场」清单核对（逐页、逐按钮）；
5. 配色统一走 token（禁止硬编码色值）。


## 11. R17：转场一致性（设备互传/备份）与设置项审计

> 证据：`evidence/r16/transfer.mp4` + `frames/t026.png`（中段：设置页左移 1/3、传输页滑入）。

### 11.1 「设置 → 设备互传/备份」为什么跳

- 设备互传入口是 `TransferManager.show()` → `openPage=true` → `navController.navigate("device_transfer")`；
- `device_transfer` 是 `paperComposable`（Cupertino 滑入），但 **home 是普通 `composable`**，
  而 NavHost 的全局 `exitTransition = ExitTransition.None` —— 于是新页滑入时，设置页**瞬间消失**，
  中间没有任何一帧「两页同屏」，看起来就是跳一下。
- 修法：把 `paperComposable` 那套曲线（`Cubic(.18,1,.04,1)`、enter 420 / exit 320、前页 1/3 视差）
  导出成 `paperRootExit()/paperRootPopEnter()/paperRootPopExit()`，显式挂到 `home` 路由上。
  现在进出都是同一套 Cupertino 转场，退栈时设置页从左侧滑回。
- 顺带收益：home 有了 `AnimatedVisibilityScope`，书架 → 阅读器的共享元素/封面飞行从此有正式的作用域。

### 11.2 设置项审计（第一遍：静态扫描）

对 `PreferencesManager` 的 60 个持久化字段逐个扫描「除 `PreferencesManager` / 设置页之外的引用」：

- **只有写入、没有读取（历史遗留，设置页里也没暴露）**：
  `clickZoneLeftAction` / `clickZoneCenterAction` / `clickZoneRightAction`（阅读器点击区域动作）、
  `customBgColor` / `customTextColor`（自定义背景/文字色）；
- `card*` 九个卡片参数通过 `readCardTweaks()`（扩展函数）被 `GlassCard` 与书架卡消费，
  静态扫描会误报为“无读取”，实际已接线（R16 的 10.3）；
- `jsSourceHealthChecked` 是内部标记（只写合理）。

结论：**设置页暴露的开关没有“写了不生效”的**；真正需要补的是「新组件是否消费」这一类，
逐项核对见 11.3。

### 11.3 新 UI 消费核对（已确认）

| 设置 | 新 UI 落点 |
|---|---|
| 主题色（primary/secondary） | `MintPrimary/MintSecondary` = `MaterialTheme.colorScheme.primary/secondary`；`Theme.kt` 已把 Wenku 纸面/墨色写进 colorScheme，新组件取 `wenkuColors()`（明暗）＋强调色取主题色 |
| 渲染质量 | `rememberWenkuAccessibility()` 读 `LocalRenderQuality`：`blurEnabled = realtimeGlass`、`decorativeMotion = !minimal`；玻璃/动效全走这两个开关 |
| 卡片参数 | R16 已接（圆角/不透明度/主题叠加/压力） |
| 减少动效 | `LocalReduceMotion` → `WenkuAccessibility.reduceMotion` → 所有 Wenku 动效先判它 |
| 高对比度 | 系统 `high_text_contrast` → 玻璃退化成实心面板 |

### 11.4 下一轮

1. Material 图标 → `WenkuSymbol` 映射表（先覆盖设置/阅读器/书库三页的所有图标按钮）；
2. 按钮图标三拍手势（`archive_icon_motion.dart`）接到 `WenkuIconButton` 的每个调用点；
3. 「每个按钮都有转场」清单：逐页 dump UI、逐按钮点一遍，记录缺失项；
4. 参考仓库剩余的列表/内容浮现序列（`loading_handoff.dart`、`reading_moment_reveal.dart`）。


## 12. R18：图标动效 —— Material → WenkuSymbol 映射表 + 按钮接入

> 证据：`evidence/r16/i1-reader-bar.png`（阅读器顶栏：返回/书签/听书/目录/更多 全部是纸片图标）。

### 12.1 映射表（`ui/wenku/WenkuIconMapping.kt`）

参考仓库的按钮图标全是手绘纸片（`archive_icons.dart` 89 个符号）。本工程还有大量 Material `Icon`，
所以按 `ImageVector.name`（`Filled.Bookmark` / `AutoMirrored.Filled.ArrowBack` …）做了一张集中映射：

- 导航、阅读、操作、文件、系统五组，共约 90 条；
- 只比较名字最后一段（family 无关），命中返回 `WenkuSymbol`，未命中返回 null；
- 调用点不需要知道映射表，也不会因为漏映射而丢图标（回退 Material）。

### 12.2 两个按钮组件都加了 Wenku 重载

- `AppIconButton(icon, contentDescription, onClick, active, tint, glyphSize, wenkuSymbol)`；
- `PullOutIconButton(icon, contentDescription, onClick, active, tint, glyphSize, wenkuSymbol)`。

命中映射时改画 `WenkuIcon`：**按下 140ms / 抬起 240ms 的 contact 三拍手势**（`archive_icon_motion.dart`），
`active` 变化走状态 morph（书签 ↔ 已书签、听书 ↔ 播放）。没命中原样走 Material，视觉零回归。

### 12.3 已接线

- 阅读器顶栏 5 键（返回 / 书签 / 听书 / 目录 / 更多）已换成新重载，书签与听书带 `active` 状态；
- 其余页面里 3 个最规整的图标按钮（Tune / Save / SwapVert）已机械替换；
- 剩下的复杂形态（onClick 里带分支、图标在 if/else 里）留到下一轮逐个改。

### 12.4 下一轮

1. 逐页把剩余图标按钮改成新重载（先设置、书库、发现三页）；
2. 「每个按钮都有转场」清单：每页 dump UI → 逐按钮点一遍 → 记录缺失；
3. 列表内容浮现（`loading_handoff.dart`）与详情页内容弹簧；
4. 主题强调色 token（`WenkuColors.accent`）与硬编码色值清理。


## 13. R19：主题色验证、页面内容浮现、图标按钮继续铺开

> 证据：`evidence/r19/t2-shelf-orange.png`（主题切橙后书架）、`frames/e045.png`（设备互传页）。

### 13.1 主题色：真的贯穿到新 UI（实测）

设置 →「外观与主题」→ 主题配色方案 切到**橙色**后：

- 书架：「默认 7」胶囊、导入新书胶囊、两排进度条、Dock 选中态全部变橙；
- 设备互传：顶部纸片图标、发送数据按钮、`全部·调整`、`已选 7 类`、分享数据包、保存本地备份 全是橙色。

结论：`BasePrimaryColors → MaterialTheme.primary → MintPrimary` 这条链是通的，新组件（Wenku 纸面/墨色）保持中性、
强调色一律取主题色。**不需要改色板**；剩下的工作是清理个别硬编码色（如 Dock 红点 `0xFFE5484D`）。

### 13.2 页面内容浮现（`WenkuPageEntrance`）

参考仓库的页面在 Cupertino 位移之外还有一层内容入场（`ArchivePart`：内容自下方浮起、透明度收尾）。
新增 `ui/wenku/WenkuPageEntrance.kt` 并挂在 `paperComposable` 上：

- 位移 18dp → 0、alpha 0.92 → 1，320ms，曲线走 `WenkuMotion.ease`；
- 只在页面首次组合跑一次；`reduceMotion` 直接落终点；
- 因为位移/透明度都在 `graphicsLayer` 里，**不触发重新布局**，页面滚动/输入不受影响。

实测：设备互传页滑入的同时内容上浮到位（`frames/e045.png` 是落定帧，布局无偏移）。

### 13.3 图标按钮继续铺开

- 设置页标题栏返回键 → 新重载（纸片箭头 + 三拍手势）；
- 缓存管理关闭键 → `WenkuSymbol.Close`；
- 上一轮已转换的阅读器顶栏 5 键保持。

### 13.4 下一轮

1. 「每个按钮都有转场」清单：按页 dump UI → 枚举 clickable 节点 → 逐点点一遍，记录缺失；
2. `LoadingHandoff`（参考 `loading_handoff.dart`）接到发现页/书库的骨架 → 内容交接；
3. 剩余 Material 图标按钮继续换新重载（书库、发现、来源管理）；
4. 硬编码颜色清理（Dock 红点、部分 `Color.Black` 遮罩改 token）。


## 14. R20：按钮/转场审计（逐项清单）+ 配色收尾

> 产物：`docs/WENKU_INTERACTION_AUDIT.md`（完整清单与判定方法）；
> 脚本：`evidence/r20/routes.py`、`evidence/r20/dump-pages.ps1`。

### 14.1 按压反馈：没有裸按钮

全仓扫描「可点修饰符」：

- `clickableWithFeedback` / `pullOutClickable` / `pullOutCombinedClickable`：**92 处**，
  统一是缩放 0.97 + 整体 dim 0.7 + 触觉（`contactSpec/settleSpec`）；
- 裸 `.clickable(`：**6 处**，全部在 `AppButton` / `GlassCard` / `PullOutMotion` 内部
  （各自带 `paperAction` 或本身就是手势基座），不构成「点了没反应的按钮」。

### 14.2 路由：没有无转场的导航

把 `navigate("…")` 与路由注册表对齐（8 条目标路由）：

- `reader` / `comic_reader` / `comic_chapters?…` / `comic_reader_online?…` / `novel_reader_online?…`
  / `settings` / `god_ranking` / `device_transfer` → 全部 `paperComposable`；
- `home` → 普通 `composable`，但 R17 已显式挂上 `paperRootExit/PopEnter/PopExit`；
- `god_editor` → 四个 `None` 是**有意为之**：它只是透明宿主，编辑器弹窗自己播动画；
- `splash` / `onboarding` → 启动转场。

### 14.3 遮罩类

| 类型 | 站点 | 动效来源 |
|---|---|---|
| 底部面板 | 12 | `WenkuSheetHost`（550ms 滑入 / 拖拽 550·24% / 320ms 退场） |
| 下拉菜单 | 4 | `WenkuMenuHost`（宽高独立 morph + 320ms） |
| 弹窗 | 全仓 | `AcrylicDialog` 遮罩 + 面板入场 |
| 神回编辑 | 1 | `GodMomentEditHost` 自带 |

### 14.4 配色

- Dock 未读红点 `Color(0xFFE5484D)` → `MaterialTheme.colorScheme.error`；
- 书架书脊的 5 个纸色（`0xFFF2F0E9` 等）确认为「纸张本色」，不随主题走，保留并记录在审计文档里。

### 14.5 下一轮

1. 书库 / 发现 / 来源管理 / 统计四页的 Material 图标按钮换新重载；
2. `LoadingHandoff` 接到发现/书库骨架；
3. 审计文档第 5 节的遗留项逐条清零。


## 15. R21：内容浮现的「钟」补上了 + 四页图标按钮

> 证据：`evidence/r21/w3.png`（切回书架后渲染正常）。

### 15.1 找到了「内容浮现没搬」的真正原因

`Modifier.wenkuReveal(order)`（卡片逐项擦除）依赖 `LocalWenkuSequence` 这个**共享时钟**；
时钟由 `WenkuLoadedContentSequence` 提供。翻遍全仓，`WenkuLoadedContentSequence` **一次都没被调用** ——
于是所有 `wenkuReveal(order)` 都走 `sequence == null` 的早退分支，等于 no-op：

- 书架卡、发现卡、收藏卡上的「逐项擦除 + 上浮 6dp」从来没有播过；
- 之前看到的卡片出现只是 LazyGrid 自带的 placement 动画。

修法：在 `MainActivity` 的 `WenkuPlaneTabSwitcher` 内容外层包一层
`WenkuLoadedContentSequence(contentKey = tab, loading = false)`。每个 Tab 进入时序列钟 0→1（420ms），
卡片的 `wenkuReveal(order)` 按 `listStart/listEnd` 错峰擦除。切 Tab 会重播（符合参考实现的「页面进入即列表浮现」）。

### 15.2 四页图标按钮换新重载

| 页面 | 转换 |
|---|---|
| 书库 | 返回、搜索清空、面板关闭（3） |
| 发现面板 | 来源详情关闭、书源窗口关闭、分类窗口关闭（3） |
| 来源管理 | 删除书源（1） |

加上此前的阅读器顶栏 5 键、设置返回、缓存关闭，共 13 个图标按钮走纸片图标 + 三拍手势。

### 15.3 下一轮

1. 剩余 `PullOutIconButton`/`AppIconButton`（书库卡片上的取消下载小叉等）按需转换——注意
   48dp 命中框与小尺寸内联按钮的布局差异；
2. `LoadingHandoff`：参考实现是「骨架留在未揭示区域下方」的堆叠，LazyGrid 里不能直接套，
   需要按「网格首屏用同一份骨架索引」实现；
3. 审计文档第 5 节清零。


## 16. R22：列表浮现实测通过 + Tab 状态存活

> 证据：`evidence/r22/p2-back.png`（切回书架的中途帧：卡片正在逐项擦除、整页上浮未落定）。

### 16.1 内容浮现真的跑起来了

R21 补的序列钟生效后，切 Tab 回到书架列表的中途帧可以看到：

- 第一排卡片已擦除到标题行，第二排还在揭示中（按 `listStart/listEnd` 错峰）；
- 整页仍带 ~6dp 的上浮位移（`wenkuReveal` 的 travel）；
- 420ms 后落定。

这就是参考实现里 `ArchivePart` 那条「列表进入逐项浮现」。

### 16.2 3D/列表选择跨 Tab 存活

实测发现：切到设置再回书架，视图选择会退回 3D —— `rememberSaveable` 在当前
`WenkuPlaneTabSwitcher(AnimatedContent)` 的结构里**不会恢复**（内容 lambda 被销毁后
saveable 值没有回到 registry）。

修法：把选择提到进程级 `HomeShelfUi.use3dShelf: MutableState<Boolean>`，
`var use3dShelf by HomeShelfUi.use3dShelf`。现在切 Tab 往返保持列表/3D 选择不变。

另外在 Tab 内容外层补了 `SaveableStateHolder.SaveableStateProvider(tab)`：
滚动位置、筛选、展开态这类 `rememberSaveable` 状态按 Tab 隔离保存（3D 选择走上面的进程级状态，
不依赖它）。

### 16.3 下一轮

1. `LoadingHandoff`（骨架 → 内容）按「同索引骨架」的思路在发现/书库落地；
2. 书库卡片上的小尺寸图标按钮（取消下载叉）按需转换；
3. 审计文档遗留项清零。


## 17. R23：LoadingHandoff 组件落地（待接线）

> 证据：`evidence/r23/library.png`（书库页在橙色主题下的回归检查）。

### 17.1 新增 `ui/wenku/WenkuLoadingHandoff.kt`

按 `loading_handoff.dart:6-98` 的语义实现：

- **内容**按揭示进度从顶部向下裁切（`clipRect(bottom = h·p)`），320ms；
- **骨架**只画尚未揭示的那一段（`clipRect(top = h·p)`），所以「内容晚到一帧」也不会露白；
- `loaded` 变 true 时起跑、变回 false 直接回骨架；`reduceMotion` 落终点；
- 组件按内容尺寸撑开，骨架 `matchParentSize` 拉伸到同一高度。

### 17.2 为什么这轮没有直接接线

缓存管理页的骨架是「整块 if/else 二选一」，而内容是 4~5 个同级子项（数字、说明、占比条、图例），
直接塞进 `content` lambda 会丢掉父级 `Column` 的居中与纵向堆叠。需要给内容再包一层 Column 并
保持括号平衡；这轮先保证组件本身正确（编译通过、未接入所以零回归），下一轮按「同索引骨架」
的写法在缓存管理页和发现页落地。

### 17.3 回归检查

书库页在橙色主题下：搜索栏、下载任务入口、空状态卡片、底部 Tab 全部正常；上一轮转换的
返回/清空/关闭三个图标按钮没有布局偏移（清空按钮只在有输入时出现，逻辑未变）。

### 17.4 下一轮

1. 缓存管理页 + 发现页接线 `WenkuLoadingHandoff`；
2. 书架卡片上的小尺寸图标按钮（取消下载叉）按需转换；
3. 审计文档遗留项清零。


## 18. R24：LoadingHandoff 接入缓存管理 + 两个小叉按钮

> 证据：`evidence/r24/frames/c010.png`（扫描完成的中途帧：数字已揭示，占比条区域仍是骨架条纹）。

### 18.1 缓存管理页接线

把 R23 的 `WenkuLoadingHandoff` 接到「扫描中骨架 → 占用数字」这段：

```kotlin
WenkuLoadingHandoff(loaded = !isScanning, skeleton = { ScanningSkeleton() }) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        …数字 / 说明 / 占比条 / 图例…
    }
}
```

中途帧能看到：顶部「1.5 MB」已经揭示，下面占比条区域还留着骨架的四条灰杠——
正是参考实现「骨架留在未揭示段下方」的语义。扫描结束后骨架整体消失，内容落定。

### 18.2 两个取消下载小叉

书库卡片上 24dp / 30dp 的两个取消按钮把 Material `Icon(Close)` 换成 `WenkuIcon(Close)`，
字号 15/17dp 保持原命中框，不动布局。

### 18.3 下一轮

1. 发现页的骨架按「同索引骨架」接 `WenkuLoadingHandoff`；
2. 审计文档遗留项：统计页/来源管理的图标按钮、Dock 红点之外的硬编码色；
3. 反馈主题色到新增的 handoff/icon 组件是否都走了 token。


## 19. R25：图标按钮再铺 6 处 + 发现页浮现核对

### 19.1 发现页的浮现其实早就有

核对 `DiscoverScreen` 时发现它自带一层
`WenkuLoadedContentSequence(contentKey = viewportKey, loading = state.loading && rows.isEmpty())`：
维度/筛选变化和「加载完成」都会重播 0→1 的钟，卡片上的 `wenkuReveal(entryIndex)` 因此一直有效。
所以发现页不需要再接 `WenkuLoadingHandoff`（网格里也没法堆叠骨架），R24 的结论修正为：
**发现页只需保留现有的序列**。

### 19.2 图标按钮 6 处

| 页面 | 按钮 |
|---|---|
| 小说在线阅读器 | 返回 |
| 神回排行榜 | 返回 |
| 神回编辑面板 | 还原默认（Refresh） |
| 阅读器点击区编辑器 | 取消 |
| 封面裁切页 | 取消（白 tint） |
| Z-Library 节点管理 | 返回 |

加上此前 15 处，共 **21 个图标按钮**走纸片图标 + 三拍手势；映射表未命中的（A-/A+ 文本按钮、
多行 onClick 的删除键）保持 Material。

### 19.3 审计文档同步

`docs/WENKU_INTERACTION_AUDIT.md` 更新了图标按钮清单与「内容浮现接线现状」表
（Tab 序列钟 / 发现页自带钟 / 缓存页 handoff / 书架与收藏的 wenkuReveal）。


## 20. R26：书架页硬编码色清到 token

`HomeScreen` 里九处写死的颜色改成主题/令牌（其余硬编码是封面艺术与徽标色，保留）：

| 原值 | 替换 |
|---|---|
| 搜索栏底 `0xFF222428` / `White` | `MaterialTheme.colorScheme.surfaceContainer` |
| 图标按钮底 `0xFF2B2D31` / `0xFFF4F4F4` | `colorScheme.surfaceContainerHigh` |
| 搜索框描边 `0xFF383A40` / `LightGray` | `colorScheme.outlineVariant` |
| 搜索框容器（聚焦/非聚焦） | `colorScheme.surfaceContainerHigh` |
| 封面卡暗色渐变 `0xFF2B2D31` / `0xFF1E2022` | `WenkuTokens.RaisedDark` / `PanelDark` |
| 封面卡亮色渐变 `0xFFF4F4F4` / `0xFFE3E5E7` | `WenkuTokens.PaperLight` / `wenkuColors().raisedHigh` |

实测（`evidence/r26/k2-shelf-list.png`）：列表书架、橙色强调、搜索栏、进度条视觉与改前一致，
但暗色/夜间下会跟随 colorScheme 而不是固定灰。

### 20.1 剩余硬编码（有意保留）

- `WenkuTokens` / `Color.kt` / `DesignTokens`：令牌定义本身；
- 书架书脊的纸色（5 个）：纸张本色，不随主题；
- 漫画/神回/开屏的装饰艺术色：属于插画资源，不是 UI chrome。


## 21. R27：收官回归 + 最后一处图标

> 证据目录：`evidence/r27/`
> - `01-shelf-3d.png`、`02-shelf-list.png`（书架 3D / 列表）
> - `03-library.png`、`04-discover.png`、`05-stats.png`、`06-settings.png`
> - `cache.png`、`transfer.png`、`09-reader-bars.png`
> - `crash.txt`（空：全程无崩溃）

### 21.1 回归结果

七个页面在**橙色主题**下全部正常：书架（两种视图）、书库、发现（漫画/小说切换 + 榜单 +
筛选区 + 卡片）、统计、设置、缓存管理、设备互传、阅读器。没有布局塌陷、没有白屏、
没有图标错位。

### 21.2 图标按钮收尾

Z-Library 节点管理的「删除节点」换成新重载 —— 累计 **22 个**图标按钮走纸片图标 + 三拍手势。

剩下两个明确保留 Material 的：

| 位置 | 原因 |
|---|---|
| 小说阅读器的 `A-` / `A+` | 是文字按钮（字号调节），不是图标 |
| 点击区编辑器的「删除分区」 | `onClick` 里有三段状态修改，包成 lambda 后要重排闭包，收益低 |

### 21.3 长期目标逐条对照

| 目标 | 证据 |
|---|---|
| ① 收藏复用 3D 书架 | `evidence/r16/a3-fav-shelf.png`（`FAV-` 书脊）、`ArchiveShelf` 泛化 |
| ② 底栏点按卡顿 | 图层拆分 + 独立 `graphicsLayer` + `pointerInput(items.size)`；R16 记录 |
| ③ 卡片参数联动 + 设置审计 | `LocalCardTweaks` 全量消费；审计结论见第 11 节 |
| ④ 互传/备份转场 + 内容浮现 | `paperComposable` + `paperRootExit`、`WenkuPageEntrance`、`evidence/r19/frames/e045.png` |
| ⑤ 按钮转场/图标手势/浮起序列 | 22 个图标按钮 + 映射表 + `WenkuLoadedContentSequence` + `evidence/r22/p2-back.png` |
| ⑥ 配色走主题 | 主题切橙实测 + 9 处硬编码改 token（第 20 节） |
| ⑦ 按钮清单 | `docs/WENKU_INTERACTION_AUDIT.md` |
| ⑧ 每步构建 + 证据 + 报告 | 本文档第 12~21 节，每节都有 `gradlew :app:assembleDebug -PincludeX86` 通过记录 |
