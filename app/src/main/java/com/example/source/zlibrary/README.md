# zlibrary

路径：`app/src/main/java/com/example/source/zlibrary/`。

## 用途

Z-Library 登录、节点、反挑战和专用请求流程。

## 内容
- `network/`
- `parser/`
- `DiamWallInterceptor.kt`
- `EncryptedCookieJar.kt`
- `EndpointHealthChecker.kt`
- `README.md`
- `RemoteEndpointConfig.kt`
- `RemoteEndpointProvider.kt`
- `ZLibraryAccessChecker.kt`
- `ZLibraryCredentialStorage.kt`
- 其余 6 项按名称和同层模块组织。

## 维护提示

随 APK 打包的生产代码和资源应通过 `:app:assembleRelease` 验证；测试 fixtures 放在对应测试源集。

2026-10-19：`ZLibraryBookReference.kt` 处理镜像迁移、书籍版本唯一匹配与不可重试错误；`ZLibraryNodeSelection.kt` 负责有界探测与取消。搜索卡下载链接和来源由 `ZLibrarySource` 保留；`ZLibraryWebViewHelper.resolveDownload` 在 HTTP 空响应/验证错误时限时尝试浏览器交接，然后由 Worker 按规则切节点。

同日补充：`ZLibraryParserManager` 在布局选择前剔除 `[hidden]`、`aria-hidden=true` 和内联隐藏子树，避免把反映查询词、随机书号且下载返回 204 的隐藏书卡作为可下载结果；通用兜底使用同一份清理后的 DOM。不能用其他书的下载成功代替用户指定单本验收。
