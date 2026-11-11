# a.gray.sexBLTWbindsnake：样本核查与官方复核准备

日期：2026-10-26（Asia/Shanghai）。

后续状态：用户明确要求不提交给检测方，本目录材料仅本机保存，未对外发送。曾尝试移除应用安装能力，用户要求保留，相关代码与权限已恢复。没有交付该尝试版本；本文记录的 1.5.3 / 245 APK 未被替换。

## 当前结论

**尚未确认消除报毒。** 先前的启动修复与模拟器测试不能证明华为的风险标记已经清除。当前准备的是可核验样本和复核材料，不是查杀通过证明。

用户提供的截图将 Ciallo Reader 标记为“病毒应用”“危险等级高”，描述包含私发短信、利用成人内容诱导扣费、私自下载安装应用或窃取隐私。风险代码由用户提供为 `a.gray.sexBLTWbindsnake`；截图自身没有显示 APK 版本、哈希或引擎名称，因此不能把它直接归属于 1.5.3，也不能仅凭代码前缀断定检测引擎。

目前对当前源码和 1.5.3 / 245 正式 APK 的有限静态核查没有发现与私发短信、联系人读取、原生支付 SDK 对应的权限/API。没有检测方原始报告或具体触发路径，尚不足以认定全部风险描述都是误报，更不能承诺保留全部内容来源后一定能通过检测方的内容分类规则。

## 检查的确切样本

| 项目 | 值 |
| --- | --- |
| 应用 | Ciallo Reader |
| 包名 | `com.aistudio.novelreader.kxmpzq` |
| 版本 | 1.5.3 / 245 |
| 文件大小 | 29,202,180 字节 |
| SHA-256 | `be71f526f8e176aa1854d4be58563bf0a4ff88ecbdea223a8066e101422dcae8` |
| MD5 | `d7b8b158fa57d167e6e543652e7fada6` |
| 签名证书 SHA-256 | `d2115e3cc5880b210a120558aaab03eb30ee5de2952a80f3376dda7e45501146` |
| APK 签名 | Android SDK apksigner 验证通过，v2 |
| 调试开关 | Android SDK apkanalyzer 返回 `false` |

证书 Subject 沿用历史 `CN=Android Debug, O=Android, C=US`，正式 APK 的 debuggable 为 false。没有证据表明证书 Subject 是本次命中原因；本次没有更换固定证书、包名或 APK 内容。

## 使用现成工具核查

使用本机 Android SDK 自带 `apkanalyzer`、`aapt`、`apksigner`，以及仓库现有的更新测试；没有编写替代杀毒引擎或自行生成“安全通过”结论。

- 从实际 APK 导出的 Manifest 未声明 `SEND_SMS`、`READ_SMS`、`RECEIVE_SMS`、`READ_CONTACTS`、`READ_PHONE_STATE`。
- `apkanalyzer dex packages` 的最终 DEX 包/API 清单中，对 `SmsManager`、`ContactsContract`、`com.android.billingclient`、`com.alipay.sdk`、`com.tencent.mm.opensdk`、`com.google.android.gms.ads` 的显式检索均未命中。这个检索不覆盖所有反射、原生代码、远端行为或所有可能的 SDK 名称，不等于完整恶意软件分析。
- APK 中包含一个 `classes.dex` 和 8 个 arm64 原生库：AndroidX 图形路径、Cronet、DataStore、漫画增强 JNI/ncnn、ONNX Runtime 两库、QuickJS。没有发现内嵌 APK、EXE、BAT、SH 文件或 `.mimosa` 工具状态。文件清单保留供复核。
- 成人源仍完整保留；没有把应用表述成“不含成人来源”，也没有以换名、隐藏代码或关闭系统查杀来代替复核。

## 更新下载安装流程核查

当前确实申请了 `REQUEST_INSTALL_PACKAGES`，用途是用户主动更新本应用。具体流程是：

1. 用户在更新弹窗点击“下载并安装”后，才会启动 APK 下载。
2. 下载地址要求精确匹配项目官方 GitHub Release；跳转仅允许指定 GitHub 官方 HTTPS/CDN 域名。
3. 校验安装包大小、可用的 SHA-256 信息、包名、版本、新旧版本关系、SDK 支持范围及与当前安装一致的签名。
4. 下载完成后可自动打开系统安装界面；安装本身仍由系统要求用户确认和相应安装授权。没有发现使用 root、设备所有者、无交互 `PackageInstaller` 提交等静默安装代码。
5. 当前流程的“自动拉起安装界面”不能直接等同于“静默完成安装”。如果检测方认为此交互违反其要求，应要求提供具体触发路径，再据此整改。

复用现有回归集，本轮结果：

| 测试 | 通过数 | 失败/错误/跳过 |
| --- | --- | --- |
| AppUpdateDownloadTest | 8 | 0 / 0 / 0 |
| AppUpdateManagerTest | 6 | 0 / 0 / 0 |
| AppUpdatePackageTest | 10 | 0 / 0 / 0 |
| GithubUpdateCheckerTest | 13 | 0 / 0 / 0 |
| 合计 | **37** | **0 / 0 / 0** |

这些是功能和限制条件测试，不是华为/腾讯引擎复检。此次没有安装或下载其他模拟器，也没有为了改变文件哈希重新打包 APK。

## 网上已有处理路径

1. [腾讯云移动安全原作者的处理说明](https://cloud.tencent.com/developer/article/1133371)给出了先自查应用及第三方库、再通过 [腾讯手机管家软件误报申诉](https://m.qq.com/complaint/) 复核的路径。2026-10-26 实际访问申诉地址跳转到 `/complaint/login`，需要登录。该资料不能证明本次华为提示一定由腾讯提供。
2. [华为官方开发者问题支持说明](https://consumer.huawei.com/cn/support/content/zh-cn00445237/)提供[开发者联盟工单入口](https://developer.huawei.com/consumer/cn/support/feedback/#/)，应优先向实际显示风险的设备厂商提交原始截图，并要求确认引擎、规则和触发依据。
3. [AniCh 1.4.0 原项目发布说明](https://github.com/Sle2p/AniCh/releases/tag/1.4.0)记录过相同代码，作者同时更换包名、默认开启安全模式、对未登录用户隐藏成人内容，并明确说明新应用不继承旧应用数据。多项改动同时发生，不能用作“只改包名即可根治”的证据，也不符合本次保留成人源的约束。

## 已备材料与下一步

本机 `artifacts/risk-review-20261026/` 包含原始截图、样本身份、Manifest、权限/API 检索结果、签名验证、文件清单、37 项测试输出和待提交说明。单独的 `submission/` 子目录只放待发送的 APK、截图、说明和样本身份；不包含私钥、密码、用户书库、Cookie、阅读记录或完整项目源码。

**没有提交给华为或腾讯，用户已明确要求不提交。** 腾讯申诉页需要账号登录；此前内嵌浏览器两次连接均报 `Timed out waiting for the Browser webview to attach for this browser-use page`，没有进入表单。登录身份和提交结果不能编造。

请求检测方分别确认：

- 对本次确切 APK 的风险能否复现；命中的是行为、内容分类、签名/包名信誉还是其他规则。
- 若认为存在短信、扣费、私自安装或隐私窃取，请提供具体组件、权限、API、网络请求或复现步骤，以便有针对性整改。
- 若行为描述不符，是否能更正具体 APK 的标记，并说明复检结果和适用范围。
- 若实际原因是成人来源的内容分类，请明确分类依据；不得将内容分类直接当成已证明的私发短信或窃取隐私行为，也不能通过本报告保证检测方会接受所有来源。
