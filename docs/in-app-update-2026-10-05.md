# Ciallo Reader 应用内更新

## 用户流程

设置 → 检查更新 → 新版本确认窗口 → 下载并安装 → 下载进度 → 完整性与身份校验 → 系统安装页 → 用户确认安装。

弹窗显示版本、发布说明与已知安装包大小。下载支持取消、失败后重试；大小未知时显示不确定进度。收起窗口或切换页面后下载由应用级协程继续执行；完成后只在应用恢复到前台时请求安装。

未获安装权限时显示选择安装方式，提供允许安装、导出安装包、浏览器下载和正式发布页入口。拒绝授权后保留已下载文件，不反复自动打开授权页面。授权成功返回后继续安装。无法打开授权或安装页面时仍可导出与浏览器下载；浏览器 / 文件管理器需要具备各自的系统安装权限。

## 下载与安装校验

- 保留 GitHub API 与官方最新发布页回退，继续使用系统代理，无账号 token 或第三方镜像。
- API 提供版本、说明、附件大小与可用 SHA-256；只接受该仓库、该 tag 的 HTTPS APK 附件。优先固定发布文件名，歧义不猜测。
- API 不可用时沿用本项目 `Ciallo-Reader-v版本.apk` 发布约定。没有 API 摘要时仍校验 APK 包名、准确版本、递增 versionCode 和当前安装证书。
- 下载只跟随 GitHub 官方 HTTPS / 默认端口附件 CDN，最多 5 次跳转；文件上限 192 MiB。独立临时文件、流式写盘、大小与 EOF 校验；取消关闭 socket 并清理本次临时文件。
- 安装前检查文件大小、可用 SHA-256、有效 Android 包、包名、版本、最低系统和签名。签名冲突提示先备份再查看迁移说明，不要求用户卸载来绕过校验。
- 安装使用 `FileProvider` 内容 URI、APK MIME 与临时读取授权。Android 8+ 查询应用安装权限；Android 7 查询全局未知来源设置。权限页返回重新检查实际授权，不依赖结果码猜测。

## 持久化与清理

只使用 `filesDir/app_updates/`：UUID `.part`、`update.apk`、原子 `pending.json`。记录目标 versionCode、来源与实际 SHA-256；不会纳入书库数据备份。

重启时清理中断临时文件，重新验证已完成文件及签名后恢复「继续安装」。恢复不会主动弹安装器；系统杀死进程时未完成下载需要重试。

收到 `MY_PACKAGE_REPLACED` 后，由新版本检查实际安装 versionCode，达到保存目标后才删除应用内包和元数据；启动时再作同样清理。安装取消 / 失败不删包；用户导出副本、系统 Downloads 与书籍文件不参与清理。

## 验收记录

37 项 JVM、4 项 Compose Native、1 项真实网络共 **42 项测试通过**；androidTest 编译与 Release 构建成功。替换前实际下载 24,005,868 B 首次发布的正式 APK，SHA-256 与正式包一致，31 次进度发布。此次 API 资料不可用，生产官方回退路径实际取得安装包。

本次替换包 `Ciallo-Reader-v1.2.3.apk`（本地验收文件名为 `Ciallo-Reader-in-app-update-preview.apk`）：24,023,980 B，SHA-256 `a6aa00b874a4bca6e73169efc0fa083d52e19b75a1956c7bd2e8096cae48c361`，与正式 1.2.3 同证书。包身份、名称、arm64、16KB 对齐和 ZIP CRC 通过；构建期间无源码 / 资源文件修改。

原始测试 XML、实际网络摘要、窗口截图与本地构建信息保存在 `artifacts/update-flow-2026-10-05/`。最终数量和安装包校验结果以该目录 `verification.json` 为准。

Windows Robolectric 的 `File` 采用反斜杠，AndroidX FileProvider 内部按 Android 的 `/` 检查根目录。测试验证真实 manifest / XML 路径覆盖，并仅替代 URI 创建；生产默认仍调用原生 FileProvider。没有用户手机实装验证。

本次按用户要求同版本替换 [Ciallo Reader 1.2.3](https://github.com/roxycon-dev/Ciallo-Reader/releases/tag/v1.2.3) 正式安装包，源码与标签同步，README 保持原文。版本仍为 `1.2.3 / 204`，已安装首次发布 1.2.3 的用户需手动下载覆盖安装；此后的更高版本可使用应用内更新。发布回执与替换后的实际下载验收记录保存在上述本地目录。

系统行为参考：[应用安装授权](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())、[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)、[升级广播](https://developer.android.com/reference/android/content/Intent#ACTION_MY_PACKAGE_REPLACED)。
