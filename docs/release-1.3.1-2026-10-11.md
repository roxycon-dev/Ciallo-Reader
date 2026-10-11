# Ciallo Reader 1.3.1 · 2026-10-11

版本 `1.3.1 / 212`，Android 7.0+、arm64-v8a。沿用 1.3.0 的签名证书，可覆盖安装并保留数据。

## 本次更新

- 书架删除实际书卡碎裂并平滑补位；排序、筛选与收藏网格补齐位置、淡入和淡出动画，排序保留视口，拖拽保持跟手。快照不可用、减少动态效果及取消协程时仍完成已确认删除，隐私锁隐藏残留碎片。
- 小说和漫画设置的内部分组、分类导航、选项与开关统一分层毛玻璃；仿真卷页面板用当前已处理页面建立专用采样层，面板退出后释放。
- 统一页面与 Tab、共享封面、弹层退出、小说插图缩放、关键词卡、下载状态与完成勾号、统计、主题和开屏的动效；实时响应系统减少动态效果设置。
- 修复漫画预加载跨话后旧回调导致页码不同步；标签搜索改为独立导航层，每层保留关键词、结果与书源状态，详情及在线阅读器使用所属搜索层，返回恢复上一层。

详细记录：[动效审计](motion-audit-2026-10-11.md)、[动效与材质实现](motion-polish-2026-10-11.md)、[阅读与导航修复](reader-navigation-fixes-2026-10-11.md)。

## 正式构建验证

- `:app:assembleRelease`、`:app:compileDebugAndroidTestKotlin` 与所选 `:app:testDebugUnitTest` 回归全部通过；Release 使用 R8 混淆与资源精简，清单未开启 debuggable。
- 47 个套件、327 项 JVM / Robolectric 回归，失败、错误和跳过均为 0。包含全部 `ui.comic` 测试，以及书架动效、弹层、小说玻璃、主题、开屏、文字排版、底栏、关键词卡、标签导航和书库初次启动测试。
- `tools/ui-gate.ps1` 通过，原有基线未修改；`git diff --check` 通过。
- 实际 APK：`com.aistudio.novelreader.kxmpzq`、`1.3.1 / 212`、最低 API 24、目标 API 35，仅 arm64-v8a；大小 `24,124,439` B（约 23.0 MiB）。
- 签名证书 SHA-256：`d2115e3cc5880b210a120558aaab03eb30ee5de2952a80f3376dda7e45501146`，与 1.3.0 相同；APK 签名、ZIP CRC 和 16 KiB ZIP 对齐均通过。
- 两份内置 ONNX 图及 MIT 许可与源码字节一致；826 项构建输入在构建前后保持一致，并核对提交后的 Git 内容。
- APK SHA-256：`0a7cc7e29ea48952eb9aed32351f0139bff0c4f6797cfc6982c5bc2b3c997437`。交付副本复制后再次核对哈希；GitHub 上传按服务端附件大小及 SHA-256 核对。

## 验证边界

本轮测试在电脑端 JVM / Robolectric 环境执行，未连接 Android 真机。软件渲染环境无法直接截取包含 GPU RenderNode 的书卡，碎裂用例注入同一测试书卡的真实截图，执行正式碎片绘制与删除逻辑；产品使用 GraphicsLayer 快照。实际 GPU 快照、GL 页面毛玻璃观感及连续操作帧率仍需设备验收。Android 测试源码编译通过不代表设备用例已经运行。

## 发布

发布页：[v1.3.1](https://github.com/roxycon-dev/Ciallo-Reader/releases/tag/v1.3.1)。附件为 `Ciallo-Reader-v1.3.1.apk` 和 `SHA256SUMS.txt`。本地交付副本位于 `releases/`，构建日志、测试 XML、源码快照及上传核对回执保存在 `artifacts/release-1.3.1/`；APK 与本地凭据不纳入 Git。
