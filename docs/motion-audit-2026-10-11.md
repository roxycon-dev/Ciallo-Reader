# Ciallo Reader 动效排查清单

日期：2026-10-11

范围：书架、书库搜索、小说与漫画阅读器、下载、书源管理、设置、统计和开屏。此次检查基于当前工作区源码；没有连接 Android 设备，未做真机录屏或帧率测量。下列“现状”来自代码，“建议”是设计判断，时长是待预览调校的起点。

优先级含义：P1 为高频交互或明确过渡缺口；P2 为整体一致性与细节；P3 为装饰效果和等待感的收敛。本清单不改变应用实现。

## P1：优先处理的 8 个点

| 编号 | 位置 | 代码中的现状 | 建议动效 | 源码位置（仓库根目录起） |
| --- | --- | --- | --- | --- |
| 01 | 通用弹窗、底部浮层 | `DialogEntrance` / `BottomSheetEntrance` 只把状态从 false 切到 true；`AcrylicDialog` / `AcrylicBottomOverlay` 的关闭直接调用外部回调，没有等待退出动画的生命周期。小说阅读排版面板已经有等待退出完成的实现，可作为参考。 | 面板退回来源方向、遮罩同步淡出，退出完成后才卸载；退出约 140–200ms。 | `app/src/main/java/com/example/ui/components/OverlayAnimations.kt:28`；`app/src/main/java/com/example/ui/components/AcrylicDialog.kt:97`；`app/src/main/java/com/example/ui/reader/ReadingSettingsPanel.kt:43` |
| 02 | 小说插图全屏查看 | 双击直接把 scale 从 1 改成 2.5 或改回 1，平移也直接归零；查看器自身没有打开、关闭的内容过渡。 | 双击围绕点击位置平滑缩放，缩放和平移同步收敛，约 180–240ms；打开与关闭补短淡入淡出，后续可做插图到全屏的共享过渡。 | `app/src/main/java/com/example/ui/reader/NovelImageFullscreen.kt:71`、`:117` |
| 03 | 横屏、平板的阅读设置面板 | 小说排版面板右侧布局仍使用纵向位移；漫画设置右侧布局由全屏容器沿纵向整屏滑入。显示位置与运动方向不一致。 | 手机底部面板从下方出现；右侧面板从右侧出现。动画作用于面板本体，用面板尺寸计算位移，约 220–300ms。 | `app/src/main/java/com/example/ui/reader/ReadingSettingsPanel.kt:55`；`app/src/main/java/com/example/ui/comic/ComicReaderChrome.kt:282`；`app/src/main/java/com/example/ui/comic/ComicReaderSheets.kt:205` |
| 04 | 漫画设置 → 预设 / 裁边 / 目录等面板 | `displayedPanel` 非空时直接更新，外层可见状态不变，内部 `when` 替换内容；不同面板之间缺少独立过渡。七个设置分类已有淡入淡出。 | 面板间增加短内容转场：前进与返回用相反方向的少量位移或淡出后淡入；保持遮罩和外壳稳定。分类切换只需微调现有节奏。 | `app/src/main/java/com/example/ui/comic/ComicReaderChrome.kt:266`、`:289`；`app/src/main/java/com/example/ui/comic/ComicReaderSheets.kt:237` |
| 05 | 书架排序、筛选、删除后的补位 | 本地书架和书架内收藏网格已有稳定 key、选中与按压动画，但条目容器没有位置重排动画；独立 `FavoritesPanel` 已有 `animateItemPlacement`。 | 排序与删除补位使用位置过渡；分类筛选使用短淡出后淡入。拖拽期间由拖拽宿主管理位置，避免两套动画同时控制。 | `app/src/main/java/com/example/ui/HomeScreen.kt:1447`、`:1521`、`:1911`；`app/src/main/java/com/example/ui/favorite/FavoritesPanel.kt:239` |
| 06 | 搜索用词卡片展开 / 收起 | `expanded` 控制 `if/else` 直接替换横向摘要和详细列表；箭头直接换图标，状态文字直接改变。 | 高度平滑展开、内容淡入、同一个箭头旋转；查词状态使用短淡入淡出。约 180–240ms，防止卡片展开时下方结果突然跳位。 | `app/src/main/java/com/example/library/KeywordSearchPreview.kt:62`、`:82`、`:85` |
| 07 | 下载进度条和百分比 | 进度采用 `MediumBouncy + StiffnessLow` 弹簧，百分比由动画中的进度计算；回弹会使视觉值在目标附近来回收敛。 | 同一任务下载期间进度保持单向增长，使用短补间或无回弹弹簧；百分比取实际进度并稳定宽度，暂停时冻结，任务切换时重置。 | `app/src/main/java/com/example/library/DownloadGlassCard.kt:102`、`:108`、`:260` |
| 08 | 下载完成 / 暂停 / 失败的状态变化 | 状态区直接用 `when(state)` 换内容；成功分支新建 `AnimatedVisibility(visible = true)`，没有显式的 false → true 状态，不能依赖它实现所写的完成入场动画。 | 按下载状态类型驱动内容过渡；100% 后勾号描绘或轻缩放一次，完成文案再出现。暂停与失败使用短淡入，不重播整张卡片。 | `app/src/main/java/com/example/library/DownloadGlassCard.kt:250`、`:275` |

## P2：建议接着调整的 9 个点

| 编号 | 位置 | 代码中的现状 | 建议动效 | 源码位置（仓库根目录起） |
| --- | --- | --- | --- | --- |
| 09 | 全局动效节奏 | `AppMotion` 和 `IosMotion` 两套令牌并存，弹簧阻尼分别包含 0.635、0.462、0.85、0.90；业务页面还有大量独立 tween 和弹簧参数。 | 按“按压、选中、展开、页面、弹层、完成反馈”统一语义规格；按压快速、页面克制、成功反馈可轻弹。保留翻页与拖拽的专用物理参数。 | `app/src/main/java/com/example/ui/feedback/Motion.kt:24`；`app/src/main/java/com/example/ui/theme/IosMotion.kt:25` |
| 10 | 页面前进 / 返回与底部 Tab 切换 | 小说阅读页前进是横向滑入、返回是缩小淡出；漫画阅读页是缩放淡入。其他路由又有不同组合。主 Tab 已经是先淡出再淡入，底栏另用弹簧移动指示器。 | 为详情、阅读、管理等页面定义一致的前进和返回方向；主 Tab 保留现有无重影过渡，调校内容与底栏指示器的落位时间，并检查快速连续切换。 | `app/src/main/java/com/example/MainActivity.kt:671`、`:1150`、`:1239`；`app/src/main/java/com/example/ui/components/AppBottomTabBar.kt:214` |
| 11 | 点击封面进入详情 / 阅读 | 本地书架 → 小说阅读已用共享封面；书库结果和漫画章节详情没有接入同样的共享过渡。 | 优先补书库封面 → 详情封面，返回时回到原卡片；保留列表位置和同一张已加载封面，避免图片重新加载打断过渡。 | `app/src/main/java/com/example/ui/HomeScreen.kt:1554`；`app/src/main/java/com/example/ui/ReaderScreen.kt:1159`；`app/src/main/java/com/example/library/LibraryScreen.kt:3038`；`app/src/main/java/com/example/ui/ComicChaptersScreen.kt` |
| 12 | 滚动时顶部栏收起 | 以 20dp 滚动阈值切换布尔折叠态，再用 180–220ms 补间追踪；阈值附近往返滚动可能反复切换目标。 | 标题大小、间距和副标题透明度按滚动距离连续变化；或增加展开 / 收起的不同阈值。手指停止后再吸附到完整状态。 | `app/src/main/java/com/example/ui/components/TabScreenHeader.kt:95`、`:130` |
| 13 | 搜索框聚焦与搜索历史展开 | 历史面板通过展开高度影响结果布局；实际入场是 320ms、退出是 280/240ms，附近注释写成 200/180ms，与代码不一致。 | 实际缩短到约 180–220ms；让历史卡、焦点反馈和键盘布局协调；继续保留聚焦期间禁用结果重排的处理。 | `app/src/main/java/com/example/library/LibraryScreen.kt:678`、`:693` |
| 14 | 列表卡片的入场重播 | 搜索卡与 MAX 玻璃卡均用局部 `remember { entered=false }` 和 `LaunchedEffect(Unit)` 播入场，条目离开组合后重新组合时可以再次播。 | 新搜索或首次进入页面播放一次；正常上下滚动保持卡片直接可见；首屏错峰只给少数条目做几十毫秒的间隔，避免长列表持续延迟。 | `app/src/main/java/com/example/library/LibraryScreen.kt:3053`；`app/src/main/java/com/example/ui/components/GlassCard.kt:390` |
| 15 | 主色切换与日夜模式 | 主色和辅色已有 600ms 动画；日夜模式通过 `if (darkTheme)` 直接替换色板，background、surface、正文色等没有同样的过渡。 | 主色变化缩短到约 250–350ms；日夜模式统一过渡背景、表面和文字色，约 220–300ms，并检查中间帧的可读性。 | `app/src/main/java/com/example/ui/theme/Theme.kt:42`、`:58`、`:78` |
| 16 | 统计数字、目标圆环和趋势图 | 总时长和目标进度采用可回弹弹簧；趋势图每当 `series` 改变就清零并重新描绘 700ms。已有动画，但数值跳动和反复重播值得收敛。 | 数字和圆环无回弹收敛；首次进入时描绘图表，周/月/年切换再做变化过渡；实时读书时长的小变化保持图表稳定。 | `app/src/main/java/com/example/ui/StatisticsScreen.kt:371`；`app/src/main/java/com/example/ui/components/ReadingTrendCard.kt:256` |
| 17 | 卡片基础按压反馈 | `GlassCard` 的按压缩放只在 MAX 档启用；另一套 `GlassKitCard` 有独立的常规按压反馈；书架与搜索卡也各有一套。 | 轻量缩放和高亮在可点击卡片上统一；倾斜、压痕和复杂折射按画质档分级。不同页面点击同类卡片应有接近的手感。 | `app/src/main/java/com/example/ui/components/GlassCard.kt:299`；`app/src/main/java/com/example/ui/glasskit/GlassKit.kt:337`；`app/src/main/java/com/example/ui/HomeScreen.kt:1515` |

## P3：建议收敛的 3 个点

| 编号 | 位置 | 代码中的现状 | 建议 | 源码位置（仓库根目录起） |
| --- | --- | --- | --- | --- |
| 18 | 书架封面持续浮动 | 有书且不滚动、不拖拽时，所有书卡按独立相位持续上下浮动 ±3dp，33ms 更新共享时钟。暂停滚动中的浮动已有处理。 | 默认保持封面静止；浮动只留给吉祥物、空状态或短暂的重点卡片，阅读入口更稳。 | `app/src/main/java/com/example/ui/HomeScreen.kt:754`、`:1526` |
| 19 | 减少动态效果的统一接入 | 已有 `LocalReduceMotion`，但上述书架自定义循环、通用弹层和部分卡片效果没有读取它；系统设置读取使用 `remember(context)`，没有监听设置变化。 | 新动效统一接入同一开关；尤其让自定义循环停止，让大位移退化为淡入；刷新系统设置状态。这属于体验一致性收尾，不能只靠动画时长为 0。 | `app/src/main/java/com/example/ui/feedback/Motion.kt:103`、`:210`；`app/src/main/java/com/example/ui/HomeScreen.kt:762`；`app/src/main/java/com/example/ui/components/OverlayAnimations.kt` |
| 20 | 开屏等待感 | 普通海报流程依次为 400ms 淡入、900ms 停留、350ms 淡出，合计约 1650ms；另有 800ms 缩放。已有点击跳过和纯净模式。 | 缩短整体停留，或让启动准备完成后尽快退出；保留海报的短展示，避免为了展示动效额外等待。 | `app/src/main/java/com/example/ui/SplashScreen.kt:93`、`:106` |

## 建议实施顺序

1. 先选定统一的基础节奏；补通用弹层退出生命周期、阅读器面板方向与面板间过渡。
2. 补小说插图缩放、下载进度与状态反馈；书架排序补位、关键词卡展开。
3. 统一页面转场与基础按压；调整顶部栏、搜索聚焦、卡片重播和主题变化。
4. 最后收敛统计重播、持续装饰动画和开屏等待，并统一减少动态效果。

现有较成熟的能力可作为基础：书架拖拽拿起与归位、小说共享封面、漫画手势跟随翻页与缩放、收藏爱心、主 Tab 无重影切换、小说排版面板等待退出完成。优先修补上表的衔接缺口。

## 后续真机验收场景

- 连续打开 / 关闭弹层、切面板、快速点不同 Tab：动画能被新操作打断并从当前状态继续。
- 书架排序、删除、筛选、拖拽：位置不瞬移，拖拽落点保持准确。
- 手机竖屏、短横屏、平板：面板方向匹配出现位置，大字体不裁切。
- 下载暂停 / 继续 / 完成 / 失败：进度不倒退，状态变化不突然，完成反馈只播放一次。
- 搜索历史展开后输入、滚动离屏再返回：键盘和列表不发生多重位移，卡片不反复播入场。
- 小说插图双击与缩放中再次双击：缩放围绕点击位置，能中断，回到原位无跳变。
- 低画质与减少动态效果：保留明确状态反馈，暂停持续装饰动画。

这些场景尚未执行；源码排查不能替代真机对跟手程度和掉帧的判断。
