# 1.5.3 / 245：启动稳定性与权限收敛

## 反馈与结论边界

收到的反馈是 HarmonyOS 3.0 打开即退出、风险名称 `a.gray.sexBLTWbindsnake`。反馈方未提供具体机型、完整系统构建号、APK 哈希或崩溃堆栈，版本按 1.5.2 / 244 排查。开发者自己的两台设备同样报风险但能够启动，因此目前不能把两种现象认定为同一原因。

这次修正了能从代码确认的启动风险，并生成可覆盖安装的验证包；没有取得华为查杀引擎的复检结果，也没有在 HarmonyOS 3.0 真机重现或证实解决原反馈。不得把此记录当作“已通过杀毒检测”或“所有鸿蒙机型已修复”的证明。

## 修改

- JS 书源登录状态直接读取现有 `js_source_data`。此前为了读取这个布尔值会创建 QuickJS 原生引擎并执行书源初始化；现在只有实际调用书源功能才初始化引擎。
- 默认书籍清理、分类初始化、书库维护、阅读连续天数计算、内置标题库与书源初始化隔离非致命异常，保留协程取消语义。一个可选任务失败不再通过未捕获协程异常直接结束应用。异常会留下本地诊断；不删除数据库或重置用户书库。
- 启动临时分享文件清理与系统朗读引擎初始化回调增加异常处理。
- 读取书源登录状态时增加失败保护，并避免旧书源的异步检查覆盖新书源状态。
- Wi-Fi Direct 发现、连接、查询与广播处理系统动态撤回权限产生的 `SecurityException`。
- 删除未使用的 `READ_EXTERNAL_STORAGE` 和 `READ_MEDIA_IMAGES`。书籍、图片选择继续使用系统选择器的 URI 授权。
- 新增应用级 Java/Kotlin 未捕获异常记录，继续交给 Android 原有处理器结束进程；没有吞掉致命异常继续运行。设置 → 关于提供“导出启动诊断”。记录仅包含设备/应用版本、错误类型和有限堆栈，不记录异常消息、账号、书籍或阅读内容，不自动上传。崩溃记录与最近启动任务错误分别保存，避免下次启动覆盖崩溃依据。
- 发现页未使用约束信息的容器由 `BoxWithConstraints` 改为 `Box`，消除完整 Lint 的现有错误。

成人源清单、源脚本与固定开启逻辑均保留。包名、固定签名证书未更换；没有通过改名、隐藏内容、加壳或关闭系统查杀来处理风险提示。

## 验证

只使用已有 `codex_paper_1413` AVD（Android 15 / API 35，x86_64，支持 ARM 转译），没有下载、创建或安装其他模拟器/系统镜像。该环境不是 HarmonyOS 3.0。

- `StartupDiagnosticsTest`：4 项通过，覆盖任务失败隔离、原生链接错误、取消传播、致命内存错误继续抛出、诊断去敏和容量限制。
- `ReleaseCompatibilityTest`：3 项通过，使用本机已缓存的 Robolectric API 28，覆盖旧 API 的玻璃弹窗清理及状态/按键回归。这是本地单元测试，不是另一台模拟器，也不代表鸿蒙真机验收。
- `AdultSourceDefaultsTest`：3 项通过，覆盖新装开启、旧关闭值不能隐藏源和设置页没有六次点击门槛。
- `StartupSourceDeviceTest`：现有 AVD 上 3 项通过，覆盖登录检查不读运行时资产/不执行脚本、成人源保留、最终合并权限不含上述图库读取权限。
- `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease`、`lintRelease` 成功；完整 Lint 为 **0 错误、121 条警告**。警告仍包括既有的可选不安全 TLS 自定义源和需要 JavaScript 的源站 WebView，不能把 Lint 通过等同于独立安全审计或查杀通过。
- 新版正式 APK 覆盖安装成功；两次冷启动 `am start -W` 均返回 `Status: ok`，耗时约 1414 ms、877 ms。首次启动后检查 crash 缓冲区为空；欢迎页可显示。现有 AVD 在后续交互阶段出现过 ADB/系统服务无响应，旧版测试阶段也出现过类似环境问题，因此不把这次冒烟测试表述为长时间稳定性或完整交互验收。
- `apksigner verify --verbose --print-certs` 通过 APK v2 验签；证书 SHA-256 与仓库固定指纹相同。
- `aapt dump badging` 确认版本 245 / 1.5.3、minSdk 24、targetSdk 35、ABI `arm64-v8a`。

正式签名证书 SHA-256：

```text
d2115e3cc5880b210a120558aaab03eb30ee5de2952a80f3376dda7e45501146
```

交付目录：`artifacts/startup-fix-1.5.3-245/`。其中 APK、`mapping.txt` 与 `SHA256SUMS.txt` 对应同一次构建，mapping 用于后续还原正式包堆栈。

APK SHA-256：

```text
be71f526f8e176aa1854d4be58563bf0a4ff88ecbdea223a8066e101422dcae8
```

本机 Gradle 的 Java NIO 回环连接存在环境问题，构建沿用本机已有的 `DiscoverTcpSelectorProvider` 辅助类；它只用于宿主 JVM，不进入 APK。相关命令以 `tools/startup-repair-tests.gradle` 选择回归集，原始输出在本机 `.startup-verify.log`、`.startup-regression.log`、`.startup-device-tests.log`。

## 仍需反馈机验证

在原反馈设备覆盖安装同一哈希的 APK，保留书库，确认是否仍提示原风险代码、是否仍在启动时退出。若应用能再次打开，使用设置 → 关于 → 导出启动诊断；若系统直接阻止运行或发生原生崩溃，Java/Kotlin 记录可能为空，需要设备侧系统日志。对应版本的 mapping 随包保留。

关于报毒，华为的[应用检测说明](https://consumer.huawei.com/cn/support/content/zh-cn15897865/)列出了多种风险预警原因，[应用风险管控说明](https://consumer.huawei.com/cn/support/content/zh-cn15935640/)提供官方支持入口；这些公开页面没有解释本次具体签名。应由检测方依据上述 APK、哈希、包名、签名与完整提示复核具体命中原因。本次没有向第三方上传 APK，也没有代发申诉。
