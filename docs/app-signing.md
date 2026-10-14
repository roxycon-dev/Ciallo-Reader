# 固定签名与覆盖升级

正式版应用标识固定为 `com.aistudio.novelreader.kxmpzq`。1.3.8 按用户最终指定的原方式构建，恢复使用已发布 1.3.6 的现有密钥；公开证书指纹为 `d2115e3cc5880b210a120558aaab03eb30ee5de2952a80f3376dda7e45501146`，保存在 `signing/release-cert.sha256`。没有重新生成密钥。后续发布需沿用该私钥并递增 `versionCode`。

1.3.8 与 1.3.6 签名相同，可按包名、证书和递增编号验证覆盖升级。现有 1.3.7 使用另一证书，不能直接覆盖；从 1.3.7 迁移需先备份数据。历史其他证书签署的版本同样不保证兼容。

## 本地构建

本机已将现有密钥固定保存到 `.signing/release.keystore`，配置位于 `release-signing.properties`。两者均不入库；使用现有原密钥，证书及私钥身份未改变。必须将密钥和密码保存在私有备份中，换机器时恢复原文件，不能重新生成密钥或改指纹。

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

脚本验证 APK 签名有效、固定证书、正式包名、关闭调试，以及新旧证书一致且版本编号递增。1.3.8 使用已发布 1.3.6 的 APK 作为兼容基线，不把不同签名的 1.3.7 当作可覆盖基线。后续同签名发布应提供上一次正式 APK。

自动发布环境也必须从私有 Secrets 恢复同一份密钥并提供上述四个环境变量，然后执行构建及覆盖检查。不能只推送公开指纹就认为 CI 已获得签名能力；私钥不随源码分发。1.3.8 使用本机保存的原密钥构建，公开仓库不上传密钥或密码。1.3.7 的历史签名记录保留在下方。

## 历史验证：1.3.7（2026-10-13）

- 正确发布密钥通过 Gradle 校验；缺失密钥和另一份测试密钥均按预期被拒绝。
- `assembleDebug` 和 `assembleRelease` 均成功；实际调试包已确认使用 `.debug` 包名和独立应用名。
- 正式 APK 通过 apksigner 验签及升级检查脚本，证书与固定指纹一致，正式包名正确，未开启调试。
- 升级检查脚本的 5 项回归测试通过，涵盖同签名升级、不同签名、不同包名、版本不递增、调试包及缺失信息。
- 从 GitHub 获取的 v1.3.6 使用另一份历史证书；与本轮 APK 比较时被正确拒绝，没有声称历史包可覆盖。未进行手机安装或数据保留实测。

Android 的覆盖更新要求参见 [官方签名说明](https://developer.android.com/studio/publish/app-signing)。

## 1.3.8 签名选择（2026-10-14）

用户确认原 1.3.7 私钥无法找到后，要求按原方式打包；因此沿用已有 1.3.6 密钥，并保留正式构建的缺失/错误密钥拦截与独立调试包名。现有 1.3.7 Release、标签和附件保持不变，不用新包覆盖旧附件。

1.3.8 正式构建和验签通过，版本编号 219；与 GitHub 已发布 1.3.6 的 APK（217）比较，包名、证书一致且编号递增，覆盖升级检查通过。新 APK SHA256 `e3e16473285d6393c4372f7f6f6cdb247e4120165d03070a5dc33dc8a46ab7d6`。未进行手机覆盖安装或数据保留实测。
