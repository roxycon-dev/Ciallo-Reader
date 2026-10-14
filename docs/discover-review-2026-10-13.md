# 发现审查返工 · 2026-10-13

已按“脚本校验 → 回归 → Android 35 模拟器 → 复查返工”执行。未修改版本或发布，生产没有注入测试书源/账号。

## 主要修复

| 审查问题 | 修复后的行为 |
| --- | --- |
| Venera 协议只识别旧首页结构 | 接入分页/多区块/mixed/游标/排行榜游标/动态分类/optionLoader |
| 缺少统计的源发现页空白 | 独立原站推荐与分类入口；四维统计仍独立，不伪造计数 |
| 今日筛选排除无日期作品，误解为无作品 | 最新保留无日期，今日/近三天/本周严格有效日期，提示数据缺失 |
| Action/Fantasy 被误标为战斗/魔法 | 宽泛标签映射父类，繁简与原站常用词补齐；未知词保留原站目录 |
| 作者/角色/IP 标签污染分类 | 隔离元数据命名空间，卡片优先展示故事标签 |
| 分类区叠加/大目录难找 | 纵向托盘；原站分类可分组与搜索，不丢未映射分类 |
| 下一页改变旧作品推荐分数 | 稳定源内名次分数，相同指标同分，大站流量和页长不压制小站 |
| 游标分类每页重复首页 | 标记宿主自动生成的 search.load，按真实 loadNext 游标加载 |
| 重复页导致无止境请求 | 连续两页无新作或重复游标即停止，原页重试保留游标 |
| 数字/日期解析截断或接受残缺值 | 不接受小数/溢出统计；日期必须完整，支持 ISO 高精度时间 |
| 随机按钮空时无响应/背景丢失 | 空时禁用，主题 Surface 保持可读背景 |

## 验证

- 36 份配置全部有可接入浏览入口（索引含不同配置重复 key）。113 配置分派 + 19 边界契约 = 132 项脚本检查通过，固定响应验证，不等同在线站点成功。
- 52 项 JVM/Compose/既有源回归通过，无失败或错误。
- 7 项模拟器检查通过：390dp、追漫/布局、130% 深色榜单/菜单、原站分类、三个本地源真实 QuickJS/HTML、搜索分类游标。
- 按当前生产分类器输出逐源分类矩阵。静态目录标签并非全库作品；0 不证明分类不适用，动态目录也不能以静态 0 判定失效。
- 回执与 XML/日志：`../artifacts/discover-review/verification.json`；最终截图：`../artifacts/discover-review/screenshots/final/`。

## 逐源原站分类映射

“未映射”包含年份、更新日、状态、作品 IP、语言及站点特殊目录；均保留原站入口，不强行当作题材。成人源仍按既有设置默认隐藏。

| 配置 key | 静态分类 | 可映射标准分类 | 保留原站分类 | 动态目录 |
| --- | ---: | ---: | ---: | --- |
| copy_manga | 62 | 44 | 18 | — |
| copy_manga | 62 | 44 | 18 | — |
| Komiic | 38 | 37 | 1 | — |
| baozi | 26 | 24 | 2 | — |
| picacg | 40 | 7 | 33 | — |
| nhentai | 723 | 10 | 713 | — |
| wnacg | 19 | 2 | 17 | — |
| ehentai | 0 | 0 | 0 | — |
| jm | 66 | 10 | 56 | — |
| manga_dex | 0 | 0 | 0 | 有 |
| ikmmh | 49 | 40 | 9 | — |
| shonen_jump_plus | 0 | 0 | 0 | — |
| hitomi | 8 | 1 | 7 | — |
| comick | 79 | 60 | 19 | — |
| ykmh | 175 | 114 | 61 | — |
| zaimanhua | 55 | 35 | 20 | — |
| ManHuaGui | 39 | 37 | 2 | — |
| manwaba | 24 | 17 | 7 | — |
| lanraragi | 0 | 0 | 0 | 有 |
| komga | 0 | 0 | 0 | 有 |
| comic_walker | 0 | 0 | 0 | — |
| mh1234 | 343 | 105 | 238 | — |
| ccc | 1 | 0 | 1 | — |
| goda | 37 | 31 | 6 | — |
| mh18 | 36 | 7 | 29 | — |
| mxs | 27 | 3 | 24 | — |
| manhuaren | 28 | 26 | 2 | — |
| hcomic | 29 | 1 | 28 | — |
| jcomic | 37 | 7 | 30 | — |
| hot_manga | 75 | 48 | 27 | — |
| kavita | 0 | 0 | 0 | 有 |
| happy | 140 | 81 | 59 | — |
| mycomic | 72 | 46 | 26 | — |
| bilimanga | 0 | 0 | 0 | — |
| vomic | 37 | 36 | 1 | — |
| pufei | 0 | 0 | 0 | — |

## 边界与依据

全部推荐协议能接入，不能保证站点当时在线、账号已登录或防护已通过。原站未提供的热议/点击/具体窗口仍显示能力缺失；不用标题或作者猜作品题材。这里没有作“业界第一”的无依据比较。

协议依据：[Venera source 文档](https://github.com/venera-app/venera/blob/master/doc/comic_source.md)、[实际分页实现](https://github.com/venera-app/venera/blob/master/lib/components/loading.dart)、[书源配置](https://github.com/venera-app/venera-configs)、[Keiyoushi BiliManga](https://github.com/keiyoushi/extensions-source/tree/main/src/zh/bilimanga)。mixed 文档与实现页码矛盾，按实际 1-based 调用实现，显式 pageBase:0 兼容文档写法。

## 完整主页面诊断补充

界面专项/原生 JS 的 7 项设备用例已通过。SwiftShader 下打开实际 MainActivity 时，Windows Application Error 记录 qemu-system-x86_64-headless.exe 的 0xc0000005 宿主崩溃（20:40:40 / 20:53:09），整台模拟器断连；不能当作 Android App 已报错，也不能记为完整入口通过。切换 host GPU/关闭 Vulkan 后未完成启动；swangle 只读实例能保持系统在线，但实际 MainActivity 启动超时，隔离实例切 MID 后仍然超时。完整主界面验收未通过，未更改生产默认画质；不能用 7 项模块专项替代完整入口验收。原始记录见 verification.json 和 main-integration-logcat.log。
