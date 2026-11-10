# Ciallo Reader 1.6.0

## UI 与交互重构

- 统一书架、书库、发现、统计、设置和阅读器的版式与玻璃面板。保留 Ciallo 的主题配色、自定义卡片参数，以及书架和收藏的 3D 视图。
- 页面切换采用连续斜切，标题、同组文字、图标与按钮按层级浮现和轻回弹。快速切回上一板块时，沿当前动画反向返回，保留滚动位置。
- 重做搜索展开、弹层开合和按钮按压反馈。恢复漫画详情的模糊大封面，修复关键词搜索残影、半窗底部裁切及两类阅读器三点菜单的浅色描边。

## 平板适配与漫画预加载

- 平板上书库和发现内容居中，发现网格随可用宽度调整列数，弹层也按宽屏尺寸限制宽度。
- 在「漫画阅读器 → 设置 → 图像 → 图片预加载」可选自动或自定义 **0–12 张**。横向翻页和纵向滚动共用设置，拆开的双页按一张原图计算。

## 鸣谢

特别感谢 [mikufoxxx 与 Wenku8 Flutter 的贡献者](https://github.com/mikufoxxx/Wenku8_Flutter) 开源分享 UI、人机交互和动效实现。档案式排版、玻璃浮层、连续转场及图标反馈为 Ciallo Reader 这次 UI 重构提供了参考和灵感。 部分动效实现从 Dart 适配到 Kotlin／Compose。[上游源码版本](https://github.com/mikufoxxx/Wenku8_Flutter/tree/ebcd7f1891b54a92fc045c372974d70ec829f10a) · [许可声明](https://github.com/mikufoxxx/Wenku8_Flutter/blob/ebcd7f1891b54a92fc045c372974d70ec829f10a/NOTICE)

感谢 [qianyunli114514](https://github.com/qianyunli114514) 在 [Issue #7](https://github.com/roxycon-dev/Ciallo-Reader/issues/7) 提出平板适配和漫画预加载数量自定义的建议。

## 安装与校验

- 版本号 **1.6.0**，版本码 **259**。APK 适用于 Android 7.0+ 的 ARM64 设备，沿用 1.5.16 的发布证书。
- 安装包文件名为 `Ciallo-Reader-v1.6.0.apk`。校验值见 [SHA256SUMS](https://github.com/roxycon-dev/Ciallo-Reader/blob/main/releases/SHA256SUMS-v1.6.0.txt)。
