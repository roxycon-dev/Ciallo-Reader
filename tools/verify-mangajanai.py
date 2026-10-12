"""Offline CPU graph/tiling smoke test, using the same fixed-size NCHW RGB tiles as Android.
Usage: python tools/verify-mangajanai.py MODEL.onnx OUTPUT_DIRECTORY
This verifies ONNX execution on desktop; it is not an Android performance benchmark.
"""
from pathlib import Path
import hashlib, json, sys, time
import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw

model, directory = Path(sys.argv[1]), Path(sys.argv[2])
directory.mkdir(parents=True, exist_ok=True)
expected = 'c985d6b78ed1a659867cd5697ce80743ad9ddd73868f463e69c824f15f169eb0'
assert hashlib.sha256(model.read_bytes()).hexdigest() == expected
options = ort.SessionOptions()
options.intra_op_num_threads = 1
options.inter_op_num_threads = 1
options.enable_mem_pattern = False
options.enable_cpu_mem_arena = False
options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
started = time.perf_counter()
session = ort.InferenceSession(str(model), options, providers=['CPUExecutionProvider'])
image = Image.new('RGB', (96, 128), '#eeeeee')
draw = ImageDraw.Draw(image)
draw.text((8, 8), 'MANGA 132', fill='black')
for i in range(12, 120, 5): draw.line((4, i, 90, i-8), fill='black', width=1)
array = np.asarray(image).astype(np.float32)/255
tile, halo = 48, 16
input_side = tile+2*halo
padded = np.pad(array, ((halo, halo+tile), (halo, halo+tile), (0, 0)), mode='edge')
output = np.empty((256, 192, 3), np.float32)
count = 0
for y in range(0, 128, tile):
    for x in range(0, 96, tile):
        patch = padded[y:y+input_side, x:x+input_side].transpose(2,0,1)[None].copy()
        result = session.run(None, {'input': patch})[0]
        assert result.shape == (1, 3, input_side*2, input_side*2)
        assert np.isfinite(result).all()
        th, tw = min(tile, 128-y)*2, min(tile, 96-x)*2
        output[y*2:y*2+th, x*2:x*2+tw] = result[0, :, halo*2:halo*2+th, halo*2:halo*2+tw].transpose(1,2,0)
        count += 1
assert np.isfinite(output).all()
Image.fromarray((np.clip(output,0,1)*255).astype(np.uint8)).save(directory/'mangajanai-smoke-output.png')
image.save(directory/'mangajanai-smoke-input.png')
report = {'sha256': expected, 'provider': session.get_providers(), 'input': [96,128], 'output': [192,256],
          'tiles': count, 'tileCore': tile, 'halo': halo, 'finiteOutput': True,
          'meanDifferenceFromNearest': float(np.abs(output-array.repeat(2,0).repeat(2,1)).mean()),
          'desktopSeconds': round(time.perf_counter()-started,3)}
(directory/'model-smoke.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
print(json.dumps(report, indent=2))
