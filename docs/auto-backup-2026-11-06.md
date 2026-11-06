# 自动备份与一键恢复

日期：2026-11-06　范围：`AutoBackupStore`、`AutoBackup`、启动提示、备份设置卡片

## 一、问题是怎么来的

用户的反馈是「每装一次新版本就签名冲突，装成功后书源和下载的书全没了」。拆开看是两件事：

1. **签名冲突**只在包的签名证书不同时发生。Android 不允许用不同证书覆盖安装，只能先卸载。我核对了 GitHub 上 1.5.11–1.5.14 四个正式包，证书都是 `3ee5fb40…0794`，与仓库钉住的 `signing/release-cert.sha256` 一致，也就是说 1.5.6 之后官方包之间是可以覆盖升级的。会触发卸载重装的是这些情况：手机上仍留着旧密钥（≤1.5.5）或 `probe` 包、装了第三方重签包、混装了不同来源的包，以及本次我给的调试包（包名 `.debug`，是另一个应用）。
2. **卸载即丢数据**是 Android 的规则：数据库、书源配置、`filesDir/downloads` 下的书都在 `/data/data/<包名>` 里，卸载一并删除，非 root 无法保留。

第一件事没法用代码修，只能靠发布侧保持同一把密钥（`verifyReleaseSigning` 已经在构建期拦截）。第二件事可以：**把备份写到卸载不会删的地方，并在数据变空时主动提示恢复**。

## 二、做法

备份落点是用户用系统文件选择器授权的目录（SAF），不是应用私有目录。授权用 `takePersistableUriPermission` 持久化，重启仍可写；卸载会让授权失效，这一点无法绕过，所以重装后需要重新选一次目录或直接挑备份文件。

| 文件 | 作用 |
| --- | --- |
| `data/AutoBackupStore.kt` | SAF 目录读写、备份文件命名、保留 3 份、状态（开关/上次时间/提示标记） |
| `data/AutoBackup.kt` | 编排：到期判断、备份、恢复、是否需要提示 |
| `MainViewModel.kt` | 启动阶段跑一次自动备份；书架为空且目录里有备份时抛恢复提示 |
| `MainActivity.kt` | 恢复对话框，恢复完成后重载书源 |
| `transfer/TransferScreen.kt` | 「设备互传与备份」里新增自动备份卡片：开关、选目录、立即备份、状态 |

备份文件与「保存本地备份」产出的 zip 完全一致（书、书源、收藏、阅读记录、设置、资源），恢复直接复用现有 `importArchive(REPLACE)` 流程，不新增格式、不需要新的导入器。

几个刻意的取舍：

- **只在书库非空时备份**：空书架生成的备份会把上一份可用备份顶掉。
- **间隔 12 小时**：冷启动频繁触发会白耗电，也不利于存储卡寿命。
- **保留 3 份**：够覆盖一次误操作，不会把用户目录塞满。
- **默认不导出受保护内容**：`ExportSelection()` 默认不带 `protectedAuthorized`，自动备份不会悄悄把隐私分区打包出去。
- **提示每个安装只弹一次**：`restoreOffered` 标记，避免每次冷启动打扰。

## 三、验证

```
C:\cr> .\gradlew.bat :app:testDebugUnitTest --tests "*AutoBackup*" --tests "*AliceSw*" :app:assembleDebug
BUILD SUCCESSFUL in 58s
AutoBackupNamingTest   tests=4 failures=0
AliceSwDiscoverTest    tests=7 failures=0
AliceSwDiscoveryTest   tests=2 failures=0
AliceSwParserTest      tests=7 failures=0
```

产物：`app/build/outputs/apk/debug/app-debug.apk`，52.25 MB。

未在设备上验证，须补：

- SAF 目录授权、写入、读取、删除整条链路只有编译和代码审查背书；`takePersistableUriPermission` 在部分厂商系统上会被回收，需要在真机上确认「杀进程后仍可备份」。
- 恢复流程会走 `importArchive(REPLACE)`，包括暂停下载、写回退快照；真机上要确认恢复后书源列表、书架、阅读进度都回来。
- 没有做「重装后自动找回」：卸载会清掉授权，App 无法知道上次备份目录在哪。重装后的路径是「设备互传与备份 → 打开已有备份或分享文件」，或在新机重新选一次目录。

## 四、后续可做

- 备份目录扫描：如果用户把备份放在固定的公共目录（如 `Download/Ciallo`），可加一个「从常见位置查找备份」的入口，减少重装后找不到文件的情况。
- 恢复到新机时把自动备份开关与目录一起写回，省一次选择。
