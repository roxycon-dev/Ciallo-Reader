# Ciallo Reader 1.3.6

1.3.6 / 217，Android 7.0+、arm64。恢复独立 **MangaJaNai 完整版**，保留 1.3.5 的 **JaNai 极速**，两个选项及缓存独立。完整版使用原 V1 黑白漫画 ESRGAN 1200p / 70k；原模型、网络规模和神经网络输入分辨率均保留。

## 画质和速度

- 原 ONNX 67,191,396 B，SHA-256 `c985d6b78ed1a659867cd5697ce80743ad9ddd73868f463e69c824f15f169eb0`。通过 pnnx 20260526 转换；351 个卷积层的权重及偏置全部逐字节核对一致。支持的 Conv、LeakyReLU、Concat、残差加乘、最近邻 Resize 保持相同数学操作；不支持的 pixel-unshuffle 在 JNI 中按 `[C,dy,dx,H/2,W/2]` 精确实现。[转换工具](../tools/prepare-mangajanai-full.py)、[来源与许可](vendor-licenses/MangaJaNai/README.md)。
- ZIP 无损压缩仅减少下载体积，提速来自 [ncnn Vulkan](https://github.com/Tencent/ncnn/releases/tag/20260526)、较大分块、固定缓冲和设备管线缓存。FP16、BF16、INT8 存储/计算全部关闭。FP16 试验在真实漫画块上最大误差约 0.25–0.29（8-bit 最大偏差 64–75），不符合质量要求，未采用。
- 每设备/系统/模型首次比较真实首块与密集 RGB 测试块的 CPU / GPU 结果，使用将实际运行的 160 或 272 边长。GPU 输出须全部有限，最大误差 ≤0.0015、平均 ≤0.0001，速度至少快 15%；失败回到原 ONNX CPU。CPU 权重关闭后才加载 GPU，避免两个重引擎同时常驻。
- GPU 核心分块 128 或 240，边缘 16；按 Java 堆预算选择大小。CPU 回退维持 48 核心 / 16 边缘。更大的分块减少重复边缘运算和调度；边界上下文变化及浮点舍入会改变个别像素，不能宣称旧分块输出逐像素完全一致。神经网络处理每个原始解码像素，未采用极速版的低分辨率残差融合。最终显示输出仍沿用 400 万像素 / 3200 长边上限。
- 一个精确图块缓存，所有原始像素包括透明度相同才复用。强度与透明度在合成阶段保持；非法输出不写入成品缓存。当前页优先、后续三页串行生成、晋升正在生成的下一页复用同一次任务，无损磁盘缓存和最高 64 MiB 窗口均接入完整版。
- 两档共享锁、只常驻一种 JaNai 引擎，切档立即释放另一种；空闲 60 秒、退出阅读、内存压力释放。GPU 图块/暂存分配器先清空，再归还设备缓存池，避免退出后继续保留大块显存。取消检查按块进行，CPU 可中止 RunOptions，下载可取消 OkHttp call；GPU 单次原生块返回后检查中断。
- 完整模型首次下载约 76 MB（ONNX 和 ncnn 两格式无损压缩，安装后约 128 MiB 私有磁盘空间），不塞入 APK。下载逐文件核对长度和 SHA-256，禁止路径穿越、未知/重复条目及超限展开。下载包失败时可使用旧已验证 ONNX 或原始源 CPU 模型，并在后续重试 GPU 包。旧私有完整权重不再被轻量档删除。

## 完整页验证

桌面 RTX 4060 Laptop GPU、官方 ncnn Python 1.0.20260526、ONNX Runtime CPU 4 线程。整个输入页保持本次测试尺寸；每块实际推理，禁止成品/图块结果缓存。GPU计时含输入布局转换、全部推理和整页拼接，排除 PNG 写盘、原图解码、Android Canvas 最终显示缩放和手机后端择优。冷引擎列加上引擎载入时间；精度校验单独进行，不能视作 Android 首页总耗时。

| 输入页 | 路径 | 整页耗时 |
| --- | --- | ---: |
| 637×900 | 旧版 48 核心 FP32 CPU，全块推理＋载入 | 37.762 s |
| 637×900 | 新版 240 核心 FP32 GPU，含引擎载入 | 2.706 s |
| 637×900 | 新版 FP32 GPU，热引擎 | 1.659 s |
| 1275×1800 | 新版 FP32 GPU，含引擎载入 | 7.378 s |
| 1275×1800 | 新版 FP32 GPU，热引擎 | 6.827 s |

小页 GPU 12 个块，大页 48 个块。旧版 CPU 数字没有整页合成开销，比较仍有利于旧版。六个真实漫画块的同尺寸 CPU/GPU 对照，最大 FP32 误差 ≤0.000137、平均 ≤0.00000152；转换为 8-bit 后最大相差 1 个灰阶。目视检查完整页中文字、网点和分块边界，未见错位。

测试素材来自《小辣椒与萝卜头》第28集《盛宴》，David Revoy 创作、Ran Zhuang 中文翻译，[原作与署名](https://www.peppercarrot.com/cn/webcomic-sources/ep28_The-Festivities.html)、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)。测试按比例缩放为两个尺寸，没有用户的《别当欧尼酱了》测试图。

没有连接荣耀 200 Pro 或其他 Android 真机。桌面大页仍超过 5 秒，**不声称手机 5 秒达标**。设备完整版测试 `MangaJaNaiFullPerformanceDeviceTest` 分别报告冷/热完整页和 `within5Seconds`，首轮包含首次下载/校验/择优；轻量版另有独立测试，二者成绩不混用。

复现：

```text
python tools/prepare-mangajanai-full.py ORIGINAL.onnx PNNX_EXECUTABLE OUTPUT_DIR
python tools/benchmark-mangajanai-full.py OUTPUT_DIR REPORT_DIR --input LICENSED_PAGE.jpg --long-edge 900 --legacy-cpu
python tools/benchmark-mangajanai-full.py OUTPUT_DIR REPORT_DIR --input LICENSED_PAGE.jpg --long-edge 1800
```

## 发布检查

55 个套件、381 项 JVM / Robolectric 回归通过，错误和跳过均为 0；Android 真机测试编译通过但未运行。初轮有 5 项旧回归未涵盖新增完整版的离线测试排除/预算预期，修正后整组选定测试重新通过。新增回归覆盖两档配置/缓存隔离、GPU 严格误差门槛、大图块原像素和边缘、损坏/恶意模型包、前向三页顺序、当前页晋升复用、极简档不生成。


Release / R8 / 资源精简、签名、ZIP CRC、16 KiB ZIP 对齐均通过；新增两份原生库的 ELF LOAD 段也验证 16 KiB 对齐。883 个构建输入在构建前后及发布提交核对；模型和许可逐字节检查。沿用 1.3.5 签名，可覆盖安装。

- APK：28,810,492 B，SHA-256 `f7d372d996e57b114fd3798587ea70e07af4ac707c5b6d53474e6d0a9a2a3ec6`。
- 完整模型包：78,931,856 B，SHA-256 `520e09760525353465d1f26194cb10315ac6ec213de81ccf9bb2e55f3184f513`。
