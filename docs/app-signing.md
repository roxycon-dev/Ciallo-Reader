# 发布签名与密钥分发

正式版应用标识保持 `com.aistudio.novelreader.kxmpzq`，最低支持 Android 7（API 24）。

> **自 1.6.7 起，官方发布私钥随仓库公开**：`signing/release-keystore/ciallo-reader-release.p12`
> 与明文口令见 [`signing/release-keystore/README.md`](../signing/release-keystore/README.md)。
> 目的是让用户能自行编译同签名的更高 `versionCode`，直接覆盖安装而不丢书架、阅读记录与设置
> （issue #11 的请求）。下面是这份材料的含义、边界与构建方法。

## 固定发布证书

```
SHA-256 3ee5fb4018a00457eb24cbbfc43e78967d24069b760a5d386d6c9454b3770794
SHA-1   02864a02d528cef1311f375775eeac152774ed23
DN      CN=Ciallo Reader Release
别名    ciallo-reader-release
```

指纹固定写在 `signing/release-cert.sha256`。`./gradlew :app:verifyReleaseSigning` 会比对
keystore 里实际证书的 SHA-256 与该文件，不一致直接终止构建——这是防止「签了个不能覆盖安装
的包」的唯一有效门禁，不要为了绕开它去改这个文件。

v1.5.6 起的全部发布包（含最新的 `releases/v1.6.6/Ciallo-Reader-v1.6.6.apk`）都是这张证书，
彼此可覆盖升级。

## 构建同签名版本

环境变量优先，`release-signing.properties` 次之：

```powershell
$env:JAVA_HOME      = "C:\path\to\jdk17-or-21"
$env:KEYSTORE_PATH  = "signing\release-keystore\ciallo-reader-release.p12"
$env:KEY_ALIAS      = "ciallo-reader-release"
$env:STORE_PASSWORD = 'Hf7cEa1iS4R8CCRo+E4/qCD3G0rKZ426jyPdOSuEzSE='
$env:KEY_PASSWORD   = $env:STORE_PASSWORD
./gradlew.bat :app:verifyReleaseSigning :app:assembleRelease
```

口令含 `+` `/` `=`，PowerShell 用单引号、bash 用单引号或直接 export，避免被当作运算/路径解析。

`versionCode` 必须比目标设备上已装的版本大，否则系统拒绝覆盖。`versionName` 随意，但建议同步
递增以免用户困惑。

## 覆盖升级自检

```powershell
python tools/verify-apk-upgrade.py `
  --new-apk      app/build/outputs/apk/release/app-release.apk `
  --previous-apk releases/v1.6.6/Ciallo-Reader-v1.6.6.apk `
  --build-tools  "$env:ANDROID_HOME\build-tools\36.1.0"
```

四项：包名、证书指纹、`versionCode` 递增、`debuggable=false`。之后 `adb install -r` 验证
真实设备上的数据保留——静态检查通过不等于安装成功。

## 公开私钥的取舍

把发布私钥公开是刻意的产品决定，不是事故：

- **要解决的问题**：用户要自己修 bug 并覆盖安装。签名不等于包名，没有私钥就无法产出可覆盖
  的包，任何「自己改自己签名」的路线都会让用户丢数据。
- **代价**：任何人都能签出与官方同身份、可互相覆盖的 APK。这不是新增风险——签名身份本来
  就绑定在固定证书指纹上，而指纹早已公开在仓库里。
- **不变的部分**：官方性仍由 GitHub Release 与 `SHA256SUMS.txt` 体现，而不是由「只有维护者
  持有私钥」体现。要防篡改只能换包名换签名另起发行渠道，那条路线放弃覆盖升级。

如果将来要收回这条决定，正确做法是换包名 + 新证书发新应用，而不是把已公开的私钥再藏回去
（藏回去不改变任何已泄露事实，只会让用户重新丢失数据）。

## 旧密钥（已退休）

1.5.6 之前的证书是 `CN=Android Debug`，指纹
`d2115e3cc5880b210a120558aaab03eb30ee5de2952a80f3376dda7e45501146`。其私钥条目曾出现在
公开历史提交的 `debug.keystore` 中，已彻底退休；用旧密钥打包不能覆盖当前版本。当时测试过
保留旧历史的 v3 轮换链，系统可保留数据覆盖安装，但检测仍列为风险软件，因此最终没有采用。
[旧证书公开历史提交](https://github.com/roxycon-dev/Ciallo-Reader/commit/6ecd4ef2e55cf49de810ad156b45cb745f33d313)。

从旧密钥迁移到新密钥仍然**不能**覆盖安装：先导出备份并确认文件已存到应用目录外，再卸载旧版、
安装新版、导入备份。1.5.6 及以后的版本之间可以正常覆盖。

## 调试包

`assembleDebug` 使用独立包名 `com.aistudio.novelreader.kxmpzq.debug` 与名称「Ciallo Reader ·
调试版」，可与正式版并存，签名是仓库里的 `debug.keystore`。它**不能**作为正式版升级包发布。

## 历史验证

- 1.3.7（2026-10-13）：正确发布密钥通过 Gradle 校验；缺失密钥和另一份测试密钥均被拒绝；
  `assembleDebug` / `assembleRelease` 成功；正式包通过 apksigner 验签与升级检查脚本的 5 项
  回归（同签名升级、不同签名、不同包名、版本不递增、调试包）。未做真机数据保留实测。
- 1.3.8（2026-10-14）：用户确认原 1.3.7 私钥不可找回，沿用 1.3.6 密钥，版本 219，与已发布
  217 比较包名、证书一致且编号递增。未做真机实测。
- 1.5.6（2026-10-26）：切换到当前固定证书 `3ee5fb40…`，旧调试证书退休。
- 1.5.12（2026-11-05）：1.5.6 起的新签名，与官方 1.5.11 覆盖升级条件、ZIP CRC、16 KiB 对齐
  全部通过；734 个生产构建输入与冻结清单一致。
- 1.6.7（2026-11-25）：签名材料公开；`verifyReleaseSigning` 与 `verify-apk-upgrade.py`
  对 v1.6.6 → v1.6.7 通过（见 `docs/release-1.6.7-2026-11-25.md`）。

Android 的覆盖更新要求见 [官方签名说明](https://developer.android.com/studio/publish/app-signing)。
