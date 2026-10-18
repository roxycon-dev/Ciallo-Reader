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
- `verify-xipi-source.cjs` — 用归档公开响应校验嬉皮图片列表与章节编号解析
- `verify-latest-comic-discovery.cjs` — 校验两源动态路由经生产发现桥传递分类、状态、排序及分页

漫画源实网探针：`probe-latest-comics.py`、`probe-hero-expanded.py`、`audit-hero-xipi.py`。原始响应与日志写入被 Git 忽略的 `.source-verification/latest-comics/`，不进入应用。探针依赖 `requests`、`beautifulsoup4`，图片审计另需 `Pillow`。

运行模型验证：`python tools/verify-mangajanai.py MODEL.onnx OUTPUT_DIRECTORY`，依赖 `numpy`、`Pillow`、`onnxruntime`。

- `benchmark-mangajanai.py`：对比原 CPU 与优化后 CPU 的完整输入图耗时、输出误差；不把小块、磁盘命中或桌面数据当作手机整页 5 秒结果。`python tools/benchmark-mangajanai.py MODEL.onnx OUTPUT_DIR --image PAGE.png`（依赖 onnxruntime、numpy、Pillow）。
