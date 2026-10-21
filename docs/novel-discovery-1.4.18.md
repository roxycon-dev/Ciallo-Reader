# 小说发现源适配 · 1.4.18

本轮修复 1.4.17 只扫描首页文字链接造成的漏源。源站首页没有文字“排行榜”不等于没有榜单：番茄由脚本与 JSON 提供，SF 是图标链接，话本的排序在二级分类页，书阁使用查询参数。

| 源 | 推荐 | 更新 | 榜单 | 分类来源 |
| --- | --- | --- | --- | --- |
| 番茄 | `/api/rank/recommend/list` | `/api/rank/recent/update/list`，真实 `updateTime` | `/api/author/misc/top_book_list/v1/` | `/rank/1_1` 的 `rankCategoryTypeList`；保留男女频及原 ID |
| 话本 | 原站首页 | 分类下 `updatetime` | `clickcount_days_7`；新作单独 `createtime` | 首页原生频道与题材链接 |
| SF | 桌面首页公开推荐；移动首页目前软 404 | `/lateList/`，续页 `/API/HTML5.ashx?op=latest` | 人气 / 新书 / 畅销 / 月票 | `/category/` 图标父节点标签 |
| 爱下 | 原站首页 | `/new/` | `/hot/`，含日榜、月榜入口 | 原生 `/sort/{id}/` |
| 书阁 | 原站首页 | `/novels` 默认更新顺序 | `/novels?sort=heat` | `cat` / `tag` 参数原值，去掉展示计数和描述 |
| Wenku8 Library | 文库公开热度等级推荐 | 全目录 `updatedAt` | 热度等级 / 上升等级；同等级不声称精确点击名次 | `/api/categories`，逐书 `tags` |
| Novelia | 源站已关闭游客列表（HTTP 401），明确提示 | 同左，不再发送必然失败的请求 | `/api/novel/rank/syosetu`、`kakuyomu`，公开原站日榜 | 原前端流派选择器，精确传递 provider / genre / range / status |
| 全本 | 真实推荐与分类 | 原站未公开，明确提示 | 原站未公开，明确提示 | 原生 `/c/{category}.html` |

Z-Library 不加入小说推荐、更新和榜单。自定义源仍按其静态 explore / Legado 规则执行；没有规则的自定义站点不猜测未知路径。

Novelia 的列表接口 `/api/novel` 目前对游客返回 `401 游客没有权限执行此操作`，推荐与更新因此不可用；公开日榜 `/api/novel/rank/*` 仍正常返回。这一栏按其实际能力只提供榜单，选择推荐或更新时直接给出原因，不再发出必然失败的请求，也不拿日榜冒充推荐。

文库接口会忽略未知 `sort` / `tag` 参数，因此加载完整元数据后再按真实字段筛选排序；每批最多 4 个请求，只在完整性校验通过后写入 1 小时磁盘缓存。只读目录元数据，不下载 EPUB / 图片 / 正文，也不逐卡请求详情。各源查询在 IO 协程执行，发现界面逐源显示结果。

番茄分类榜的网页字段带有自定义字体编码，不能直接作为普通 Unicode 标题显示。本轮选用返回可读文字的公开作品榜；不把 PUA 字符输出到卡片，不以请求每本详情来掩盖列表解析问题。

分类遵循精确原名与明确别名映射，保留原站完整分类入口。组合题材能归入多个筛选维度，例如“科幻末世”为科幻与末世、“玄幻言情”为奇幻与恋爱；书名和简介中的关键词不参与冒充原站分类。多条原站路线一起请求并去重，保留各自分页，不能只取第一条。

公开协议依据：[番茄](https://fanqienovel.com/)、[话本](https://www.ihuaben.com/tongren/)、[SF 分类](https://m.sfacg.com/category/)、[爱下](https://ixdzs8.com/)、[书阁](https://xbookcn.app/novels)、[文库 API](https://wenku8.ywy.moe/api/categories)、[Novelia](https://n.novelia.cc/)、[全本](https://www.quanben.io/sitemap.html)。协议检查及实际 Android 请求结果随发布保留在 artifacts/paper-1.4.18/。
