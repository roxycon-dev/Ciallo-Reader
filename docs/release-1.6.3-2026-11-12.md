# Ciallo Reader 1.6.3 · 2026-11-12

版本 **1.6.3 / 263**。

这次主要是两件事：把英文界面里剩下的中文清干净，以及把仓库里误传的生成物删掉。

## 英文界面

以下位置过去在英文模式下仍是中文，现在全部翻译：

- **统计页**：阅读时长会跟着语言走（`3 分钟` → `3 min`、`1小时20分钟` → `1h 20m`）；本周/周期阅读天数与连续天数下面的「N 天」；吉祥物的「本周只读了 …」「Roxy 捧着魔法书等你翻开第一页～」；本周点亮的星期名；神回空态「还没有神回，读完一话向后滑试试」；周阅读图表的周一到周日与「周一 至 周日」。
- **设置 → 神回**：「神回排行榜展示风格」「查看神回排行榜 ✦」，以及三种排行榜风格的名字与说明（领奖台 / 唱片架 / 照片墙）、排序方式（评分 / 最近添加 / 书籍）、内容类型（漫画 / 小说）。
- **发现 → 分类**：入口根分类、每个分组的标题、「全部…」、分类项、「· N 本」、「应用 N 项」「查看全部」「找你想看的…」，以及分类窗口里的 125 个题材词（热血冒险 / 恋爱关系 / 异世界 / 条漫 / 韩漫 / 限制级 …）全部入表。
- **发现 → 漫画 / 小说两个分区**：分区名改成读取时翻译。
- **发现 → 结果行**：`results` 右侧的「来源 N / M」。
- **书库 → 书源管理**：标题「书源管理」、导入菜单（从本地文件导入 / 粘贴 JSON 配置 / 网络导入书源）、确定导入、开始导入、说明文案、登录与注册失败提示、分组名（小说 / 漫画 / 其他 / 自定义）、调试日志与 Venera 源条目。
- **书架搜索栏**：点开后的「全部 / 书名 / 作者」分段标签。
- **漫画阅读器**：「第 N 页」页码描述、「正在加载 · N%」「已接收 N KB」「准备 OCR 模型 · N%」「高清图加载中…」「MangaJaNai 处理中」。

## 仓库清理

删掉误传进仓库的生成物与过期实验页：

- `promo/output/Ciallo-reading-experience.html`（13 MB 生成页）及其同目录的封面、音频、markdown；
- `promo/experience/archive-v1/3/4/5/6/7` 与 `archive-audio-v8`（旧版实验目录，含各自的 js/css/mp3/build 脚本）。

保留：`docs/promo-site/`（GitHub Pages 落地页）、`promo/styleframes/`、`promo/experience/`，以及测试用的 HTML 夹具（`app/src/test/resources/xbookcn/*.html`、`app/src/androidTest/assets/pufei-layout/*.html`）。

## 验证

- `:app:compileDebugKotlin` 通过；`tr()` 调用点 **1500+**、词表 **3000+** 条、**缺失 key 0**。
- 签名沿用正式密钥 `CN=Ciallo Reader Release`（SHA-256 `3ee5fb40…0794`），`versionCode 263 > 262 > 261 > 260`，可直接覆盖 1.6.1 / 1.6.2 安装。

[ARM64 APK](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.6.3/Ciallo-Reader-v1.6.3.apk)
