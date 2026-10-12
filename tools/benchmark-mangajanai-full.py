"""Measure complete original-resolution FP32 pages; no page/tile result cache.

Use official ncnn Python bindings and ONNX Runtime. Desktop results do not prove phone speed.
The CPU/GPU numerical comparison uses the same tile layout; legacy 48px CPU timing is separate.
"""
from pathlib import Path
import argparse, json, time
import numpy as np
import onnxruntime as ort
import ncnn
from PIL import Image

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('model_directory',type=Path)
parser.add_argument('output_directory',type=Path)
parser.add_argument('--input',type=Path,required=True)
parser.add_argument('--long-edge',type=int,default=1800)
parser.add_argument('--core',type=int,choices=[48,128,240],default=240)
parser.add_argument('--legacy-cpu',action='store_true')
args=parser.parse_args(); p=args.model_directory; args.output_directory.mkdir(parents=True,exist_ok=True)
image=Image.open(args.input).convert('RGB'); image.thumbnail((args.long_edge,args.long_edge),Image.Resampling.LANCZOS)
source=np.asarray(image,dtype=np.float32).transpose(2,0,1).copy()/255
height,width=source.shape[1:]; records=[]
options=ort.SessionOptions(); options.intra_op_num_threads=4; options.inter_op_num_threads=1
start=time.perf_counter(); cpu=ort.InferenceSession(str(p/'full.onnx'),options,providers=['CPUExecutionProvider'])
cpu_load=time.perf_counter()-start
def patch(x,y,core):
    side=core+32
    ys=np.clip(np.arange(y-16,y-16+side),0,height-1)
    xs=np.clip(np.arange(x-16,x-16+side),0,width-1)
    return source[:,ys[:,None],xs].copy()
if args.legacy_cpu:
    start=time.perf_counter(); count=0
    for y in range(0,height,48):
        for x in range(0,width,48):
            cpu.run(None,{'input':patch(x,y,48)[None]}); count+=1
    records.append(dict(engine='legacy CPU FP32 48+16',input=[width,height],tiles=count,seconds=time.perf_counter()-start+cpu_load))
refs=[]
for x,y in [(0,0),(width//3,height//3),(width//2,height//2)]:
    data=patch(x,y,args.core); refs.append((data,cpu.run(None,{'input':data[None]})[0][0]))
del cpu
start=time.perf_counter(); net=ncnn.Net(); net.opt.num_threads=4; net.opt.use_vulkan_compute=True
for key in ['use_fp16_packed','use_fp16_storage','use_fp16_arithmetic','use_bf16_storage','use_bf16_packed','use_int8_inference','use_int8_packed','use_int8_storage','use_int8_arithmetic','use_fp16_uniform','use_int8_uniform','use_cooperative_matrix']:
    if hasattr(net.opt,key): setattr(net.opt,key,False)
assert net.load_param(str(p/'full.param'))==0 and net.load_model(str(p/'full.bin'))==0
gpu_load=time.perf_counter()-start
def infer(data):
    side=data.shape[1]; half=side//2
    packed=data.reshape(3,half,2,half,2).transpose(0,2,4,1,3).reshape(12,half,half).copy()
    with net.create_extractor() as ex:
        assert ex.input('3',ncnn.Mat(packed).clone())==0
        result,output=ex.extract('out0'); assert result==0
        return np.array(output).copy()
for data,reference in refs:
    output=infer(data); error=np.abs(output-reference)
    assert np.isfinite(output).all() and error.max()<=.0015 and error.mean()<=.0001
    records.append(dict(test='same-size CPU/GPU FP32 accuracy',side=data.shape[1],max_error=float(error.max()),mean_error=float(error.mean()),max_8bit_error=int(np.abs(np.rint(np.clip(output,0,1)*255)-np.rint(np.clip(reference,0,1)*255)).max())))
for run in range(2):
    start=time.perf_counter(); page=np.empty((3,height*2,width*2),dtype=np.float32); count=0
    for y in range(0,height,args.core):
        for x in range(0,width,args.core):
            output=infer(patch(x,y,args.core)); count+=1
            h=min(args.core,height-y)*2; w=min(args.core,width-x)*2
            page[:,y*2:y*2+h,x*2:x*2+w]=output[:,32:32+h,32:32+w]
    assert np.isfinite(page).all()
    elapsed=time.perf_counter()-start
    records.append(dict(engine='Vulkan FP32',input=[width,height],core=args.core,tiles=count,run=run,load_seconds=gpu_load,whole_page_seconds=elapsed,including_engine_load_seconds=elapsed+gpu_load if run==0 else elapsed))
    Image.fromarray(np.rint(np.clip(page.transpose(1,2,0),0,1)*255).astype(np.uint8)).save(args.output_directory/f'full-{run}.png')
net.clear()
(args.output_directory/'benchmark.json').write_text(json.dumps(records,indent=2)+'\n')
print(json.dumps(records,indent=2),flush=True)
