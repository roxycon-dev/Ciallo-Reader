# 阅读翻页与标签搜索返回修复

本次修改针对两处阅读问题。初次单独修复阶段未更改版本号或生成安装包；现已整合进 1.3.1 / 212，见 [发布验证](release-1.3.1-2026-10-11.md)。

## 漫画跨话翻页

预加载下一话时，Compose 会复用翻页组件，但章节的页码状态已经更换。原有分页监听仍然调用上一话的回调，造成画面与当前页码不同步，末页继续翻时可能回到本话首页。磁吸翻页在前后章节页数相同时也会复用手势监听，存在同类问题。

`ComicReaderCore.kt` 中两处长时间存活的监听改为通过 `rememberUpdatedState` 调用当前回调。新增回归连续跨越五个等长章节并逐页返回，覆盖普通翻页、磁吸翻页及从右向左阅读。

修复前已复现第二话页码没有更新；漫画相关 313 项回归通过，结果保存在 `artifacts/comic-chapter-navigation-fix/`。

## 标签搜索逐层返回

原来的标签快捷搜索会回退到主页并清掉详情页，同时复用全局搜索和作品状态。现改为每次标签搜索压入一个新的导航记录，即使标签相同也保留独立层级：

`原详情 → 标签搜索 → 新详情 → 新标签搜索 → 新详情`

每层搜索使用该导航记录持有的 `LibraryViewModel`，保留自己的搜索结果、关键词、书源选择和作品状态。详情页与漫画/小说在线阅读器通过导航参数使用对应搜索层的状态。返回时恢复上一层；搜索页增加返回按钮，导航层保留原有 `rememberSaveable` 的输入与滚动状态。

应用级书源注册、下载管理、关键词服务和全局偏好仍共享，搜索层不重复初始化这些服务。

主要修改文件：

- `app/src/main/java/com/example/MainActivity.kt`
- `app/src/main/java/com/example/library/LibraryNavigation.kt`
- `app/src/main/java/com/example/library/LibraryViewModel.kt`
- `app/src/main/java/com/example/library/LibraryScreen.kt`
- `app/src/test/java/com/example/library/LibraryNavigationTest.kt`

## 验证状态与后续整合

以下为单独修复阶段的验证记录；整合后已重新运行 `LibraryNavigationTest` 与全部 `ui.comic` 回归，同时验证动效、玻璃设置、弹层、主题、开屏和底栏。1.3.1 发布回归共 47 个套件、327 项测试，失败、错误和跳过均为 0，包括之前等待超时的多层返回场景。

- 停止构建前，漫画/小说阅读器返回、空标签与独立书源选择四个导航测试场景通过。
- 多层返回测试在等待异步搜索结果时超时；测试随后增加了对 Robolectric 暂停主 Looper 的推进，该调整尚未重跑，不能视为全部导航回归已通过。
- `git diff --check` 与 `tools/ui-gate.ps1` 通过。
- 用户要求其他 AI 完成修改前不再编译打包，已停止本次 Gradle 调用。其他 AI 的现有改动予以保留。

电脑端整合回归已完成。仍需实机检查连续点标签、进入结果详情/阅读器、逐层返回时的内容与滚动位置；本次未进行实机验证。
