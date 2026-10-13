# Ciallo Reader 宣传官网

中文首页为 `index.html`，英文首页为 `en/index.html`，共享样式和脚本。发布结果为纯静态页面，没有网络字体或 CDN 依赖。GitHub Pages 工作流压缩本目录的 CSS / JS，为资源生成内容指纹文件名，再部署生成目录，避免手机或平板混用新旧脚本和样式。

## 本地预览

在本目录运行 `python -m http.server 4178 --bind 127.0.0.1`，打开 `http://127.0.0.1:4178/`。英文版为 `/en/`。

验证发布版本：在仓库根目录运行 `npm ci --prefix tools/promo-site`、`node tools/promo-site/build.mjs` 和 `node tools/promo-site/check.mjs`。产物位于 `artifacts/site-performance/preview/after`。构建会检查全部三个发布脚本合计小于 30 KB；校验会检查中英文资源引用、指纹与脚本语法。产物中的 `_site-version.json` 记录版本与部署提交。GitHub Pages 的旧 HTML 缓存仍可能短暂保留，刷新后新的 HTML 会引用同一版资源。

## 编辑

- `index.html` / `en/index.html`：真实功能文案、界面展示、FAQ、SEO 与许可说明。两个语言版本应同步编辑。
- `site.css`：基础排版、真实截图和内容模块。
- `experience.css`：六层玻璃材质、三层曲线显影、滚动展台与逐模块响应式样式。
- `site.js` 顶部 `CONFIG`：曲线数量、采样步数、帧率、像素比、鼠标影响半径与导航倾角。
- `curve-engine.js`：两条外边界共用采样、内曲线插值、鼠标压力与三个画布的绘制。渐变、采样权重和顶点数组复用，不改变原曲线坐标或质量参数。
- `flow-worker.js`：支持 OffscreenCanvas 时在工作线程绘制。一次只处理一帧，积压时合并输入到最新状态；不支持或启动失败时使用同一个绘制器在主线程运行。
- `tools/promo-site/`：发布压缩、资源指纹与校验工具。源文件可以直接本地预览，构建不改变源文件。

曲线构图参考了 [Mario DeMeyer 的 Flow](https://scenery.io/scenes/flow-Fx6wByMGCZP) 的线束节奏，以及 [Tyler Hobbs 的 Fidenza](https://www.tylerxhobbs.com/words/fidenza) 中对弧势、留白与调色的讨论。网站曲线使用自行设计的控制点与绘制算法。

三组丝带式曲线与三个柔光斑固定铺在全站后方。每组由两段连续的三次贝塞尔曲线构成，共用弧势和渐变色，线距在收束与展开之间变化。接合点保持切线连续；手机使用中心裁切与画幅比例限制，避免拉成长直线。清晰层在远处仍保留 68% 的显影，不随鼠标移走而消失。各组共同以约三分钟一周期缓慢呼吸。

鼠标附近以高斯权重产生向内凹陷与轻微扭转，远处的基础曲线保持稳定；移动速度影响局部压感和显影半径，离开页面后平滑回弹。重模糊、轻模糊过渡与清晰层共享同一张离屏绘图。无输入 2.5 秒后虚拟光点缓慢游走，只改变清晰度，不压缩曲线；触摸抬起或原生滚动取消触摸后显影逐渐收回。页面隐藏时暂停，滚入第二屏后整体透明度降至 70%。减少动态效果模式关闭时间演化、形变与虚拟光点，保留缓和的光标显影。

桌面宽屏使用 108 条曲线，窄桌面与平板使用 84 条，触屏手机使用 60 条。触屏渲染限制像素比、采样与模糊层分辨率；低性能设备去掉中间模糊层。曲线数量按输入方式和视口共同判断，窄桌面窗口仍保留丰富线条。

Chromium 使用圆角矩形边缘位移贴图与三通道 SVG 滤镜，实现边缘折射与色散。其他引擎及 iOS 回退为 CSS 玻璃材质。滤镜按可见区域异步生成，重复尺寸共用。玻璃还包含光标方向的边缘高光、体积光、分层投影和表面光斑。

折射贴图利用圆角矩形对称性生成，像素结果与原算法一致。边缘光只计算进入视野的玻璃元素，读取尺寸后统一写入样式；章节、导航和进度仅在滚动或布局变化时更新。曲线局部显影在支持复合遮罩的浏览器中使用固定径向图加位置变化，其余浏览器保留完整径向渐变。页面隐藏时停止请求绘制和光斑动画，恢复时继续。

深色主题的内容玻璃增加墨色底，并降低浅色卡片的底色强度，保留折射和曲线，同时避免彩色光线盖过说明文字。

阅读展台使用原生页面滚动联动三幕，支持点击标签直接跳幕与左右方向键、Home / End。点击画面注释或「探索这一幕」打开真实截图详情；Escape 关闭并归还焦点。小高度视口及减少动态效果模式使用普通标签浏览。手机菜单支持 Escape。关闭 JavaScript 时，三组阅读体验完整显示，FAQ 仍可使用。

导航、内容与展台共用 760 px 手机断点。长屏手机采用上下布局，短屏采用左右布局；截图保持原比例，并根据实际容器尺寸给倾斜动画留出空间。展台顶部与底部预留安全区，英文长文案和窄屏标签允许自然换行。平板的搜索卡片调整图片位置，避免覆盖说明文字。上述调整保留完整内容、原有玻璃和交互。

格式胶囊包含相邻磁吸放大；三张核心玻璃卡片支持鼠标倾斜与图标浮起；翻译流程的连线在进入视野后绘制；FAQ 保留原生 details 并增强展开与收起动画。全站不依赖 CDN，JS 小于 30 KB。

## 图片来源

图片均来自仓库中现有实机截图、录屏提取帧与应用图标，保持原界面和内容，仅压缩或调整展示尺寸。原始文件保留在原目录。截图中的作品与阅读数字仅作界面展示。

| 页面图片 | 原始文件 |
| --- | --- |
| `product-shelf.webp` | `promo/experience/assets/shelf.webp` |
| `product-comic.webp` | `promo/experience/assets/comic-native.webp` |
| `product-search.webp` | `promo/experience/assets/native-search.webp` |
| `product-stats.webp` | `promo/experience/assets/native-stats.webp` |
| `product-theme-green.webp` | `promo/experience/assets/native-theme-green.webp` |
| `product-theme-orange.webp` | `promo/experience/assets/native-theme-orange.webp` |
| `product-ranking.webp` | `promo/readme-screenshots/11-god-chapter-ranking.jpg` |
| `feature-novel-layout.jpg` | `docs/screenshots/shot-reader125-novel-phone.jpg` |
| `feature-resume.webp` | `promo/experience/assets/native-resume.webp` |
| `feature-download-paused.webp` | `promo/experience/assets/native-download-paused.webp` |

不要把真实漫画截图标注成增强前后对比；当前页面没有展示经过验证的对比图。翻译文案明确区分设备端识别和在线译文生成。
