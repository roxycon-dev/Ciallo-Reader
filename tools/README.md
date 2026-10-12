# tools

路径：`tools/`。

## 用途

代码库维护与本地验证辅助脚本，不进入 Android 运行时。

## 内容
- `check-comic-metadata.cjs`
- `prepare-comic-network-probe.py`
- `README.md`
- `ui-gate-baseline.json`
- `ui-gate.ps1`
- `verify-mangajanai.py` — 固定权重校验与桌面 CPU 分块推理验证；不是 Android 性能测试
- `verify-comic-source-host.cjs`

运行模型验证：`python tools/verify-mangajanai.py MODEL.onnx OUTPUT_DIRECTORY`，依赖 `numpy`、`Pillow`、`onnxruntime`。

- `benchmark-mangajanai.py`：对比原 CPU 与优化后 CPU 的完整输入图耗时、输出误差；不把小块、磁盘命中或桌面数据当作手机整页 5 秒结果。`python tools/benchmark-mangajanai.py MODEL.onnx OUTPUT_DIR --image PAGE.png`（依赖 onnxruntime、numpy、Pillow）。
