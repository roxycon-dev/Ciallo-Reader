# 官方发布签名密钥（完整、可直接使用）

本目录包含 Ciallo Reader 的**正式发布签名私钥**，随仓库公开。目的只有一个：让任何人
都能离线编译出**与原版同签名**的更高 `versionCode` APK，从而在不卸载、不丢书架/阅读记录
/设置的前提下覆盖安装。

对应 issue：<https://github.com/roxycon-dev/Ciallo-Reader/issues/11>

## 文件

| 文件 | 说明 |
| --- | --- |
| `ciallo-reader-release.p12` | PKCS#12 私钥库，别名 `ciallo-reader-release`，3072 位 RSA，`SHA-384 with RSA`，有效期至 2054-03-13 |
| `SHA256SUMS.txt` | 上述文件的校验和，便于确认下载完整 |

KDF/加密：PKCS#12（`storetype=PKCS12`，JDK 17+ 默认算法族），库口令与私钥口令相同。

## 口令

```
Hf7cEa1iS4R8CCRo+E4/qCD3G0rKZ426jyPdOSuEzSE=
```

（含 `+`、`/`、`=` 三个符号，shell 里必须用单引号或环境变量传，别让它们被解释掉。）

## 证书指纹（必须一致）

```
SHA-256 3ee5fb4018a00457eb24cbbfc43e78967d24069b760a5d386d6c9454b3770794
SHA-1   02864a02d528cef1311f375775eeac152774ed23
DN      CN=Ciallo Reader Release
```

固定指纹同时记录在 `signing/release-cert.sha256`，Gradle 的 `verifyReleaseSigning`
任务会用它拦下任何不一致的密钥——**换签名就不能覆盖安装**，所以这一步不能跳过。

v1.6.6 及更早的同签名发布包（`releases/v1.6.6/Ciallo-Reader-v1.6.6.apk`）用的是同一张
证书，可以直接覆盖升级。

## 编译同签名的更高版本

### 方式 A：环境变量（推荐，不留配置文件）

```powershell
$env:JAVA_HOME          = "C:\path\to\jdk17-or-21"
$env:KEYSTORE_PATH      = "C:\path\to\ciallo-reader-release.p12"
$env:KEY_ALIAS          = "ciallo-reader-release"
$env:STORE_PASSWORD     = 'Hf7cEa1iS4R8CCRo+E4/qCD3G0rKZ426jyPdOSuEzSE='
$env:KEY_PASSWORD       = $env:STORE_PASSWORD

# 1) 先把版本号抬到比已安装版本更高
#    app/build.gradle.kts -> defaultConfig { versionCode = 268 ; versionName = "1.6.7" }

# 2) 校验密钥 → 构建 → 验签
./gradlew.bat :app:verifyReleaseSigning :app:assembleRelease
& "$env:ANDROID_HOME\build-tools\36.1.0\apksigner.bat" verify --print-certs `
    app/build/outputs/apk/release/app-release.apk
```

### 方式 B：`release-signing.properties`

```properties
storeFile=signing/release-keystore/ciallo-reader-release.p12
storePassword=Hf7cEa1iS4R8CCRo+E4/qCD3G0rKZ426jyPdOSuEzSE=
keyAlias=ciallo-reader-release
keyPassword=Hf7cEa1iS4R8CCRo+E4/qCD3G0rKZ426jyPdOSuEzSE=
```

环境变量优先于该文件。`release-signing.properties` 本身仍被 `.gitignore` 忽略，
避免把口令散落到更多地方；密钥库本身已入库，无需再复制。

### 方式 C：`tools/build-secure-release.ps1`

原脚本接收的是 DPAPI 保护的密码文件，那是单机方案。现在口令已公开，直接走 A 或 B 即可；
若仍要用该脚本，把 `-ProtectedPasswordPath` 指向你自己生成的 DPAPI 文件，或者改用 A。

## 验签与覆盖升级自检

```powershell
python tools/verify-apk-upgrade.py `
  --new-apk        app/build/outputs/apk/release/app-release.apk `
  --previous-apk   releases/v1.6.6/Ciallo-Reader-v1.6.6.apk `
  --build-tools    "$env:ANDROID_HOME\build-tools\36.1.0"
```

检查项：包名仍是 `com.aistudio.novelreader.kxmpzq`、证书 SHA-256 等于固定指纹、
`versionCode` 严格递增、`debuggable=false`。四项全过才是能覆盖安装的升级包。

装到设备上验证数据保留：

```powershell
adb install -r app/build/outputs/apk/release/app-release.apk
```

`-r` 会保留应用私有数据（书架、阅读进度、设置都在里面）。

## 为什么公开私钥

issue #11 明确要求「修复后用原签名发布，能直接覆盖 v1.6.6」。原始密钥只存在于维护者
单机（`D:\Ciallo-Private-Signing`，口令受 DPAPI 保护），用户拿不到；不公开就不可能让
用户自行编译同签名版本。发布身份本来就绑定在固定证书指纹上，公开私钥不会让既有安装包
变成别人的——它只是把「谁能签这个包名」从一个人扩到所有人。

因此本项目从 1.6.7 起把签名材料视为**公开构建输入**：

- 同签名的分叉包可以和官方包互相覆盖，这是刻意的取舍；
- 官方渠道（GitHub Release + `SHA256SUMS.txt`）仍是判断「哪个包是官方发布的」的依据；
- 如果你需要防篡改的独立签名，自行换 `applicationId` 重新签名即可，但那无法覆盖官方包。

## 旧密钥（已废弃，勿用）

1.5.6 之前用的是 `CN=Android Debug` 调试证书（指纹
`d2115e3cc5880b210a120558aaab03eb30ee5de2952a80f3376dda7e45501146`），其私钥曾出现在
公开历史提交里，已彻底退休。用旧密钥打包不能覆盖当前版本。详见
[`docs/app-signing.md`](../../docs/app-signing.md)。
