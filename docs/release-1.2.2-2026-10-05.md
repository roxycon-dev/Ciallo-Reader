# 1.2.2：更新检查修复与发布验证

版本 1.2.2 / 203，发布地址 https://github.com/roxycon-dev/Ciallo-Reader/releases/tag/v1.2.2 。

## 修复

设置页原先使用独立 HttpURLConnection，未显式接入项目系统代理策略；异常统统吞掉，只显示“国内 GitHub 不稳定”。现在抽出 GithubUpdateChecker，使用共享 OkHttp 传输与逐次系统代理解析；8 / 10 / 12 秒连接 / 读取 / 单次请求预算。

先请求公开 GitHub 最新正式 Release API，失败后读取官方 /releases/latest 重定向，限定 HTTPS、同域名、同项目及合法稳定版本路径。不解析第三方镜像，不需要账号 token，不抓取大 HTML。两条路径均失败时显示 DNS、超时、HTTP、TLS 等实际原因，重试无粘住失败缓存。

使用四段数字比较版本，1.2.10 高于 1.2.9；当前 1.2.2 面对正式版 1.2.1 不会误报发现新版。新版本按钮打开具体发布页。请求取消仍传播，检查按钮通过 finally 恢复；本机版本显示使用 BuildConfig 作为回退。

本轮包含此前已验收的多语言搜索、中文简称、本地词库 / 在线缓存、滚动搜索用词卡片、Tab 动画、原生选字复制，以及既有漫画滚动进度修复。上游 1.2.1 漫画源改动保留。

## 验证与签名

单测覆盖数字版本、API 限流 / 超时后的官方回退、具体错误、恢复查询、非法版本 / 发布地址和取消。真实 HTTPS 探针通过 Android 系统代理 getter 调用生产客户端，API 与官方网页回退均成功，发布前读到最新正式版 1.2.1；摘要位于本地 artifacts/release-1.2.2/live-update-results.json。最终核心、界面、真实网络和 Release 检查的 XML / 日志 / 指纹在该交付目录，原始本地验收产物不提交 Git。

新安装包沿用本地 v5 证书 `d2115e3cc5880b210a120558aaab03eb30ee5de2952a80f3376dda7e45501146`，可覆盖此系列验收版。下载并验证 GitHub 旧 v1.2.1 包，其证书为 `5d8ad655459b11174c9a2c6988689b536adc9e57f8e385c8c5153522c4ac1ea5`，因此无法直接覆盖该旧包。遇到签名冲突，请先设置导出备份，再安装新版并恢复；未备份前不要卸载。没有将私钥或 GitHub 凭据提交仓库。

组件与网络测试在 Robolectric / 本机代理环境执行，不代表用户手机实测。UI 静态门禁仍存在此前及上游字面量超限，未重设基线。签名、版本、ABI、16KB 对齐、CRC、捆绑词库与发布后 GitHub 最新版本接口均需核对。

接口语义依据 [GitHub 官方 Release API 文档](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)。

最终专项验证：131 核心 + 47 Compose Native + 3 真实网络，共 181 项通过；设备测试编译、Release 构建成功且输入稳定。APK 24,001,884 B，SHA-256 `520605e895fc91427f847739b6ea6f52d6b405d1c95592068c96df15572ddcd0`。签名 / 身份 / arm64 / 16KB 对齐 / CRC / 捆绑词库核验通过。
