"""Desktop whole-page benchmark of the mobile SPAN/residual pipeline, NOT phone results.

Uses every tile (no image or tile cache), includes CPU session preparation on the cold page,
decode, input scaling, inference, tile composition and full-detail residual composition.
PNG writing is outside the timer. Pillow's resize differs from Android Canvas filtering.
Requires numpy, Pillow and onnxruntime. Input image must be licensed for benchmark use.
"""
from pathlib import Path
import argparse
import hashlib
import json
import platform
import time
import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw, ImageOps

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('model', type=Path)
parser.add_argument('directory', type=Path)
parser.add_argument('--input', action='append', type=Path, default=[])
args = parser.parse_args()
args.directory.mkdir(parents=True, exist_ok=True)
TILE, HALO, SIDE = 128, 24, 176
session = None
tile_ns = None
output_data = np.empty((1, 3, SIDE * 2, SIDE * 2), dtype=np.float32)
input_data = np.empty((1, 3, SIDE, SIDE), dtype=np.float32)

def native_run():
    binding = session.io_binding()
    binding.bind_cpu_input('input', input_data)
    binding.bind_output('output', 'cpu', 0, np.float32, output_data.shape, output_data.ctypes.data)
    session.run_with_iobinding(binding)

def input_size(w, h, budget):
    scale = min(1, 1400 / max(w, h), (900000 / (w * h)) ** .5)
    iw, ih = max(1, int(w * scale + .5)), max(1, int(h * scale + .5))
    while ((iw + TILE - 1) // TILE) * ((ih + TILE - 1) // TILE) > budget:
        iw, ih = max(1, int(iw * .93)), max(1, int(ih * .93))
    return iw, ih

def enhance(image, cold):
    global session, tile_ns
    start = time.perf_counter_ns()
    # Benchmark source is a complete page limited to 1800 long edge, before processing.
    rgb = image.convert('RGB')
    src = ImageOps.contain(rgb, (1800, 1800), Image.Resampling.BILINEAR) if max(rgb.size) > 1800 else rgb
    source = np.asarray(src)
    initial = np.pad(source, ((HALO, SIDE), (HALO, SIDE), (0, 0)), mode='edge')[:SIDE, :SIDE]
    input_data[0] = initial.transpose(2, 0, 1).astype(np.float32) / 255
    if session is None:
        options = ort.SessionOptions()
        options.intra_op_num_threads = min(4, __import__('os').cpu_count())
        options.inter_op_num_threads = 1
        options.add_session_config_entry('session.intra_op.allow_spinning', '0')
        session = ort.InferenceSession(str(args.model), options, providers=['CPUExecutionProvider'])
        native_run()
        probe_start = time.perf_counter_ns()
        native_run()
        tile_ns = time.perf_counter_ns() - probe_start
    prepare_ms = (time.perf_counter_ns() - start) / 1e6
    budget_ns = min(3.2e9, 4.3e9 - (time.perf_counter_ns() - start) - .7e9)
    if budget_ns <= 0:
        raise RuntimeError('Preparation exhausted the page budget')
    budget = max(1, int(budget_ns / (tile_ns * 1.4)))
    iw, ih = input_size(*src.size, budget)
    low = src.resize((iw, ih), Image.Resampling.BILINEAR)
    padded = np.pad(np.asarray(low), ((HALO, HALO + TILE), (HALO, HALO + TILE), (0, 0)), mode='edge')
    neural = np.empty((ih * 2, iw * 2, 3), dtype=np.uint8)
    tiles = 0
    inference_ns = 0
    neural_start = time.perf_counter_ns()
    for y in range(0, ih, TILE):
        for x in range(0, iw, TILE):
            input_data[0] = padded[y:y + SIDE, x:x + SIDE].transpose(2, 0, 1).astype(np.float32) / 255
            tile_start = time.perf_counter_ns()
            native_run()
            elapsed = time.perf_counter_ns() - tile_start
            inference_ns += elapsed
            tile_ns = max(elapsed, (tile_ns * 3 + elapsed) // 4)
            tw, th = min(TILE, iw - x) * 2, min(TILE, ih - y) * 2
            core = output_data[0, :, HALO * 2:HALO * 2 + th, HALO * 2:HALO * 2 + tw]
            neural[y * 2:y * 2 + th, x * 2:x * 2 + tw] = np.floor(np.clip(core.transpose(1, 2, 0), 0, 1) * 255 + .5).astype(np.uint8)
            tiles += 1
    neural_ms = (time.perf_counter_ns() - neural_start) / 1e6
    output_scale = min(2, 3200 / max(src.size), (4000000 / (src.width * src.height)) ** .5)
    output_size = tuple(max(1, int(v * output_scale + .5)) for v in src.size)
    original = src.resize(output_size, Image.Resampling.BILINEAR)
    low_full = low.resize(output_size, Image.Resampling.BILINEAR)
    ai_full = Image.fromarray(neural).resize(output_size, Image.Resampling.BILINEAR)
    composed = np.empty((output_size[1], output_size[0], 3), dtype=np.uint8)
    # Small row bands, like Android; no extra float full-page working images.
    orig_array, low_array, ai_array = map(np.asarray, (original, low_full, ai_full))
    for y in range(0, output_size[1], 48):
        detail = orig_array[y:y + 48].astype(np.int16)
        delta = ai_array[y:y + 48].astype(np.int16) - low_array[y:y + 48].astype(np.int16)
        composed[y:y + 48] = np.clip(detail + delta, 0, 255).astype(np.uint8)
    total_ms = (time.perf_counter_ns() - start) / 1e6
    return Image.fromarray(composed), original, {
        'cold': cold, 'source': list(src.size), 'neuralInput': [iw, ih], 'output': list(output_size),
        'tiles': tiles, 'inferredTiles': tiles, 'cacheHits': 0, 'aiComplete': True,
        'prepareMs': round(prepare_ms, 2), 'inferenceMs': round(inference_ns / 1e6, 2),
        'neuralAndTilesMs': round(neural_ms, 2), 'totalMs': round(total_ms, 2),
    }

cases = []
for path in args.input:
    cases.append((path.stem, path))
# Dense lines are deliberately not repetitive blank tiles, and text stays at original resolution.
synthetic = Image.new('RGB', (1200, 1800), 'white')
draw = ImageDraw.Draw(synthetic)
for y in range(0, 1800, 11):
    draw.line((0, y, 1200, y + 140), fill='black', width=1 + y % 3)
for x in range(0, 1200, 23):
    draw.line((x, 0, x + 130, 1800), fill='#777777', width=1)
for y in range(50, 1800, 113):
    draw.text((100, y), 'Manga 12345 fine text and strokes', fill='black')
cases.append(('dense-1200x1800', synthetic))
results = []
for name, item in cases:
    # Reading input compressed bytes is timed too; saved preview output is excluded.
    io_start = time.perf_counter_ns()
    image = Image.open(item) if isinstance(item, Path) else item.copy()
    image.load()
    io_ms = (time.perf_counter_ns() - io_start) / 1e6
    enhanced, original, result = enhance(image, session is None)
    result['input'] = str(item) if isinstance(item, Path) else name
    result['decodeMs'] = round(io_ms, 2)
    result['totalMs'] = round(result['totalMs'] + io_ms, 2)
    result['name'] = name
    results.append(result)
    enhanced.save(args.directory / (name + '-enhanced.png'))
    original.save(args.directory / (name + '-original.png'))
    print(json.dumps(result), flush=True)
report = {'environment': platform.platform(), 'onnxruntime': ort.__version__, 'providers': ['CPUExecutionProvider'],
          'threads': min(4, __import__('os').cpu_count()), 'deviceResults': False,
          'modelSha256': hashlib.sha256(args.model.read_bytes()).hexdigest(),
          'limitations': 'Desktop CPU/Pillow, not Android. Adaptive input, full-detail original residual. Cold preparation omits Android backend selection probes. No tile/image cache.',
          'results': results}
(args.directory / 'benchmark.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
