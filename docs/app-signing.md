# 发布签名与旧密钥迁移

正式版应用标识保持 `com.aistudio.novelreader.kxmpzq`，最低支持 Android 7（API 24）。1.5.6 完全退休暴露的旧发布密钥，使用新的固定发布证书，不携带旧证书轮换链。**首次从旧密钥版本迁移不能覆盖安装：先导出备份，确认备份文件已保存到应用目录外，再卸载旧版、安装新版并导入。后续相同新密钥版本可正常覆盖升级。**

旧证书为 `d2115e3cc5880b210a120558aaab03eb30ee5de2952a80f3376dda7e45501146`。同证书的私钥条目曾存在于公开历史提交的 `debug.keystore` 中，不能继续把它视为仅开发者持有的发布材料。这里记录的是材料暴露，不声称发现恶意签署或证明这就是报毒根因。[公开历史提交](https://github.com/roxycon-dev/Ciallo-Reader/commit/6ecd4ef2e55cf49de810ad156b45cb745f33d313)。

新证书 SHA-256：`3ee5fb4018a00457eb24cbbfc43e78967d24069b760a5d386d6c9454b3770794`。仓库只保存公开证书和固定指纹；新私钥和密码不得入库。后续版本必须沿用新密钥并递增 `versionCode`。

测试过保留旧历史的 v3 轮换方案：系统可以保留数据覆盖安装，但腾讯检测仍把它列为风险软件。因此最终发布不使用这条链，不继续信任旧证书的签名权限、共享 UID 或回退能力；测试用轮换材料不作为发布输入。对照检测及限制见 [安全修复记录](security-remediation-2026-10-26.md)。

1.5.5 的内置更新器会拒绝不同签名的 APK，这是正常安全校验；不能为迁移关闭此校验。第一次迁移请从官方 Release 下载完整安装包，按备份流程处理。不同包名的 probe 版本同样需导出、导入备份。卸载会删除旧应用的私有数据，不能跳过备份确认。

导出时检查所需类别；受保护内容需主动解锁并加入。书源登录凭据、API 密钥及本机隐私 PIN 不随便携备份迁移，导入后按需重新配置。具体操作见 [1.5.6 迁移步骤](release-1.5.6-2026-10-26.md)。

## 本地构建

使用 `release-signing.properties.example` 创建本地配置，或设置 `KEYSTORE_PATH`、`STORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 环境变量（环境变量优先）。密钥必须与新的固定指纹一致。旧本地配置不会自动获得新密钥，不能绕过证书门禁。

本机新私钥独立存放在工作树外，密码使用当前 Windows 用户的 DPAPI 保护。可使用下面的辅助脚本；两个路径参数均指向私有文件，不应放进仓库。DPAPI 文件只能由所属 Windows 账号解密，跨机器恢复还需由维护者安全备份密钥和密码。

```powershell
./tools/build-secure-release.ps1 -SigningKeyPath PRIVATE_KEY.p12 -ProtectedPasswordPath PRIVATE_PASSWORD.dpapi
```

```shell
./gradlew :app:verifyReleaseSigning :app:assembleRelease
```

缺少配置、密钥不匹配或密码错误会使发布构建失败。使用新密钥生成 v2/v3 签名，不生成旧密钥回退；发布后必须使用官方 apksigner 对最终 APK 验签。`signing/release-cert.sha256` 的固定指纹门禁仍生效。

日常 `assembleDebug` 使用独立包名 `com.aistudio.novelreader.kxmpzq.debug`，名称为「Ciallo Reader · 调试版」，可与正式版并存；其设置和数据独立，不能作为正式版升级包发布。

## 发布检查

首次 1.5.6 迁移先验证包名、新固定证书和关闭调试：

```shell
python tools/verify-apk-upgrade.py --new-apk app/build/outputs/apk/release/app-release.apk --build-tools PATH_TO_ANDROID_SDK/build-tools/36.1.0
```

给这个命令提供旧密钥的 1.5.5 作为 `--previous-apk` 应明确失败，不能把这次迁移记为覆盖升级通过。1.5.6 之后沿用新密钥的发布，应递增版本编号并提供上一版新密钥 APK 作为 `--previous-apk`。除静态检查外，还需实际验证更新安装和数据保留。

自动发布环境必须从私有 Secrets 恢复同一份新密钥并提供上述四个环境变量，然后执行完整构建及相应检查。公开指纹不提供签名能力。以下是历史记录，不应作为继续使用旧密钥的构建指引。

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
