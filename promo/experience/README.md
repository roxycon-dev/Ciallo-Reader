# Ciallo 阅读 / 下一页，按你的方式

## v10 声音修订 / v8 画面：给自己，一页的时间 · 41.35 秒 / 18 镜

按动画短片重新组织三幕：寻找故事、沉入阅读、故事为你留着。片头打开一本由真实界面组成的书；中段以源管理近景、跨源搜索、漫画分镜与纸页阅读交替；阅读记录和继续阅读接到片尾。不同段落采用拉远、匹配切换、短叠化、纸页停顿与玻璃掠光。详情见 CINEMA-v8.md。

继承 v7 对画面边界和运动起落的修正：原图完整等比放入，去掉过度放大与无目的飞入；书源、章节、下载重新取干净边界。配乐更换为 Lily J 的 House Vibez 连续片段，漫画转小说只轻微减弱，不切断音乐；HTML 和 MP4 共用含拟音的混音母带。

所有 App UI 均来自用户录屏整帧或自然裁切，不重绘、不模拟数据、不打码。漫画分镜采用实机内容的自然裁切；翻译只展示设置。录制中的卡顿不进入主片，包装动画不代表原生 App 帧率。

## 打开 / 录制 / 互动

- 直接打开 `promo/output/Ciallo-reading-experience.html`，图片、字体和可选声音全部内嵌，可离线播放。
- 项目预览：在此目录运行 `node server.mjs`，访问 `http://127.0.0.1:4173/?v=10-audio`。
- 默认静音自动播放，结束后停在真实下载入口；点击 ♫ 开启原创配乐与少量转场拟音。音乐最后淡出。
- 空格暂停或重播；左右方向键切镜头；进度条定位；1× / 1.25× / 1.5× 倍速。
- 按 H 隐藏控制条；`?record=1` 直接进入纯画面模式；建议全屏 1920×1080 录制。
- 鼠标进入画面时，粒子轻微让开；片尾下载链接通往软件 GitHub Releases。
- `?frame=32.8` 定格；`window.CialloPromo` 提供 seek、play、pause、duration、shots。动画由影片时间驱动。
- 页面隐藏时暂停；首次加载到后台会在第一次显示时开始。系统启用减少动态效果时初始暂停，禁用粒子和主要相机变换。

## 内容与素材

多源聚合、漫画 / 小说 / 多语言、书源管理与 JSON、搜索结果、作品详情与章节、在线阅读、漫画模式 / 方向 / 图像 / 翻译 / OCR / 自动翻页 / 手势 / 背景、小说排版与主题、书签、下载中 / 暂停 / 完成、书架分类与本地导入、主题与强调色、玻璃质感、继续阅读、阅读时长 / 日历 / 趋势，以及公益开源下载入口。

`CINEMA-v8.md` 记录当前导演决定；`native-manifest.json`、`kinetic-manifest.json`、`composition-manifest.json`、`cinema-manifest.json` 和 `ASSETS.md` 记录来源。统计录屏为 432×960，不伪造高清细节。音乐由 compose_score.py 原创合成，拟音为既有 Mixkit 资源，出处见 `AUDIO-ATTRIBUTION.md`。

## 编辑与打包

`kinetic-film.js`：时间轴、独立镜头模板、播放器与音效点。`kinetic-film.css`：镜头构图与时间驱动动画。`finish.css`：原图表面与文字。`atelier.css`：早期视觉基础。`composition.css`：自然裁切与安全区。`cinema.css`：当前电影构图、光影及运动。`python build.py` 生成离线 HTML 和源码 ZIP。修改仅限 promo，旧版文件存于 `archive-v4/` 至 `archive-v7/`。

`review-v8/` 保存关键帧、时间轴、原创乐谱事件和完整运动预览。离线 HTML 为正式可互动作品；`promo/output/Ciallo-cinematic-preview.mp4` 为 1920×1080 / 30fps 配乐短片，`Ciallo-cinematic-no-music.mp4` 为保留拟音的无配乐版本。
