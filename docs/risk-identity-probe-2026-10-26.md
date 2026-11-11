# 包身份对照测试（1.5.3 / 245）

## 状态与用户要求

成人源保持默认启用，不删除、不增加首次启用门槛。安装权限、应用内下载更新、安装器调用与下载后在前台拉起系统安装界面的原有流程均已恢复。

此前尝试移除安装能力的 1.5.4 / 246 没有交付，相关修改已撤回。没有向华为、腾讯或其他第三方上传 APK、截图或申诉材料，用户明确要求不提交。

**报毒尚未确认消除。** 此包用于取得进一步对照证据，不是华为查杀通过证明。

## 对照包

- 应用名称：`Ciallo Reader · 对照测试`
- 包名：`com.roxycon.ciallo.reader.probe`
- 版本：1.5.3 / 245
- APK：`artifacts/risk-identity-probe-1.5.3/Ciallo-Reader-1.5.3-identity-probe.apk`
- SHA-256：`0274e10dceb4d1057ed95b5c9040095f79d114f1c0cfacc6188f81ac1ca7d0ed`
- 签名证书 SHA-256：`d2115e3cc5880b210a120558aaab03eb30ee5de2952a80f3376dda7e45501146`

Gradle 通过显式参数 `-PidentityProbe=true` 选择此包身份；普通构建仍使用原包名 `com.aistudio.novelreader.kxmpzq`。仅选择构建身份与区分用名称，运行时功能没有增加检测方或审核人员专用分支。

## 核对结果

- 原成人源 `showAdultSources` getter 仍固定为 `true`，源清单和资产未修改。
- 安装器与更新流程文件的差异已撤回；新 APK Manifest 仍包含 `REQUEST_INSTALL_PACKAGES`。
- 原版与对照包的系统权限集合一致。AndroidX 自定义动态广播权限的包名前缀按新身份改变。
- 比对原版 1.5.3 APK 中 53 个 `assets/`、`lib/` 条目，没有增删文件。只有 `assets/dexopt/baseline.prof`、`assets/dexopt/baseline.profm` 因编译代码标识变化而不同；全部源脚本及原生库字节一致。
- `apksigner` 验签通过，证书与原版固定指纹一致。
- `assembleRelease -PidentityProbe=true --offline --no-daemon --no-watch-fs --max-workers=2` 成功。
- 只使用已有 `codex_paper_1413` AVD（Android 15 / API 35，x86_64/ARM 转译），没有下载或创建其他模拟器。对照包安装成功，冷启动返回 `Status: ok`（约 7129 ms）；后续进程存活，检查 crash 缓冲区为空。
- 模拟器中原版包与对照包同时存在。原版 1.5.3 交付 APK 哈希仍为 `be71f526f8e176aa1854d4be58563bf0a4ff88ecbdea223a8066e101422dcae8`，未被替换。

## 使用范围

这是独立测试应用，不能覆盖原版，也不会自动带入原书架、账号和阅读记录。原版及其数据保留。对照包的包名与正式更新通道不同，正式采用新包名之前还需规划数据迁移及对应的后续发布包；请勿把它当作已完成迁移的日常正式版。

请在发生报毒的同一台手机上，对照记录原版和此包的检测结果，可分别记录安装后、首次打开并加载来源后的结果。这里不要求关闭查杀或解除系统防护。

- 若原版报毒而新身份暂未报毒，说明有包身份、样本、检测时机或本地状态相关因素值得继续核查；不能据此保证成人内容永远不会触发分类。
- 若两包均报同一代码，不能仅用这一结果确认成人源是唯一原因，签名、共同代码、内容和其他规则仍可能相关。

[AniCh 1.4.0 原项目发布记录](https://github.com/Sle2p/AniCh/releases/tag/1.4.0)曾同时更换身份和默认内容模式处理同码反馈。此处只借用透明的身份对照思路，继续遵守本次成人源默认启用、安装功能保留的要求，不声称复现了该项目的查杀结果。
