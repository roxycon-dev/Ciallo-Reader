"""Losslessly convert MangaJaNai V1 2x/1200p FP32 to ncnn (pnnx 20260526).

The unsupported pixel-unshuffle prefix is performed explicitly by our JNI bridge.
Every convolution weight and bias must match the source ONNX bytes exactly.
Requires onnx, numpy, and the official pnnx executable; no training/pickle code.
"""
from pathlib import Path
import argparse, hashlib, json, struct, subprocess, zipfile
import onnx
from onnx import numpy_helper

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('model', type=Path)
parser.add_argument('pnnx', type=Path)
parser.add_argument('directory', type=Path)
args=parser.parse_args()
assert hashlib.sha256(args.model.read_bytes()).hexdigest()=='c985d6b78ed1a659867cd5697ce80743ad9ddd73868f463e69c824f15f169eb0'
p=args.directory.resolve(); p.mkdir(parents=True,exist_ok=True)
subprocess.run([str(args.pnnx.resolve()),str(args.model.resolve()),'inputshape=[1,3,80,80]','fp16=0',
    f'ncnnparam={p}/full.param',f'ncnnbin={p}/full.bin',f'pnnxparam={p}/full.pnnx.param',
    f'pnnxbin={p}/full.pnnx.bin',f'pnnxpy={p}/full.pnnx.py',f'ncnnpy={p}/full.ncnn.py'],check=True)
lines=(p/'full.param').read_text().splitlines()
assert [line.split()[0] for line in lines[3:6]]==['Reshape','Permute','Reshape']
assert lines[6].split()[0]=='Convolution' and lines[6].split()[4]=='3'
layers,blobs=map(int,lines[1].split()); lines[1]=f'{layers-3} {blobs-3}'
lines[2]='Input input 0 1 3'; del lines[3:6]
(p/'full.param').write_text('\n'.join(lines)+'\n',encoding='ascii',newline='\r\n')
model=onnx.load(args.model)
initializers={t.name:numpy_helper.to_array(t) for t in model.graph.initializer}
convs=[n for n in model.graph.node if n.op_type=='Conv']
assert len(convs)==351
with (p/'full.bin').open('rb') as binary:
    for conv in convs:
        assert struct.unpack('<I',binary.read(4))[0]==0, 'Weights must be FP32, never FP16/int8'
        for name in conv.input[1:]:
            expected=initializers[name].astype('<f4',copy=False).tobytes()
            assert binary.read(len(expected))==expected, f'Weight changed: {name}'
    assert not binary.read(1), 'Unexpected transformed weights'
manifest={name:{'bytes':(p/name).stat().st_size,'sha256':hashlib.sha256((p/name).read_bytes()).hexdigest()} for name in ['full.param','full.bin']}
manifest['weights']={'convolutions':351,'precision':'FP32','source_sha256':hashlib.sha256(args.model.read_bytes()).hexdigest(),'byte_identical':True}
(p/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
with zipfile.ZipFile(p/'MangaJaNai-full-v1-fp32.zip','w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
    archive.write(args.model,'full.onnx')
    for name in ['full.param','full.bin','manifest.json']: archive.write(p/name,name)
    archive.write(Path(__file__).resolve().parents[1]/'app/src/main/assets/comic_enhancement/MangaJaNai-Full-NOTICE.txt','NOTICE.txt')
print(json.dumps(manifest,indent=2))
