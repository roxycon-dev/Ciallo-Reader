# Ciallo Reader 1.4.4 · 2026-10-18

修复设备互传停留在连接阶段的问题，并统一设置入口和传输页面的毛玻璃样式。

## 界面

- 设置中的「设备互传与备份」改为与相邻设置项一致的整行卡片：图标、标题和箭头，点击整张卡片进入，移除「打开设备互传」按钮。
- 传输页面进入主导航，沿用全局背景与主题；移除独立窗口的不透明底色。
- 与书源管理共用 `GlassKitHost`、`GlassKitCard`、`GlassTopBarStrip` 和 `GlassManagementTopBar`，使用相同连续曲率圆角、模糊、透镜与高光。接收开关使用已有 `AppSwitch`，确认、PIN 与历史窗口使用已有 `AcrylicDialog`。

![Android 模拟器上的互传毛玻璃页面](screenshots/shot-transfer144-glass.jpg)

截图为 Android 模拟器上的实际 Compose 渲染，使用测试背景，不代表真实双机传输。

## 连接修复

- Android 原生测试复现了 TLS 握手失败：Keystore 返回 `INCOMPATIBLE_PADDING_MODE`。补齐原生 TLS 使用的无摘要签名和 RSA 填充授权，并固定 TLS 只能选用当前设备身份，避免误选旧证书或应用内其他密钥。仍保留双向 TLS、证书校验和双方核对码确认。
- 使用新版本的 Keystore 身份，保留原密钥，不更换 APK 的发布签名。**两端均需更新；旧传输任务请取消后重新发起。**
- 先绑定真实 TCP Socket 到发现服务所在网络，再叠加 TLS，避免默认移动网络／VPN 路由和 SSL Socket 封装影响局域网连接。保留全部发现地址，优先 IPv4，再尝试其他地址。
- 监听成功后才公布实际端口；端口变化会更新广播。Wi-Fi Direct 独立保存直连端口，客户端读取组主设备的端口。
- 初次拨号不再反复等待同一失效地址；限制连接与握手的等待时间，分别显示当前阶段，取消操作同时关闭正在连接或握手的 Socket。传输中断后的续传策略继续保留。

Android 对 TLS 密钥的摘要和填充授权要求见 [官方 KeyGenParameterSpec.Builder 文档](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder)。NSD 的网络与多地址信息见 [官方 NsdServiceInfo 文档](https://developer.android.com/reference/android/net/nsd/NsdServiceInfo)。

## 验证与限制

- 11 项选定 JVM／Robolectric／Compose 测试通过：静默设备握手超时、取消握手、路由合并、分块协议、权限策略、页面状态和设置入口交互。
- Android 模拟器上 3 项仪器测试通过：原生 Keystore 授权、两组独立身份的双向 TLS 握手及超过 1MiB 数据实际收发、双方核对码一致、毛玻璃页面渲染。
- 原生 TLS 测试先复现失败，再验证修复成功；该结果不等同于双机无线链路验收。
- 主应用调试包在模拟器上完成「设置整行入口 → 传输页 → 系统返回设置」交互检查，使用极简画质；高画质毛玻璃由独立 Android 页面测试验证。UI gate 的既有字体指标超限未增加。
- Release 构建与固定发布签名校验通过。Android 7.0+，arm64-v8a，版本 1.4.4 / 227。
- APK 大小 29,067,363 B；SHA256：`39bb5c6e56b0d0df434f2aae51203324f966e278a51c96f4e0cf07721a566d79`。
- 尚未连接两台物理手机；不同品牌、同 Wi-Fi／热点、Wi-Fi Direct、第三方分享和真机覆盖安装仍需验证。

[下载 APK](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.4.4/Ciallo-Reader-v1.4.4.apk) · [SHA256SUMS](https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.4.4/SHA256SUMS.txt) · [互传说明](device-transfer.md)
