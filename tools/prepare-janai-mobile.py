"""Convert the author's SPAN-S V3 denoise ONNX to the bundled mobile FP32 model.

Input is IllustrationJaNai_V3denoise_onnx.zip from the-database/MangaJaNai 3.0.0.
No training code or pickle is executed. Requires onnx, onnxruntime, numpy and Pillow.
"""
from pathlib import Path
import argparse
import hashlib
import json
import zipfile
import numpy as np
import onnx
from onnx import TensorProto, numpy_helper
from onnxruntime.quantization import CalibrationDataReader, QuantFormat, QuantType, quantize_static
from PIL import Image, ImageDraw, ImageFont

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('archive', type=Path)
parser.add_argument('directory', type=Path)
parser.add_argument('--try-int8', action='store_true', help='Experimental calibration only; NOT a release model. Validate image quality before use.')
args = parser.parse_args()
assert hashlib.sha256(args.archive.read_bytes()).hexdigest() == '86d393d5c55a7ac0df986cd42e79a0351ecf89b370752f79d4fd0a9cf3f2aa58'
args.directory.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(args.archive) as archive:
    name = next(n for n in archive.namelist() if 'SPAN_S' in n and n.endswith('.onnx'))
    data = archive.read(name)
model = onnx.load_model_from_string(data)
for index, tensor in enumerate(model.graph.initializer):
    if tensor.data_type == TensorProto.FLOAT16:
        model.graph.initializer[index].CopyFrom(numpy_helper.from_array(numpy_helper.to_array(tensor).astype(np.float32), tensor.name))
for value in list(model.graph.input) + list(model.graph.output) + list(model.graph.value_info):
    if value.type.tensor_type.elem_type == TensorProto.FLOAT16:
        value.type.tensor_type.elem_type = TensorProto.FLOAT
for node in model.graph.node:
    for attr in node.attribute:
        if attr.type == onnx.AttributeProto.TENSOR and attr.t.data_type == TensorProto.FLOAT16:
            attr.t.CopyFrom(numpy_helper.from_array(numpy_helper.to_array(attr.t).astype(np.float32), attr.t.name))
# These operators have identical definitions between 18 and 23; lower opset supports mobile EPs.
assert {n.op_type for n in model.graph.node} <= {'Conv', 'Sigmoid', 'Mul', 'Add', 'Sub', 'Concat', 'DepthToSpace'}
model.opset_import[0].version = 18
# Exporter reused input H/W names for a 2x output; avoid contradictory static EP shapes.
model.graph.output[0].type.tensor_type.shape.dim[2].dim_param = 'output_height'
model.graph.output[0].type.tensor_type.shape.dim[3].dim_param = 'output_width'
model = onnx.shape_inference.infer_shapes(model)
onnx.checker.check_model(model)
fp32 = args.directory/'janai-span-f32.onnx'
onnx.save(model, fp32)

def calibration(seed, side=176):
    rng = np.random.default_rng(seed)
    paper = int(rng.integers(180, 256))
    image = Image.new('RGB', (side, side), (paper, paper, paper))
    draw = ImageDraw.Draw(image)
    if seed % 8 == 0:
        image = Image.fromarray(rng.integers(0, 256, (side, side, 3), dtype=np.uint8))
    elif seed % 8 == 1:
        image = Image.new('RGB', (side, side), int(rng.integers(0, 256)))
    else:
        for _ in range(25):
            points = tuple(int(v) for v in rng.integers(0, side, 4))
            draw.line(points, fill=tuple(int(v) for v in rng.integers(0, 180, 3)), width=int(rng.integers(1, 5)))
        draw.ellipse((35, 35, 130, 130), outline='black', width=2)
        for y in range(5, side-15, 21):
            draw.text((8, y), 'Manga 123 test', fill='black')
        if seed % 3 == 0:
            for y in range(0, side, 4):
                for x in range(0, side, 4):
                    draw.point((x, y), fill='#888888')
    return np.asarray(image).astype(np.float32).transpose(2, 0, 1)[None].copy()/255

class Samples(CalibrationDataReader):
    def __init__(self): self.index = 0
    def get_next(self):
        if self.index >= 48: return None
        value = {'input': calibration(self.index)}
        self.index += 1
        return value

models = [fp32]
if args.try_int8:
    quantized = args.directory/'janai-span-int8.onnx'
    quantize_static(str(fp32), str(quantized), Samples(), quant_format=QuantFormat.QOperator,
                    activation_type=QuantType.QUInt8, weight_type=QuantType.QInt8,
                    per_channel=True, op_types_to_quantize=['Conv'],
                    extra_options={'WeightSymmetric': True})
    models.append(quantized)
report = {'upstreamName': name, 'upstreamSha256': hashlib.sha256(data).hexdigest(),
          'experimentalInt8': args.try_int8,
          'models': [{ 'name': p.name, 'bytes': p.stat().st_size,
                       'sha256': hashlib.sha256(p.read_bytes()).hexdigest() } for p in models]}
(args.directory/'model-conversion.json').write_text(json.dumps(report, indent=2)+'\n')
print(json.dumps(report, indent=2), flush=True)
