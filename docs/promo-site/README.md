# Ciallo Reader 宣传官网

中文首页为 index.html，英文首页为 en/index.html。两者共用 site.css 和只负责主题偏好的 site.js。保留既有 GitHub Pages 域名和部署；旧主题偏好继续生效。不开启 JavaScript 时，阅读区、目录、FAQ 和下载均可使用。

## 本地预览与发布检查

在仓库根目录运行以下命令。

```shell
npm ci --prefix tools/promo-site
node tools/promo-site/build.mjs
node tools/promo-site/check.mjs
```

构建将 CSS 和 JS 输出为内容指纹文件，写入 artifacts/site-performance/preview/after。检查中英页面的图片、srcset、脚本和样式引用。脚本总量上限为 4 KiB。页面在 main 更新时通过 GitHub Pages 工作流部署。

## 视觉与素材

纸色、深青与衬线标题参考产品影片的版式。showcase-* 里的横版静态图来自原片的书架、找书、离线阅读、小说排版、翻页、漫画、统计、外观和备份画面。640 和 1280 像素 WebP 用于不同视口，showcase-cover.jpg 用于社交分享。听书、词典和竖排图来自 docs/readme-media 的原始模拟器截图。中英文 README 继续使用模拟器原素材。

首屏只优先加载一张主图，其余图片声明尺寸并延迟解码或加载。页面无实时画布、视频、Worker、视口级滤镜或滚动监听。FAQ 与详情采用浏览器原生控件，不需要逐帧脚本。

## 性能对比

2026-11-19 在同一台 Windows 机器的 Chromium headless 模式对比两版构建。每个版本用独立空配置，桌面视口 1440×900、DPR 1，移动视口 390×844、DPR 2。工作集是 Chromium 进程树合计，包含浏览器本身。

| 指标 | 桌面改版前 → 后 | 移动视口改版前 → 后 |
| --- | --- | --- |
| 首屏观察窗口内请求字节 | 661 → 152 KiB | 661 → 220 KiB |
| JS 堆使用量 | 1.29 → 0.75 MiB | 1.17 → 0.79 MiB |
| Chromium 工作集 | 684 → 673 MiB | 746 → 664 MiB |
| 约 1.4 秒闲置主线程任务耗时 | 144 → 1 ms | 145 → 1 ms |
| 受控滚动帧间隔 P95 | 7.1 → 7.3 ms | 7.4 → 7.4 ms |

公开脚本从 25,601 字节降到 688 字节左右。单次 headless 测量用于比较改动前后的渲染开销，实际手机的帧率、工作集和加载时延仍取决于设备与网络。
