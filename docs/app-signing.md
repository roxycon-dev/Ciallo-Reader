# 固定签名与覆盖升级

正式版应用标识固定为 `com.aistudio.novelreader.kxmpzq`。此轮开始固定发布证书，公开指纹保存在 `signing/release-cert.sha256`。每次发布必须沿用同一份私钥并递增 `versionCode`，才可覆盖上一版并保留应用数据。

此轮按用户要求保证今后的版本连续升级，不保证覆盖历史 1.2.6 或其他证书签署的版本。Android 不允许用不相同的签名直接接管旧应用数据。

## 本地构建

本机已将现有密钥固定保存到 `.signing/release.keystore`，配置位于 `release-signing.properties`。两者均不入库；密钥密码已重新设置，证书及私钥身份未改变。必须将密钥和密码保存在私有备份中，换机器时恢复原文件，不能重新生成密钥或改指纹。

其他开发者从 `release-signing.properties.example` 复制配置，填写同一份发布密钥的路径、密码及别名。也可设置 `KEYSTORE_PATH`、`STORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 环境变量，环境变量优先。

```shell
./gradlew :app:verifyReleaseSigning :app:assembleRelease
```

缺少配置、密钥不匹配或密码错误会使发布构建失败，正式版不再回退到临时调试密钥。环境变量指定的密钥路径直接参与签名，不再依赖仓库根的 `my-upload-key.jks` 是否存在。

日常 `assembleDebug` 使用独立包名 `com.aistudio.novelreader.kxmpzq.debug`，名称为「Ciallo Reader · 调试版」，可与正式版并存；其设置和数据独立，不能作为正式版升级包发布。

## 发布检查

修改 `app/build.gradle.kts` 中的版本名及版本编号；发布前用上一版正式 APK 检查：

```shell
python tools/verify-apk-upgrade.py --new-apk app/build/outputs/apk/release/app-release.apk --previous-apk PATH_TO_PREVIOUS_RELEASE.apk --build-tools PATH_TO_ANDROID_SDK/build-tools/36.0.0
```

脚本验证 APK 签名有效、固定证书、正式包名、关闭调试，以及新旧证书一致且版本编号递增。首次采用此固定签名没有兼容旧证书的承诺，可先省略 `--previous-apk` 验证正式包；从下一次发布开始必须提供上次 APK。

自动发布环境也必须从私有 Secrets 恢复同一份密钥并提供上述四个环境变量，然后执行构建及覆盖检查。不能只推送公开指纹就认为 CI 已获得签名能力；私钥不随源码分发。本轮 v1.3.7 使用本机固定密钥构建，公开仓库不上传密钥或密码。

## 本轮验证（2026-10-13）

- 正确发布密钥通过 Gradle 校验；缺失密钥和另一份测试密钥均按预期被拒绝。
- `assembleDebug` 和 `assembleRelease` 均成功；实际调试包已确认使用 `.debug` 包名和独立应用名。
- 正式 APK 通过 apksigner 验签及升级检查脚本，证书与固定指纹一致，正式包名正确，未开启调试。
- 升级检查脚本的 5 项回归测试通过，涵盖同签名升级、不同签名、不同包名、版本不递增、调试包及缺失信息。
- 从 GitHub 获取的 v1.3.6 使用另一份历史证书；与本轮 APK 比较时被正确拒绝，没有声称历史包可覆盖。未进行手机安装或数据保留实测。

Android 的覆盖更新要求参见 [官方签名说明](https://developer.android.com/studio/publish/app-signing)。
