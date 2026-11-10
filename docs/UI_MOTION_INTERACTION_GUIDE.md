# 界面动效与交互规范（开发者版）

适用对象：在 `app/src/main/java/com/example/ui/` 下新增或修改页面、弹层、菜单、按钮的开发者。
配套证据：[交互与转场审计](WENKU_INTERACTION_AUDIT.md)（逐页验收状态）、[UI 打磨清单](ui-polish-checklist.md)、[跨设备 UI 审计](cross-device-ui-audit.md)、[自适应屏幕审计](adaptive-screen-audit.md)。

本文只写**已经存在于代码里的组件**和**可以照着接线的规则**。审计文档里标注「待验」「待设备核对」的条目，本文不会替它下结论；新增功能也不要靠推断把未验证行为写成「已支持」。

---

## 0. 证据分级：写结论前先看这一节

| 级别 | 含义 | 可以写进 PR / 发布说明的措辞 |
|---|---|---|
| A 已跑过自动测试 | `app/src/test/` 下的 JVM / Robolectric 用例通过 | 「回归覆盖……」 |
| B 已在模拟器或设备上手动走通 | 有截图、fixture 记录或审计表条目 | 「在 X 设备走通……」 |
| C 只做了静态接线 | 代码接上了，没跑 | 「已接线，待验」 |
| D 推测 | 只有设计意图 | 不写结论，只写 TODO |

规则：

- 只做过 C 的改动，PR 描述里必须写「已接线，待验」，不许写「已修复动效」。
- 不得把参考实现（Wenku8 Flutter 等）的行为当成当前 App 的行为。参考只用于设计意图，落地必须能在本仓库找到对应 Kotlin 调用点。
- 性能数字必须带前提：同一设备、同一数据、预热次数、样本轮次。审计文档中多次出现「P90 下降但慢帧比例波动、不能作为优化证据」的记录，照此格式写。

---

## 1. 五条不可协商的约束

1. **参数只有一个来源。** 圆角、透明度、模糊、色调、按压幅度从主题 / 卡片参数读取，不在调用点写 `Color(0xFF…)`、`RoundedCornerShape(18.dp)` 之类的魔法值。画质档位（极简 / 流畅 / 均衡 / 高 / 极致）必须能只改参数就整体降级。
2. **一个窗口层级只有一个背景采样所有者。** 面板不新建整屏采样，复用父层；否则会出现审计里记录过的「阅读设置退出时采样提前撤销」「多层同时动画」。
3. **退场结束才执行后续动作，且只执行一次。** 关闭、确认、跳转都走这条；不允许「动画和业务并行起跑」。
4. **减少动效是必测分支，不是可选优化。** 语义标签、可点区域、业务动作在 `reduceMotion` 下必须完全等价，只是没有位移和回弹。
5. **旋转 / 折叠 / 分屏只做布局切换，不做尺寸动画。** 宽度变化时重新决策布局并保留状态；不要对 `maxWidth` 做 `animateDpAsState`。

---

## 2. 主题、壁纸与卡片参数

### 2.1 颜色与可访问性

- 颜色令牌：`ui/wenku/WenkuTokens.kt` 的 `WenkuColors`；可访问性尺寸 / 对比度参数：同文件 `WenkuAccessibility` + `rememberWenkuAccessibility()`。
- 新增界面一律从 `WenkuColors` 取色。确实需要「纸张本色」这类固定色（书架书脊）时，在代码注释里写清「非主题色，理由」，并在审计文档登记，避免下次被当成遗漏清理掉。
- 大字：所有文字块用 `WenkuAccessibility` 的字号 / 行高约束；按钮高度随文字增长而不是裁切（审计里 EPUB 页脚文字被裁、按钮需随字体增高都属于此类）。

### 2.2 壁纸与玻璃背景

`ui/components/LiquidGlass.kt` 提供三种采样入口：

| 入口 | 用在哪 |
|---|---|
| `rememberScreenGlassBackdrop()` | 整屏 / Tab 内容层，一个界面一个 |
| `rememberThemedGlassBackdrop()` | 需要跟随主题色与壁纸混合的页面级玻璃 |
| `rememberGlassPanelBackdrop()` | 面板、菜单、阅读器里浮动的那一层 |

接线规则：

- 面板打开时**不要**再调用一次 `rememberScreenGlassBackdrop()`。从宿主把 panel backdrop 传下去。
- 面板退场期间背景必须继续存在到最后一帧（审计：面板保留到退场完毕），因此 backdrop 的释放挂在退场完成之后，不要挂在 `visible = false` 的瞬间。
- 极简画质下玻璃退化为纯色 + 圆角 + 透明度，参数仍从卡片参数读，不要走另一套硬编码。
- 缓存玻璃轮廓（审计记录过滚动帧里反复分配路径的问题）。列表项不要每帧重建 shape 对象。

### 2.3 卡片参数

- 数据类：`ui/components/CardTweaks.kt::CardTweaks`；渲染：`ui/components/GlassCard.kt::GlassCard`、`ui/glasskit/GlassKit.kt::GlassKitCard`、`ui/glasskit/GlassKit.kt::GlassKitHost`。
- 设置页入口：`ui/CardAppearanceScreen.kt::CardAppearanceScreen`，滑条用同文件 `CardTweakSlider`。
- 书源管理、搜索历史等 GlassKit 页面必须读同一份卡片状态（审计：GlassKit 连续圆角、主题色、卡片透明度和模糊共用状态），否则「卡片外观」只改了首页。
- 新卡片一律复用 `GlassCard` / `GlassKitCard`，不要自己拼 `Surface + blur`。

---

## 3. 层级与宿主：先选对容器，再谈动画

### 3.1 决策表

| 需求 | 用 | 不要用 |
|---|---|---|
| 进入一个**有自己返回栈**的页面（设置、配色、卡片外观、书源管理、互传、目录、封面预览） | 页面级宿主 + `WenkuPageEntrance` | 全屏 `Dialog` 假装页面 |
| 在当前页上方**临时**出现、可返回键 / 遮罩 / 拖拽关闭的玻璃面板 | `WenkuSheetHost` / `WenkuSheetDialog`（`ui/wenku/WenkuMenuHost.kt`）、`WenkuSheetSurface` | 自己写 `Popup` + 手搓遮罩 |
| 从**被点的卡片或按钮**长出来的菜单 | `WenkuMenuHost` + `rememberWenkuAnchor()`（`ui/wenku/WenkuAnchors.kt`）、`WenkuMenuTrigger`、`WenkuMenuStretch` | 居中弹窗，或用固定 `offset` 猜位置 |
| 少量动作的选择（继续 / 删除 / 取消） | `WenkuActionSheet` | 五个以上动作硬塞进动作单 |
| 阅读器侧栏 / 底部面板 | `ComicSheetContainer`（`ui/comic/ComicReaderChrome.kt`）按宽度切底部或右侧；小说查找复用同一玻璃底部面板 | 两套独立面板实现 |
| 需要系统级行为（权限、文件选择、安装） | 系统 intent / `ActivityResult`，玻璃面板只做前置说明 | 用动画遮住系统界面 |

### 3.2 层级纪律

- 弹层永远叠在**当前可见页面**之上，不叠在已退场的页面上。页面正在退出时点开新面板 = 状态 bug，先在入口处判断「过渡中」并忽略重复触发。
- 嵌套面板最多两层（面板 → 确认面板）。第三层改为「先关掉上一层再开」。
- 关闭顺序固定：内层先退，外层后退；Dock / 底栏在最后一层退场结束后再出现（审计：底部栏退场后 Dock 再出现）。
- 系统返回键：先收键盘，再关最内层面板，再退页面，最后才回桌面。任何一层在处理返回时不得同时触发下一层。

### 3.3 锚定菜单接线

```kotlin
// 正确：root 坐标 + 一个锚点状态（rememberWenkuAnchor(): Pair<Rect?, (Rect) -> Unit>）
val (anchor, setAnchor) = rememberWenkuAnchor()
Box(
    Modifier.wenkuAnchor(setAnchor)   // 内部就是 boundsInRoot()，别换成 boundsInWindow()
) {
    Card(onLongClick = { menuVisible = true })   // 不在这里手算 offset
}
WenkuMenuHost(
    visible = menuVisible,
    anchorBounds = anchor,                 // 参数名是 anchorBounds，不是 anchor
    onDismiss = { menuVisible = false },   // 用户路径：返回 / 遮罩 / 拖拽
    onHidden = { /* 退场结束、内容已卸下：后续动作挂这里 */ },
) { … }
```

- `WenkuMenuHost` 真实参数：`visible`、`anchorBounds`、`sourceRadius`、`onDismiss`、`modifier`、`dismissRequested`、`onHidden`、`borderlessSurface`、`content`；没有 `onDismissed`。`onDismiss` 只在用户路径触发，`onHidden` 在退场结束、内容卸下时**总会**触发一次——「关闭后执行」挂 `onHidden`。
- 菜单内容在退场完成前保留（审计：菜单在反向结束前保留锚点与内容）。宿主用 `mounted` 自己保持组合，不要 `if (!visible) return`。
- 同一时间只允许一个锚定菜单存在；打开第二张卡前先关闭第一个。

### 3.4 按钮放在哪

| 操作影响范围 | 位置 | 现有参考 |
|---|---|---|
| 在主要板块之间切换 | 手机底栏、宽窗侧栏。顺序、图标和选中态一致 | 书库、发现、书架、统计、设置 |
| 影响整个当前页面 | 页头尾侧。搜索、筛选和排序按使用顺序接近结果区 | 书库搜索、发现筛选 |
| 只影响一个条目 | 条目上的尾侧按钮或该条目的锚定菜单 | 漫画卡片的收藏、长按菜单 |
| 提交、安装、导入等流程主操作 | 浮层底部独立动作区，内容超出时只滚动内容区 | 应用更新、导入面板 |

同一个范围只给一个主入口。具有破坏性的次要操作放入确认流程，关闭和取消保持可达。宽度变化时允许同一个控件移位，名称、功能和选择状态跟随控件一起保留。

---

## 4. 进出交接与「关闭后执行」

### 4.1 页面进场

- 页面级进度：`ui/wenku/WenkuPageEntrance.kt::WenkuPageEntrance`。
- 内容分段落位：`ui/wenku/WenkuMotion.kt::WenkuLoadedContentSequence` + `WenkuContentClock`，区块用 `ui/wenku/WenkuMenuHost.kt::WenkuStaggerPart`。
- 骨架 → 内容：`WenkuLoadingHandoff`、`WenkuSkeletonBlock`（同文件）。
- 规则：
  - 一个页面只有**一个**时钟源；标题、卡片、分节都挂在它上面，用不同区间（例如 .10→1 与 0→.90），不要各自 `animateFloatAsState`。
  - 错峰区间必须有上限：最后一个元素的起点 + 时长要落在页面进场总时长之内，否则用户会觉得页面「一直在加载」。
  - 进场尚未完成时到达的返回，直接把时钟推到终态再退场，不要排队播完。

### 4.2 退场与后续动作（最重要的接线）

面板 / 菜单的关闭是一个**反向时钟 + 一次性后续动作**。正确形态：

```kotlin
enum class Pending { None, Download, OpenReader }

var visible by remember { mutableStateOf(false) }
var exitRequested by remember { mutableStateOf(false) }
var pending by remember { mutableStateOf(Pending.None) }
var exitDone by remember { mutableStateOf(false) }

// 1) 点击只决定「要做什么」+ 请求退场，不执行业务
onConfirm = { pending = Pending.Download; exitRequested = true }

// 2) 宿主播完反向动画，退场结束时回调一次
//    WenkuSheetHost 没有 onDismissed / onHidden，退场结束回调就是 onDismiss
WenkuSheetHost(
    visible = visible,
    anchorBounds = null,                // 必填；没有锚点就显式传 null
    dismissRequested = exitRequested,   // 程序化退场也走这条，才会触发 onDismiss
    onDismiss = {
        visible = false
        exitRequested = false
        exitDone = true                 // 只在退场真正结束时触发一次
    },
) { /* 内容在退场期间仍在组合里 */ }

// 3) 动作只在这里执行，且只执行一次
LaunchedEffect(exitDone) {
    if (!exitDone) return@LaunchedEffect
    val action = pending
    pending = Pending.None
    exitDone = false
    when (action) {
        Pending.Download -> download()
        Pending.OpenReader -> openReader()
        Pending.None -> Unit
    }
}
```

要点：

- 退场回调按宿主区分，别套同一个名字：`WenkuSheetHost` 只有 `onDismiss`，由关闭按钮 / 遮罩 / 返回键 / 拖拽达阈值 / `dismissRequested = true` 五条路径在反向动画结束后调用；`WenkuSheetDialog` 没有 `onDismissed`，它的 `onDismissRequest` 由内部宿主在退场结束时调用，程序化退场用 `requestDismiss = true`；`WenkuMenuHost` 的 `onDismiss` 只管用户路径，`onHidden` 才是「一定在退场结束后触发一次」。
- 直接把 `visible = false` 不会触发 `WenkuSheetHost.onDismiss`（宿主判定为外部收起，只播动画不走关闭分支），所以示例里用 `dismissRequested` 发起退场。
- 动作放在 `LaunchedEffect` 里并用 `pending` 消费，天然获得「重复触发被忽略」的保护；再叠一层 `busy` 让按钮在退场期间禁用（`WenkuSheetLifecycleTest` 覆盖了「忙碌面板吞掉返回但仍允许显式成功关闭」）。
- 拖拽关闭的阈值按 **dp/s 速度 + 位移比例** 双条件，速度与遮罩进度共用同一个值；不要用固定的像素距离。
- 面板内容在退场期间**保持组合**，只是不可交互；用 `graphicsLayer` / 进度驱动透明度，不要在 `visible=false` 时把子树移出组合。

### 4.3 交接的两个反例

```kotlin
// 错误 1：动画与业务并行，退场中途下载弹窗又弹出，遮罩错位
onConfirm = { scope.launch { downloadNow(); visible = false } }

// 错误 2：靠 delay 猜动画时长，改时长就错位
onConfirm = { visible = false; scope.launch { delay(320); openReader() } }
```

`delay(320)` 这类写法在审计里已经造成过真实问题（同一段 550ms 入场 / 320ms 退场的面板，被硬编码时间对齐后，减少动效档位下动作永久不执行）。一律用回调，不猜时长。

---

## 5. 文字与图标：同一组，有限回弹

### 5.1 文字

- 数字滚动 / 文本形态切换：`ui/wenku/WenkuTextMotion.kt::WenkuCountUp`、`WenkuMorphText`。用于统计数字、徽标计数、标签切换，不要给正文用。
- 文本与它所属的图标共享同一个进度区间，**同组进退**：图标先接触（0→.3），文字在 .2→.8 之间跟出，收尾一起停。分成两条独立动画会出现「字到了图标还在动」。
- 标题落位允许一次有界回弹（见 §5.3），但标签、按钮文字、输入框提示不做回弹。

### 5.2 图标

| 组件 | 位置 | 用途 |
|---|---|---|
| `WenkuIcon` | `ui/wenku/WenkuIcon.kt` | 独立图标按钮，自带按住 / 释放 / 点击反馈 |
| `WenkuIconResponse` / `WenkuIconContact` / `WenkuIconPose` | 同上 / `WenkuIconArt.kt` | 需要把按压状态交给父级的场合 |
| `WenkuButtonGlyph` | `ui/wenku/WenkuIconMapping.kt` | 复合按钮（胶囊、下载、播放、收藏）内部的图标 |
| `WenkuSymbol` | `ui/wenku/WenkuIconArt.kt` | 可用符号枚举，含分组与语义标签 |
| `WenkuPillButton` / `WenkuTextButton` / `WenkuIconButton` | `ui/wenku/WenkuGlass.kt` | 玻璃胶囊、文字按钮、图标按钮 |

规则：

- 有映射的图标用 `WenkuButtonGlyph`，映射之外的图标**保留 Ciallo 形状**并响应按住 / 松手，不要临时换成 Material 图标救场。
- 复合按钮（图标 + 文字 + 进度环）只能有一层按压变换：组件内部已经做了，外层不要再套 `scale`。
- 加载中的按钮在**所有画质档位**都禁用重复提交（审计明确要求）；禁用只改交互与着色，不要移除无障碍标签。
- 每个图标按钮必须有 `contentDescription`（或显式 `null` 并说明是装饰）。图标动了、语义没动，是这一轮重构最容易漏的回归。

### 5.3 有限回弹

- 允许回弹的位置：标题落位、卡片出现、按钮松开、Dock 指示器、滑条吸附。
- 不允许回弹的位置：窗口外边（会被裁切）、遮罩透明度、文字基线、进度条填充、页码。
- 幅度约束：过冲不超过该元素自身尺寸的约 3%，且必须在一次衰减内停住（`springMotionIsIndependentOfFrameSubdivision` 已覆盖「弹簧结果与帧切分无关」这一点，改参数后要复跑）。
- 禁止无限循环的呼吸 / 漂浮动画装饰可点区域；空闲插画保持静止（审计：互传空闲插画静止）。

---

## 6. 滚动、按压、语义、减少动效

### 6.1 滚动

- 边缘虚化：`WenkuScrollEdgeFade`。加在滚动容器外层，不加在每个 item 上。
- 滚动开始即结束按压追踪（审计：玻璃卡片在滚动接管时立即结束按压追踪），不要出现「滚动中卡片还在缩放」。
- 3D 书架锁定横向手势，纵向留给页面滚动。
- 滚动位置在「打开搜索 / 打开详情 / 返回上一页」之间必须保留；不要用 `key` 重建列表来「修」状态问题。
- 隐藏工具栏时保持测量与放置，只停止绘制、输入与滚动进度订阅（审计：隐藏时维持测量与放置）。用 `if (!visible) return` 直接摘掉子树会丢失滚动锚点。

### 6.2 按压

- 统一入口：`ui/wenku/WenkuGlass.kt::WenkuJellyTapSurface`、各按钮组件内部；组件外的 `Modifier.clickable` 只允许出现在手势基座上。
- 点按与拖拽按系统触摸阈值分流（Dock 的做法），不要自建阈值。
- 触觉反馈跟随设置里的触觉开关；关闭触觉后视觉反馈保留。
- 长按与点击共存时，长按不能同时触发点击。

### 6.3 语义

- 可访问性状态（选中 / 展开 / 禁用 / 进度）用语义属性表达，不靠颜色或描边单独表达（配色页的选中态就是「描边 + 勾选图标 + 无障碍状态」三者同时给）。
- 错峰进场的分块**跨过自己的起点后**才进入无障碍树（审计：两个胶囊分别跨过起点才进入树）。用 `WenkuStaggerPart` 的既有行为，别自己写 `visible` 门控。
- 弹层打开时，背景内容不可聚焦；关闭后焦点回到触发它的控件。
- 语义顺序 = 视觉顺序。`Row` 里图标在文字之后但语义要先读图标时，显式设置遍历顺序，不要靠视觉位置猜。

### 6.4 减少动效

- 读取系统设置：`ui/feedback/Motion.kt::systemReduceMotion()`、`ProvideMotion`；需要局部判断时用 `ui/god/GodMotion.kt::rememberReduceMotion()`。不要在多处各写一份 `Settings.Global.ANIMATOR_DURATION_SCALE` 读取。
- 减少动效下的正确行为：
  - 位移 / 缩放 / 回弹 → 直接终态，**保留**淡入淡出（或干脆瞬切，但不得比原来慢）。
  - 业务动作、导航目标、状态写入完全一致。`reducedMotionAndOffscreenDeletionStillRunTheBusinessActionOnce`、`progressAndSelectionRemainAccurateWithReducedMotion`、`reducedMotionKeepsAccurateStatusAndTheSameNavigationAction` 都是这条的回归。
  - 用户在系统里打开减少动效后，**不需要重启**就应生效；缓存过的 `remember` 值要随配置变化失效。
- 画质档与减少动效是两个独立维度：极简画质不等于关闭动效，减少动效也不等于降画质。

---

## 7. 窄窗、平板横竖、分屏与状态保留

### 7.1 布局切换

- 断点判断只在一处做：工作区用 `ui/adaptive/AdaptiveSpec.kt::AdaptiveWorkspace`，面板内容用 `AdaptiveSheetContent`。它读 `BoxWithConstraints` 的**实宽**，不是 `LocalConfiguration.screenWidthDp`；子组件不要各自判断宽度，否则同一屏会出现「一侧是侧栏、一侧是底部面板」。
- **1.6.1 分栏策略**：内容实宽 ≥ 960dp 且必须是横向时才左右分栏。
  ```kotlin
  AdaptiveWorkspace(
      splitAt = 960.dp,          // 实宽阈值，不是 Configuration 宽
      sidebarWidth = 300.dp,     // 左侧筛选列固定宽
      landscapeOnly = true,      // 平板竖屏（maxWidth <= maxHeight）保持上下
      controls = { split -> DiscoverFilters(split) },
  ) { split -> DiscoverResults(split) }
  ```
  `controls` 在左、`content` 在右；两槽是 `movableContentOf`，跨断点 / 旋转只换父容器，滚动位置、输入框和焦点都不重建。840dp 平板减去导航栏后不会被侧栏挤压，也不因为「横屏就分栏」把竖屏切成两半。
- 发现页网格列数只看结果区实宽：`split || windowWidth >= 600` → `GridCells.Adaptive(156.dp)`（最小卡宽 156dp，列数由宽度算），窄窗 → `GridCells.Fixed(2)`，列表布局 → `GridCells.Fixed(1)`。不要再用整屏 Configuration 宽写死 2/3/4 列，也不按 item 数量自适应。
- 设置页分组：标题与整组卡片由 `ui/SettingsTabScreen.kt::SettingsSection(title) { … }` 一起负责（内部 `SettingsSectionHeader` 统一 13sp / 半粗 / 次级色），页面里不要再手写分组标题或把卡片散落在分组之外。该函数是文件内 `private`，只在设置页组合使用。
- 分栏工作区详情：`ui/wenku/WenkuReflow.kt::WenkuSplitWorkspace`（列表 + 详情）、`WenkuReflowItem` / `WenkuReflowScope` / `WenkuReflowViewport`、`rememberWenkuReflowState()`。
- 宽屏下玻璃面板从底部改为右侧进出的，复用 `ComicSheetContainer` 的既有分支，不要新写一套侧栏动画。

### 7.2 窗口变化时不做的事

- 不对尺寸做动画（旋转时 `animateDpAsState` 会让内容抖一帧并触发重排）。
- 不重建 ViewModel / 不清空滚动位置 / 不重放进场动画。旋转后停在原来的阅读位置、原来的滚动位置。
- 不因为宽度变化关闭已打开的弹层；弹层改为新的形态（底部 ↔ 侧栏），内容状态保留。
- 分屏与折叠的窗口动画期间，玻璃采样必须保持存活（`dynamicBackdropAndResizingKeepRealGlassLive` 覆盖这一点）：不要在 `onSizeChanged` 里重建 backdrop。

### 7.3 状态保留清单

| 状态 | 存放方式 | 必须活过 |
|---|---|---|
| 搜索词、筛选、排序 | 提升到页面 ViewModel / `rememberSaveable` | 旋转、分屏、进出阅读器 |
| 滚动位置 | 列表状态 + 显式 key | 打开搜索覆盖层、返回详情 |
| 当前 Tab、书架/收藏视图、发现维度 | 各自保存，不共享 | 快速连点 Tab、旋转 |
| 弹层开合 | 可 `rememberSaveable`，但**退场进度不保存** | 旋转（重新以终态出现或直接关闭，二选一并写进注释） |
| 一次性动作（下载、跳转） | 仅内存，退场结束即消费 | 不要求活过进程重建 |
| 下载 / 阅读进度 | 持久层 | 进程重建、重启 |

原则：**可重建的 UI 状态用 saveable，业务事实用持久层，一次性动作绝不持久化**（否则重启后会重放一次下载）。

### 7.4 窄窗与大字号

- 宽度紧张时优先：隐藏次要图标 → 缩短标题（`WenkuMorphText` 或省略号）→ 换行 → 减少列数。**最后**才裁内容。
- 175% 字号下底部固定动作必须仍可达（`largeTextAndReducedMotionKeepThePrimaryActionReachable`）；面板内容滚动，动作区固定。
- 键盘弹出：面板底边跟随 `ime` 内边距，按钮在键盘之上；返回键先收键盘。审计记录过键盘与面板退场重叠导致 `InsetsPaddingModifier` 递归崩溃，取边距时用「键盘与导航栏取较大值」，不要在退场节点上叠加消费链。

---

## 8. 正确 / 错误示例汇总

### 8.1 卡片参数（主题一致性）

```kotlin
// 正确：参数来自共享状态，画质降级自动生效
// （示意：实参名以 ui/components/GlassCard.kt 的签名为准）
GlassCard(tweaks = cardTweaks, modifier = Modifier.fillMaxWidth()) { … }

// 错误：调用点写死，切极简画质后这块仍然高模糊 + 大圆角
Box(
    Modifier
        .clip(RoundedCornerShape(22.dp))
        .background(Color(0x33FFFFFF))
        .blur(24.dp)
)
```

### 8.2 背景采样

```kotlin
// 正确：页面持有一个 backdrop，面板复用 panel backdrop
val screen = rememberScreenGlassBackdrop()
val panel = rememberGlassPanelBackdrop()
PageGlass(backdrop = screen) {
    WenkuSheetHost(
        visible = sheetVisible,
        anchorBounds = null,
        onDismiss = { sheetVisible = false },   // 注意：程序化退场要配 dismissRequested
    ) { PanelGlass(backdrop = panel) { … } }
}

// 错误：面板内部再开一个整屏采样 → 退出时采样先撤，最后一帧变黑
WenkuSheetHost(visible = sheetVisible, anchorBounds = null, onDismiss = { sheetVisible = false }) {
    val b = rememberScreenGlassBackdrop(); PanelGlass(backdrop = b) { … }
}
```

### 8.3 关闭后执行

见 §4.2 的三段式写法；对照两条反例（并行起跑、`delay` 猜时长）。

### 8.4 文字与图标同组

```kotlin
// 正确：一个 progress，两段区间（示意：形参以 WenkuMotion.kt / WenkuIconMapping.kt 为准）
WenkuContentClock(progress) { p ->
    WenkuButtonGlyph(symbol = WenkuSymbol.Download, press = press, progress = p)
    Text(label, modifier = Modifier.graphicsLayer { alpha = smooth(p, .2f, .8f) })
}

// 错误：图标和文字各自动画，节奏永远对不上，减少动效下还会不同步结束
WenkuButtonGlyph(…)                       // 内部 220ms
AnimatedVisibility(visible) { Text(label) } // 内部 300ms + spring 过冲
```

### 8.5 列表进场

```kotlin
// 正确：序列钟 + 有上限的错峰（示意：形参以 ui/wenku/WenkuMotion.kt 为准）
WenkuLoadedContentSequence(contentKey = viewKey, loading = isLoading) {
    items(list, key = { it.id }) { item -> WenkuStaggerPart(index = index) { Card(item) } }
}

// 错误：每个 item 自己 LaunchedEffect { delay(index * 40); visible = true }
// 长列表尾部要等几秒；滚动复用后下标变化会重播；离屏 item 也在跑时钟
```

### 8.6 旋转

```kotlin
// 正确：实宽 + 方向在 AdaptiveWorkspace 内部决策一次，状态留在上层
AdaptiveWorkspace(
    splitAt = 960.dp,
    sidebarWidth = 300.dp,
    landscapeOnly = true,
    controls = { split -> Filters(split) },
) { split ->
    DiscoverGrid(
        split = split,
        cells = if (split) GridCells.Adaptive(156.dp) else GridCells.Fixed(2),
    )
}

// 错误 1：用 Configuration 宽猜断点，平板竖屏也被切成左右两栏
val wide = LocalConfiguration.current.screenWidthDp >= 840
if (wide) SplitWorkspace(state) else StackedWorkspace(state)

// 错误 2：尺寸参与动画，旋转时列表重排 + 进场重播
val w by animateDpAsState(if (wide) 420.dp else 0.dp)
```

---

## 9. 小型验收清单

每次改动至少走完 A 段；改动涉及弹层 / 导航 / 布局时补 B 段；涉及图标与文字时补 C 段。记录格式沿用审计文档：**页面 → 按钮 → 打开/关闭路径 → 设置影响 → 证据**。

### A. 通用（每次都做）

- [ ] 四条关闭路径都只执行一次后续动作：关闭按钮、遮罩点击、返回键、拖拽达阈值。
- [ ] 关闭过程中内容仍在，没有闪空 / 闪黑 / 双遮罩。
- [ ] 过渡中重复点击不产生第二个面板、第二次导航或第二次下载。
- [ ] 旋转 / 折叠 / 分屏后：滚动位置、搜索词、筛选、Tab 不变；没有重播进场。
- [ ] 系统「减少动效」打开后：动作与导航不变，位移消失，无需重启即生效。
- [ ] 玻璃参数只来自共享状态；切到极简画质仍有圆角、色调、透明度。
- [ ] 语义：图标有标签，弹层打开时背景不可聚焦，关闭后焦点回到触发控件。
- [ ] 无新增硬编码颜色 / 圆角 / 时长；无 `delay(<动画时长>)`。

### B. 层级与布局

- [ ] 新页面走双向路由；返回恢复原页面画面与位置（含已访问页复用）。
- [ ] 锚定菜单从真实卡片边界展开，退场期间保留锚点。
- [ ] 窄窗（≤ 360dp 宽）、平板横、平板竖、分屏各看一次：内容不裁切，底部动作可达。
- [ ] 175% 字号 + 键盘弹出：按钮在键盘之上，返回先收键盘。
- [ ] 嵌套不超过两层；Dock / 底栏在最后一层退场后出现。

### C. 文字与图标

- [ ] 图标与文字同一进度区间进退，收尾同时停。
- [ ] 回弹只出现在允许位置（§5.3），且一次衰减内停住。
- [ ] 未映射图标保留 Ciallo 形状，仍响应按住 / 松手。
- [ ] 加载中按钮在所有画质档位禁用重复提交，语义标签未丢。

### D. 回归与证据

- [ ] 弹层生命周期：`app/src/test/java/com/example/ui/wenku/WenkuSheetLifecycleTest.kt`。
- [ ] 动效与业务一致性：`ui/MotionPolishTest`、`ui/components/SharedStateMotionTest`、`ui/components/LiquidSegmentPhysicsTest`、`ui/components/ScrollTiltSchedulingTest`、`ui/components/PaperAndSliderTest`。
- [ ] 卡片参数与画质：`ui/components/GlassCardTweaksTest`。
- [ ] 设备夹具（`app/src/androidTest/`）：`ui/FoldMotionDeviceTest`、`ui/PaperPolishDeviceTest`、`ui/OnboardingWenkuDeviceTest`、`ui/CoverGalleryDeviceTest`、`ui/comic/ComicGlassPolishTest`。
- [ ] 改了上述区域但没跑对应用例时，PR 必须写明「未跑 / 未验」，不要用其他用例的结果代替。

---

## 10. 组件索引（都在本仓库，签名以文件为准）

| 文件 | 组件 |
|---|---|
| `ui/wenku/WenkuTokens.kt` | `WenkuColors`、`WenkuAccessibility`、`rememberWenkuAccessibility()` |
| `ui/wenku/WenkuGlass.kt` | `WenkuGlassSurface`、`WenkuIconButton`、`WenkuTextButton`、`WenkuPillButton`、`WenkuJellyTapSurface`、`WenkuContentPanel`、`WenkuMenu`、`WenkuSectionHeader`、`WenkuSearchField`、`rememberWenkuSearchMotion`、`WenkuHairline` |
| `ui/wenku/WenkuMenuHost.kt` | `WenkuSheetHost`、`WenkuSheetDialog`、`WenkuSheetSurface`、`WenkuMenuHost`、`WenkuMenuTrigger`、`WenkuMenuStretch`、`WenkuStaggerPart`、`wenkuRevealProgress` |
| `ui/wenku/WenkuMotion.kt` | `WenkuLoadedContentSequence`、`WenkuContentClock`、`WenkuLoadingHandoff`、`WenkuSkeletonBlock`、`WenkuScrollEdgeFade`、`rememberWenkuReaderChrome`、`WenkuReaderChromeLayer`、`WenkuReaderChromePart`、`WenkuReaderProgressSlider` |
| `ui/wenku/WenkuPageEntrance.kt` | `WenkuPageEntrance` |
| `ui/wenku/WenkuTextMotion.kt` | `WenkuMorphText`、`WenkuCountUp` |
| `ui/wenku/WenkuIcon.kt` / `WenkuIconArt.kt` / `WenkuIconMapping.kt` | `WenkuIcon`、`WenkuIconResponse`、`WenkuIconContact`、`WenkuIconPose`、`WenkuSymbol`、`WenkuButtonGlyph` |
| `ui/wenku/WenkuReflow.kt` | `WenkuSplitWorkspace`、`WenkuReflowItem`、`WenkuReflowScope`、`WenkuReflowViewport`、`rememberWenkuReflowState` |
| `ui/wenku/WenkuAnchors.kt` | `rememberWenkuAnchor`（返回 `Pair<Rect?, (Rect) -> Unit>`）、`Modifier.wenkuAnchor`（root 坐标 `boundsInRoot()`） |
| `ui/wenku/WenkuActionSheet.kt` | `WenkuActionSheet` |
| `ui/wenku/ArchiveShelf.kt` | `ArchiveShelf` |
| `ui/wenku/WenkuCoverFlight.kt` / `WenkuFlightBridge.kt` / `WenkuCoverSnapshots.kt` | `WenkuCoverFlight`、`WenkuCoverArrival`、`WenkuFlightOverlay`、`WenkuFlightRequest`、`WenkuCoverSnapshot` |
| `ui/components/LiquidGlass.kt` | `rememberScreenGlassBackdrop`、`rememberThemedGlassBackdrop`、`rememberGlassPanelBackdrop`、`rememberGlassFxChain` |
| `ui/components/GlassCard.kt` / `CardTweaks.kt` / `CardAppearanceScreen.kt` | `GlassCard`、`CardTweaks`、`CardAppearanceScreen`、`CardTweakSlider` |
| `ui/components/PullOutHosts.kt` / `OverlayAnimations.kt` | `PullOutBottomSheet`、`BottomSheetEntrance` |
| `ui/glasskit/GlassKit.kt` / `GlassManagementTopBar.kt` | `GlassKitHost`、`GlassKitCard`、`GlassTopBarStrip`、`GlassManagementPage`、`GlassManagementTopBar` |
| `ui/feedback/Motion.kt` / `ui/god/GodMotion.kt` | `ProvideMotion`、`systemReduceMotion()`、`rememberReduceMotion()` |
| `ui/adaptive/AdaptiveSpec.kt` | `AdaptiveWorkspace`（实宽 `splitAt` / `sidebarWidth` / `landscapeOnly`）、`AdaptiveSheetContent`（断点与面板形态） |
| `ui/SettingsTabScreen.kt` | `SettingsSection`、`SettingsSectionHeader`（文件内私有：标题 + 整组卡片） |

---

## 11. 提交前自检（三行版）

1. 参数从哪来？——主题 / 卡片状态，不是一个字面量。
2. 关闭后动作在哪执行？——退场回调里，一次，且可重复点击不重复执行。
3. 减少动效、旋转、分屏下还成立吗？——不成立就别提 PR，或明确标注「已接线，待验」。
