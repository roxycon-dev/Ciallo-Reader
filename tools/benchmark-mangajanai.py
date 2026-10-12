"""Compare uncached full-image FP32 execution, without changing MangaJaNai weights or tiling.

python tools/benchmark-mangajanai.py MODEL.onnx REPORT_DIR [--image page.png]
Without --image this uses a clearly labelled 300x450 synthetic sample, not a normal manga page.
Requires onnxruntime, numpy and Pillow. Desktop CPU results do not establish Android latency.
"""
from pathlib import Path
import argparse
import gc
import hashlib
import json
import time
import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('model', type=Path)
parser.add_argument('directory', type=Path)
parser.add_argument('--image', type=Path)
parser.add_argument('--threads', type=int, default=4)
args = parser.parse_args()
expected = 'c985d6b78ed1a659867cd5697ce80743ad9ddd73868f463e69c824f15f169eb0'
assert hashlib.sha256(args.model.read_bytes()).hexdigest() == expected
args.directory.mkdir(parents=True, exist_ok=True)
if args.image:
    image = Image.open(args.image).convert('RGB')
else:
    image = Image.new('RGB', (300, 450), 'white')
    draw = ImageDraw.Draw(image)
    for y in range(15, 445, 11):
        draw.line((5, y, 290, y-9), fill='black', width=2)
    draw.text((100, 210), 'MangaJaNai', fill='#333333')
rgb = np.asarray(image).astype(np.float32) / 255
h, w = rgb.shape[:2]
padded = np.pad(rgb, ((16, 64), (16, 64), (0, 0)), mode='edge')
reports = []
reference = None
for name, threads, opt, arena in (
    ('1.3.2 CPU', 1, ort.GraphOptimizationLevel.ORT_DISABLE_ALL, False),
    ('optimized CPU', args.threads, ort.GraphOptimizationLevel.ORT_ENABLE_ALL, True),
):
    started = time.perf_counter()
    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.inter_op_num_threads = 1
    options.graph_optimization_level = opt
    options.enable_cpu_mem_arena = arena
    options.enable_mem_pattern = arena
    if arena:
        options.add_session_config_entry('session.intra_op.allow_spinning', '0')
        options.add_session_config_entry('session.inter_op.allow_spinning', '0')
        for dim, value in [('batch_size', 1), ('width', 80), ('height', 80)]:
            options.add_free_dimension_override_by_name(dim, value)
    session = ort.InferenceSession(str(args.model), options, providers=['CPUExecutionProvider'])
    init_seconds = time.perf_counter()-started
    output = np.empty((h*2, w*2, 3), np.float32)
    patch = np.empty((1, 3, 80, 80), np.float32)
    tile_output = np.empty((1, 3, 160, 160), np.float32)
    binding = session.io_binding()
    if arena:
        binding.bind_cpu_input('input', patch)
        binding.bind_ortvalue_output('output', ort.OrtValue.ortvalue_from_numpy(tile_output))
    tiles = 0
    for y in range(0, h, 48):
        for x in range(0, w, 48):
            patch[0] = padded[y:y+80, x:x+80].transpose(2, 0, 1)
            if arena:
                session.run_with_iobinding(binding)
                result = tile_output
            else:
                result = session.run(None, {'input': patch})[0]
            th, tw = min(48, h-y)*2, min(48, w-x)*2
            output[y*2:y*2+th, x*2:x*2+tw] = result[0, :, 32:32+th, 32:32+tw].transpose(1, 2, 0)
            tiles += 1
    seconds = time.perf_counter()-started
    assert np.isfinite(output).all()
    if reference is None:
        reference = output.copy()
    difference = np.abs(reference-output)
    report = {'backend': name, 'input': [w, h], 'tiles': tiles, 'initSeconds': init_seconds,
              'totalSeconds': seconds, 'maxError': float(difference.max()),
              'meanError': float(difference.mean()), 'within5Seconds': seconds <= 5}
    assert report['maxError'] <= 0.0015 and report['meanError'] <= 0.0001
    reports.append(report)
    print(json.dumps(report), flush=True)
    Image.fromarray(np.rint(np.clip(output, 0, 1)*255).astype(np.uint8)).save(args.directory/f'{len(reports)}.png')
    del session, binding
    gc.collect()
result = {'modelSha256': expected, 'runtime': ort.__version__, 'syntheticSample': args.image is None,
          'cpuSpeedup': reports[0]['totalSeconds']/reports[1]['totalSeconds'], 'runs': reports}
(args.directory/'benchmark.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
