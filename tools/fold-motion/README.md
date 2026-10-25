# 动效优化验证

本工具仅用于本地验证，不打入正式 APK。固定原始提交：`4307f0b6fd1edd1276f52e58c1c50dc64b8be54e`。

## 软件渲染对照与回归

在工程根目录运行：

```powershell
$env:JAVA_HOME = 'D:\android studio\jbr'
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$env:TEMP/ciallo-no-unix-socket"
python tools/verify-fold-motion.py prepare --baseline-ref 4307f0b6fd1edd1276f52e58c1c50dc64b8be54e
.\gradlew.bat :app:testDebugUnitTest :liquidglass-core:testDebugUnitTest -I artifacts/fold-motion/tests.gradle -PincludeX86 --offline --console=plain
```

`prepare` 从原始提交提取比较实现，在被 Git 忽略的 `artifacts/fold-motion/parity-src/` 中生成冻结对照，不覆盖产品源码。`parity/` 的测试通过临时 Gradle init script 加入测试源集，普通产品构建不依赖这些副本。

覆盖四种按钮按压/释放关键帧、开关切换与反转、576 个包含真实触摸及物理推进的滑条帧、10,000 次弹层弹簧轨迹，以及分页保留、偏移定位和其它既有交互回归。阴影竖向模糊测试独立覆盖随机掩码、极小尺寸、超大半径及有效缓冲区之外的哨兵。

## 硬件渲染 A/B

先保留同包名、同版本、同调试签名、同构建参数的基线与候选 APK 为 `artifacts/fold-motion/baseline.apk` 和 `optimized.apk`。两者都使用 `:app:assembleDebug -PincludeX86`。基线需从上述原始提交单独构建。

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PincludeX86 --offline --console=plain
# 使用自己的测试模拟器 serial；不要安装到无关设备。
adb -s emulator-5580 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python tools/verify-fold-motion.py device --serial emulator-5580 --pairs 10 --scenario mixed
```

同一个测试 APK 交替驱动两个产品 APK，每组交替先后顺序。`mixed` 覆盖四种按钮、12 个开关和滑条；`primary` 单独覆盖玻璃主按钮。保存原始 FrameMetrics、进程 ART 分配量、首次加载/首轮交互、前后截图与汇总。首次加载耗时包含测试驱动，不能当作正式应用启动耗时；模拟器帧时间不能外推三星真机帧率。

测试 AVD 为 API 35、7.6 英寸 Fold-in with outer display、420 dpi，WHPX + `-gpu host`。已有 AVD 保持原样。无窗口模式下，设备姿态会变化，但系统镜像不会自行裁切显示区域；折叠回归因此同时设置姿态和配置中真实的 884×2208 / 1768×2208 显示尺寸。测试结束恢复尺寸和姿态覆盖。

`FoldMotionDeviceTest` 另有玻璃实时背景、同帧 DOWN/UP 释放、往返折叠、窗口缩放、正文非空及输入状态保留检查。相关设备回归应在安装候选 APK 后单独运行，性能采样期间不并行编译或录制 trace。

## 性能采样边界

CPU 局部基准分别执行十组成对样本，原始结果写入 `shadow-cpu.json`、`parity/slider-cpu.json`。它们证明局部算法成本，不等于整页帧率；Windows JVM 线程 CPU 时钟还有量化误差。每次修改后同时检查像素对照与交互，不以单次计时判断收益。

`trace.pbtxt` 用于独立诊断采样，保存 sched、HWUI、GC 和 FrameTimeline。trace 开销不能混入正式 A/B 数字。具体运行结果、未通过的原有场景及交付身份见 `docs/fold-motion-performance-2026-10-25.md`。

## 无测试框架的 CPU/GPU 对照

`MotionResourceProbeActivity` 存放于 Debug 源集，正式 Release 不包含它。原始与候选 checkout 使用相同采样源码。`benchmark.gradle` 只在显式传入 init script 时创建 `motionBenchmark`：复制发布配置、开启 R8 和资源压缩、关闭 debuggable、开放 shell profiling、依赖库选择 release，改用测试签名和 `.debug` 包名隔离真实应用。正常 Debug / Release 配置不变。这遵循 [Android 官方性能构建建议](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)。

```powershell
$motionManifest = (Resolve-Path tools/fold-motion/benchmark-manifest.xml).Path
.\gradlew.bat :app:assembleMotionBenchmark -I tools/fold-motion/benchmark.gradle "-PmotionBenchmarkManifest=$motionManifest" -PincludeX86 --offline --no-build-cache --max-workers=3
```

分别保留原始和候选的 `app/build/outputs/apk/motionBenchmark/app-motionBenchmark.apk` 为 `resource-baseline-release.apk` / `resource-optimized-release.apk`。原始生产源码保持基线提交。两包使用同一调试证书和 `-PincludeX86`，采样脚本拒绝 debuggable 或包名不符的发布采样包。页面固定四种按钮、12 个开关及滑条，通过真实 MotionEvent 触发动作，不载入 Espresso 或 Compose-test。

```powershell
python tools/fold-motion/resource-probe.py --serial emulator-5580 --pairs 10 --restart-every 3
python tools/fold-motion/resource-probe.py --serial emulator-5580 --pairs 10 --quality MAX --restart-every 3
# 单主按钮，以及与连续滚动并行的补充场景
python tools/fold-motion/resource-probe.py --serial emulator-5580 --pairs 3 --scene primary --restart-every 3
python tools/fold-motion/resource-probe.py --serial emulator-5580 --pairs 3 --scene scroll --restart-every 3
# 独立诊断；不计入以上性能样本
python tools/fold-motion/resource-probe.py --serial emulator-5580 --trace
python tools/fold-motion/analyze-resource-trace.py
```

每次安装后对两包都执行 `cmd package compile -m speed -f`，先预热全部控件并等待收敛，再测量固定 4.3 秒窗口。独立记录全进程 CPU（Process 毫秒时钟）、主线程 CPU（纳秒线程时钟）、RenderThread CPU（/proc ticks）、FrameMetrics 帧/GPU 时间及 ART 分配，并记录根绘制内容重录次数。另记录从 Activity 创建起的首轮 UI 序列，包含第一次绘制和控件使用，不称作完整应用冷启动。GPU 时间和超时比例不等于 GPU 利用率或功耗。数据按 `resource-probe/release/{high|max}/{mixed|primary|scroll}/` 保存；每轮两段序列都须触发 12 次按钮回调、产生足够 GPU 帧，且 system_server 不得重启，否则拒绝该轮。4 核、4 GiB 受控环境关闭网络及测试镜像的 GMS 后台服务；这只影响自建测试 AVD。`--restart-every 3` 只允许重启本轮专属 AVD，避免长期重复安装导致模拟器状态漂移。汇总同时保存成对变化及确定种子 bootstrap 区间，小样本区间仅用于判断趋势。

trace 必须同时含应用 sched 数据和 `motion_cpu_gpu_measured` 标记。分析程序只截取该热区间；缺失任一信息立即失败，不拿空 trace 当作原始性能。嵌套 slice 的包含时间不能相加作为 CPU。

减少 shader 空槽位的实验已撤回，交付保留原 AGSL。实验的 54 组像素结果不用于证明最终性能收益。`SliderLayerParityDeviceTest` 比较 identity RenderNode 边界下深浅色、单/双滑块的 64 个真实触摸帧；动态背景与三种降级路径由 `FoldMotionDeviceTest` 覆盖。

`kernel/ShadowKernelProbeActivity.kt` 是显式加入发布式采样构建的局部 CPU 基准，不属于正常产品源集。使用 `-PmotionKernelProbe=<本目录的 kernel 绝对路径>` 和 `-PmotionBenchmarkManifest=<kernel-manifest.xml 绝对路径>` 编译，启动 `com.example.ui.perf.ShadowKernelProbeActivity`；结果写入应用外部目录 `motion-resource/shadow-kernel.json`。先检查逐位一致，再执行 11 种尺寸、每种十组成对样本。两算法均在同一个非调试 R8 / AOT speed 进程中计时。

交付前可用 `package-evidence.py --snapshot` 冻结实际 Release 输入，构建本地 APK 后运行 `package-evidence.py` 检查源文件未变化、固定发布签名、版本、ABI、ZIP CRC、16 KiB 对齐与非调试标记。清单排除 Debug、测试及编辑器临时文件。
