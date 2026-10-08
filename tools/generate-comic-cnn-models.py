"""Reproducibly export the vendored Anime4K matrices to bounded native ONNX graphs.

No training or model download. Matrix convention and edge padding match the Kotlin
reference exactly. Requires numpy and onnx in the development environment.
"""
from pathlib import Path
import re
import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto

ROOT = Path(__file__).resolve().parents[1]
WEIGHTS = ROOT / "app/src/main/java/com/example/ui/comic/Anime4KCnnWeights.kt"
OUT = ROOT / "app/src/main/assets/comic_enhancement"


def parse(name):
    text = re.search(rf"{name}\s*=\s*floatArrayOf\((.*?)\n\s*\)", WEIGHTS.read_text(encoding="utf-8"), re.S)[1]
    values = np.array([float(v) for v in re.findall(r"(-?\d+\.\d+)f", text)], dtype=np.float32)
    cursor = 1
    layers = []
    for _ in range(int(values[0])):
        groups = int(values[cursor]); cursor += 1
        bias = values[cursor:cursor + 4]; cursor += 4
        entries = []
        for _ in range(groups):
            tex, act, x, y = map(int, values[cursor:cursor + 4]); cursor += 4
            matrix = values[cursor:cursor + 16].reshape(4, 4).T.copy(); cursor += 16
            entries.append((tex, act, x, y, matrix))
        layers.append((bias, entries))
    assert cursor == len(values)
    return layers


def export(name, filename):
    layers = parse(name)
    nodes, initializers = [], []
    initializers.append(numpy_helper.from_array(np.array([0, 0, 1, 1, 0, 0, 1, 1], dtype=np.int64), "pads"))
    previous = "rgba"
    for i, (bias, groups) in enumerate(layers):
        keys = sorted(set((g[0], g[1]) for g in groups))
        inputs = []
        for tex, act in keys:
            value = "rgba" if tex == 0 else previous
            if act == 2:
                negative = f"l{i}_neg_{tex}"
                nodes.append(helper.make_node("Neg", [value], [negative])); value = negative
            if act:
                activated = f"l{i}_act_{tex}_{act}"
                nodes.append(helper.make_node("Relu", [value], [activated])); value = activated
            inputs.append(value)
        joined = f"l{i}_joined"
        nodes.append(helper.make_node("Concat", inputs, [joined], axis=1))
        padded = f"l{i}_padded"
        nodes.append(helper.make_node("Pad", [joined, "pads"], [padded], mode="edge"))
        weights = np.zeros((4, len(keys) * 4, 3, 3), dtype=np.float32)
        for tex, act, x, y, matrix in groups:
            ch = keys.index((tex, act)) * 4
            weights[:, ch:ch + 4, y + 1, x + 1] += matrix
        wn, bn = f"l{i}_weights", f"l{i}_bias"
        initializers.extend([numpy_helper.from_array(weights, wn), numpy_helper.from_array(bias, bn)])
        previous = f"l{i}_output"
        nodes.append(helper.make_node("Conv", [padded, wn, bn], [previous], kernel_shape=[3, 3]))
    graph = helper.make_graph(nodes, name,
        [helper.make_tensor_value_info("rgba", TensorProto.FLOAT, [1, 4, "h", "w"])],
        [helper.make_tensor_value_info(previous, TensorProto.FLOAT, [1, 4, "h", "w"])], initializers)
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)], ir_version=9,
        producer_name="Ciallo Reader Anime4K matrix exporter")
    model.doc_string = "Derived from bloc97/Anime4K, MIT; see Anime4KCnnWeights.kt. Outputs convolution residuals, not final RGB."
    onnx.checker.check_model(model)
    OUT.mkdir(parents=True, exist_ok=True)
    onnx.save(model, OUT / filename)
    # Compare the exported graph to scalar mat4-style accumulation, including boundaries.
    from onnx.reference import ReferenceEvaluator
    rng = np.random.default_rng(42)
    source = rng.random((1, 4, 11, 13), dtype=np.float32); source[:, 3] = 1
    prev = source
    for bias, groups in layers:
        result = np.broadcast_to(bias[None, :, None, None], source.shape).copy()
        for tex, act, x, y, matrix in groups:
            value = source if tex == 0 else prev
            if act == 1: value = np.maximum(value, 0)
            if act == 2: value = np.maximum(-value, 0)
            padded = np.pad(value, ((0, 0), (0, 0), (1, 1), (1, 1)), mode="edge")
            value = padded[:, :, 1 + y:12 + y, 1 + x:14 + x]
            result += np.einsum("oc,nchw->nohw", matrix, value)
        prev = result
    actual = ReferenceEvaluator(model).run(None, {"rgba": source})[0]
    delta = np.max(np.abs(prev - actual))
    assert delta < 2e-5, delta
    print(f"{filename}: {(OUT / filename).stat().st_size} bytes; scalar max error {delta:.9f}")
    # Exercise the real CPU runtime as well when it is installed on the build host.
    import importlib.util
    if importlib.util.find_spec("onnxruntime"):
        import onnxruntime as ort
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        session = ort.InferenceSession(model.SerializeToString(), options, providers=["CPUExecutionProvider"])
        runtime = session.run(None, {"rgba": source})[0]
        runtime_delta = float(np.max(np.abs(prev - runtime)))
        assert runtime_delta < 2e-5, runtime_delta
        print(f"ONNX Runtime {ort.__version__} CPU: {filename} max error {runtime_delta:.9f}")


if __name__ == "__main__":
    export("RESTORE_S", "restore-s.onnx")
    export("UPSCALE_S", "upscale-s.onnx")
