<div align="center">

<img src="./app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp" width="96" alt="Ciallo Reader 图标"/>

[简体中文](README.md) · [English](README_EN.md)

# Ciallo Reader｜Android 小说、漫画与电子书阅读器

**在线找书，本地管理，随时离线阅读。**  
把小说、漫画和个人电子书放进同一个 Android 书架。

[⬇️ 下载最新版 APK](https://github.com/roxycon-dev/Ciallo-Reader/releases/latest) ·
[查看截图](#界面预览) ·
[全部功能](#功能亮点) ·
[提交问题或建议](https://github.com/roxycon-dev/Ciallo-Reader/issues)

[![Latest release](https://img.shields.io/github/v/release/roxycon-dev/Ciallo-Reader?display_name=tag)](https://github.com/roxycon-dev/Ciallo-Reader/releases/latest)
![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
![License](https://img.shields.io/badge/license-All%20Rights%20Reserved-lightgrey)

</div>

Ciallo Reader 是一款 **Android 小说阅读器、漫画阅读器和 EPUB 电子书阅读器**。你可以导入本地书籍，也可以通过内置或自定义书源搜索内容、下载到书架，再使用可调排版、翻页和阅读进度继续阅读。

**English:** Ciallo Reader is an Android ebook, novel, and manga reader for local files and configurable online sources. Search, download, organize your library, and read offline.

## 界面预览

<table>
<tr>
<td align="center"><strong>小说阅读设置</strong><br><img src="./docs/screenshots/shot-reader125-novel-phone.jpg" width="260" alt="Android 小说阅读器的字体、行距、亮度和排版设置"/></td>
<td align="center"><strong>漫画阅读设置</strong><br><img src="./docs/screenshots/shot-reader125-comic-polish-phone.jpg" width="260" alt="Android 漫画阅读器的翻页、显示、图像和翻译设置"/></td>
</tr>
</table>

> 以上为功能界面示例，部分截图来自较早版本的 Android 模拟器；应用仍在持续更新，当前界面可能已有调整。演示正文为样例内容。

## 功能亮点

### 小说、漫画和电子书，一个书架管理

- 导入并阅读 **TXT、EPUB、MOBI、AZW、AZW3、PRC、DOCX、FB2、CBZ、ZIP、PDF** 等文件。
- 在线搜索结果可以直接阅读；支持把小说整本或漫画章节下载到本地，断点续传并在书架中管理。
- 书源搜索支持多源并发、自定义 Legado JSON 书源，以及 @css: / @json: 规则导入。
- 本地进度、章节书签、收藏和阅读统计集中管理；换源时可迁移支持的阅读记录。

### 可按习惯调整的阅读体验

- 小说支持仿真卷页、覆盖、平移、渐变和滚动等翻页方式，可调字号、字体、行距、页边距、主题，并支持 TTS 朗读。
- 漫画支持单页、双页、条漫和无缝滚动，可设置阅读方向、缩放、裁边、跨页拆分与页面背景。
- 手机、平板和横屏均有适配；提供多个阅读预设、夜间主题和低性能设备的「极简」画质选项。

### 漫画画质增强与翻译

- 内置锐化、线条增强和 JaNai 极速处理；**MangaJaNai 完整版**可在首次下载模型后离线使用。
- 漫画翻译使用本地 OCR 和气泡检测，译文由所选在线翻译服务或自定义 AI 接口生成。
- 完整版增强模型按需下载，首次使用约 **76 MB**；不需要时可继续使用轻量选项。

## 支持格式

| 格式 | 阅读方式 |
| --- | --- |
| TXT | 分段加载、章节识别与多编码适配 |
| EPUB | 目录、封面、元数据及内嵌插图 |
| MOBI、AZW、AZW3、PRC | Kindle/PDB/KF8 文件解析 |
| DOCX、FB2 | 文本提取与章节切分 |
| CBZ、ZIP | 漫画图片排序与阅读 |
| PDF | 按页显示；PDF 文本层解析尚未支持 |

## 安装

- **系统要求：** Android 7.0（API 24）及以上。
- **当前 Release：** 提供 arm64-v8a APK，适用于大多数 Android 手机和平板；Release 暂不包含 x86_64 模拟器版本。
- **获取 APK：** 前往[最新版 Release](https://github.com/roxycon-dev/Ciallo-Reader/releases/latest)下载 APK。当前版本也可[直接下载 v1.3.6](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.3.6/Ciallo-Reader-v1.3.6.apk)。
- 下载后按 Android 提示允许安装该来源的应用，再打开 APK 安装。

## 快速开始

**导入本地书：** 打开书架，点「导入新书」，选择设备中的支持格式。

**搜索在线内容：** 打开「书库」，选择小说、漫画或聚合搜索；可在「设置 → 书源管理」启用、管理或导入书源。在线书源由第三方提供，搜索结果和可用性会随来源变化。

**离线阅读：** 在在线作品页下载整本小说或漫画章节，完成后从书架打开。在线阅读和搜索需要网络。

## 常见问题

**为什么搜索结果为空？**  
检查网络和书源开关，再尝试聚合搜索或更换关键词。部分来源需要登录、验证或特定网络环境；来源站点调整也可能暂时影响搜索。

**下载后的书可以离线读吗？**  
可以。下载完成后从本地书架阅读；在线搜索、在线阅读和网络翻译仍需要连接相应服务。

**漫画翻译会把哪些内容发送到网络？**  
OCR 与气泡检测在设备上完成；生成译文时会通过你选择的在线翻译服务或自定义 API。请先查看所用服务的隐私条款。

**完整画质增强为什么要下载模型？**  
MangaJaNai 完整版模型较大，因此按需下载；下载完成后可离线使用。不需要额外模型时可使用其他处理选项。

## 开发与反馈

项目使用 **Kotlin、Jetpack Compose、Material 3、Room 和 WorkManager**。本地构建需要 JDK 17、Android SDK 35、NDK 29.0.14206865 与 CMake 3.22.1：

~~~bash
git clone https://github.com/roxycon-dev/Ciallo-Reader.git
cd Ciallo-Reader
./gradlew :app:assembleDebug
~~~

Windows 可使用 gradlew.bat :app:assembleDebug。遇到问题或想提出功能建议，请到 [GitHub Issues](https://github.com/roxycon-dev/Ciallo-Reader/issues) 附上应用版本、设备型号和复现步骤。

- [发布版本与更新记录](CHANGELOG.md)
- [v1.3.6 发布说明与验证记录](docs/release-1.3.6-2026-10-12.md)
- [第三方组件与模型许可说明](docs/vendor-licenses/README.md)

## 内容来源与许可

Ciallo Reader 是阅读与书库管理工具，在线书源和翻译服务由第三方提供，其内容、可用性及服务条款由对应提供方决定。请遵守所在地法律、来源站点规则及作品版权要求。

本仓库未附带开源许可证，项目源码与应用保留所有权利；第三方组件各自遵循其许可证，详见上方许可说明。

