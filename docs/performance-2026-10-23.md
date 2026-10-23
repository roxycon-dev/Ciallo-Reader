# 平板性能优化 · 2026-10-23

基于 1.5.0 / 242。保持原版本、数据库结构、书源协议、阅读参数与全部视觉参数。没有调整布局、颜色、字体、图片分辨率、玻璃质量、动画曲线或时长。本轮为本地性能验证包，不向 GitHub 发布。

## 已完成

| 路径 | 改动与作用 |
| --- | --- |
| `ui/ReaderProgressEffect.kt`、`ui/ReaderScreen.kt` | 用 `snapshotFlow` + `collectLatest` 观察滚动，保留 1500ms 防抖；不再把每个像素偏移作为整页 `LaunchedEffect` 键。回顶按钮改为派生布尔状态，只在原 20% 阈值跨越时更新。退出保存读取最新章节与偏移，避免首次组合的旧闭包。正文准备复用同一次格式化结果。 |
| `data/ReaderChapterWindow.kt`、`MainViewModel.kt` | 保留当前 / 前一 / 后一章窗口，复用已加载的两章，只读取新进入窗口的一章。逻辑章拼接与元数据合并放到 Default 线程；选择代校验、取消和不完整正文报错保留。窗口外正文仍释放，不累积整本书。 |
| `data/AppDatabase.kt`、`MainViewModel.kt` | 收藏的已下载话数通过分组计数查询获得，不再在每次偏移写库后构造整张书架及分组列表；相同结果去重。聚合工作在 Default 线程，界面无人订阅后停止。来源和作品双键保持独立。 |
| `MainActivity.kt`、`MainViewModel.kt` | 完整阅读记录和会话仅由统计 Tab 订阅；发现页用 `SELECT DISTINCT bookTitle`，保留已读推荐依据，避免加载全部历史。 |
| `ui/StatisticsScreen.kt`、`ui/components/ReadingStats.kt` | 本周的分钟、标题和明细共用一次日期索引，替代原来 7×3 次全历史过滤；仍保持原始顺序、去重与合并规则。 |
| `data/BookRepository.kt`、`data/AppDatabase.kt` | 启动超长章检查用单次相关 EXISTS 查询筛选候选书，替代逐本 `hasOversizedChapter`。原拆分事务、书签迁移、进度与跳过规则保留。Room Flow 增加相同结果去重。 |
| `ui/ReaderPagination.kt` | 原 8 项 LRU 同时增加 8 MiB 文本载荷预算，计算整章字符串、分页字符串和保守对象开销；超大项不挤掉有用缓存。页内容和断行算法不变。 |
| `ui/reader/NovelInlineImages.kt`、`MainActivity.kt` | 相邻插图预解码串行并重复检查缓存，避免多张大图同时分配；内存压力 / 页面隐藏通知清理分页和插图缓存，只释放缓存所有权，不 recycle 正在显示的位图。已排队旧预热不会重新填回被清理的缓存。 |

这里的 8 MiB 是分页缓存的保守载荷计量，不是整机 PSS，也不包含正在显示的页面。插图采样精度保持原样。

## 验证

- Debug Kotlin 编译通过；最终 13 个套件、59 项 JVM / Robolectric 检查通过，失败 / 错误 / 跳过均为 0。明细：`artifacts/tablet-performance/test-results.json`。
- 平板 1280×800 原生 Robolectric 渲染：极简滚动与高画质分页各 1,024,000 像素，对照任务开始时 HEAD 的原 `ReaderScreen`，两组变化像素均为 **0**。基线仅重命名入口和文件私有符号以便同一测试进程并列渲染，临时副本已移出 `src/test`，保存在验收目录。截图和结果：`artifacts/tablet-performance/visual/`。
- 60 次连续滚动更新，父组合次数增量为 0；跨过原回顶阈值只增加 1 次。防抖、最新回调、切章取消和禁用均通过。
- 顺序切章测试确认只请求新章、相邻正文对象保持相同、离窗正文清空；另测跳章、空正文、续章拼接与缺少分片失败。
- 真实 Room 检查涵盖下载计数 / 来源隔离 / 进度更新 / 删除、已读标题去重与删除、启动候选的长度边界；统计日期索引用 10,001 条记录与旧过滤结果逐项比较。
- 分页与插图旧回归、数据库迁移、漫画导航和章节书签回归通过。内存清理后原位图仍未回收、像素值保持。
- `tools/ui-gate.ps1` 沿用原基线通过，未重置基线。`git diff --check` 通过。
- Release 构建通过（2 分 39 秒）；正式固定签名与已发布 1.5.0 相同，包名 / 版本仍为 `com.aistudio.novelreader.kxmpzq` / 1.5.0 / 242，同版本覆盖安装包。ARM64 单架构、ZIP CRC 与 16 KiB 对齐通过；1496 个生产输入与冻结清单匹配。安装包身份见 `artifacts/tablet-performance/release-identity.json`。

验收截图仅覆盖上述两个阅读场景；没有把它解释为所有页面、所有显卡驱动的像素保证。没有连接 Android 实机，未获得平板掉帧率、帧时间或 PSS 的前后数据，不报告未测量的百分比。需要在实际平板上用 `verify_perf.ps1` 复测设置 / 统计滚动与实际阅读。

首次测试过滤配置未生效，扩大执行到 215 项时出现 4 项失败、1 项跳过，随后终止未完成的测试进程；不能称全量通过。失败涉及 `AppUpdateManagerTest` 两项 READY 状态断言、`RealEpubPipelineTest` 缺少用户 Downloads 内指定 EPUB、`Round7DataTest` 的旧关键词过滤断言。这些项目未在本轮修复；最终 59 项为明确限定的相关套件。原始日志：`.perf-tests.log`。

## 复现与环境

所有构建使用工程原 JBR 与离线依赖。本机 JBR 21 的 Unix-domain 管道报 `Invalid argument: connect`，本次仅对命令进程设置 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/Users/GuanXingRen/AppData/Local/Temp/ciallo-no-unix-socket`，该不存在目录使 JDK 使用原有 TCP 回退；没有更改项目 JDK、全局 Java 参数或系统网络设置。

持久回归用例为 `ReaderChapterWindowTest`、`LibraryPerformanceQueryTest`、`ReaderPaginationCacheTest`、`ReaderProgressEffectTest`，以及 `NovelImageCacheTest` 新增的缓存所有权检查。运行截图回归时把验收目录的基线和 `ReaderVisualParityTest.kt` 临时复制到测试源码，运行 `-Proborazzi.test.record=true`，结束后移除。相关 Gradle 初始化脚本保存在 `artifacts/tablet-performance/`；正式包不包含这些验证代码。

## 本地安装包

- 文件：`artifacts/tablet-performance/Ciallo-Reader-v1.5.0-performance.apk`。
- 大小：29,193,028 B。
- SHA-256：`ae5bdb49aad30432112442405e3762e0643454638faf19eaf27b1c346969c162`。
- 工程说明源码覆盖 741 个 Kotlin / Java 文件，遗漏 0；反向检查四项均为明确标记删除的旧文件。
- 同版本覆盖包不通过应用内更新推送，未修改线上发行版。


后续 1.5.1 / 243 已把以上优化纳入正式发行，并追加条漫几何缓存与进度订阅范围优化；当前发行及验证以 [1.5.1 说明](release-1.5.1-2026-10-23.md) 为准，上述同版本本地包为历史验收产物。
