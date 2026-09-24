#!/usr/bin/env python3
"""Freeze synthetic detector inputs/probability maps from pinned upstream code.
No real images, OCR answer routing, model download, contours or crop output.
Run from the repository scripts location; output must be outside the repository.
"""
import argparse,gzip,hashlib,importlib.metadata,json,os,inspect,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
SOURCE_SHA='2341dff63bd10d41867351ddff0e4145daa2d9d3493bc8cdc88ea289c1a076a8'
MODEL_SHA='4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae'
IDS=['en-quality-16','fr-nonrefundable-16','zh-hans-quality-16','zh-hant-quality-16','mixed-quality-16','blank-negative-24','color-control','wide-959','wide-1499','wide-2001']
ACCEPTANCE={'inputFloatBits':'exact','outputAtol':0.00001,'outputRtol':0.0001,'outputMeanAbsMax':0.000001,'threshold':0.3,'thresholdOperator':'> float32(0.3)','maxThresholdFlips':0,'outputRangeMin':0.0,'outputRangeMax':1.0,'nearThresholdBand':0.0001,'note':'Frozen before device execution; do not widen to fit results; numeric parity only'}
def sha(data):return hashlib.sha256(data).hexdigest()
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--models',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();out=a.output.resolve();models=a.models.resolve();assert out!=ROOT and ROOT not in out.parents
 versions={name:importlib.metadata.version(name) for name in ['rapidocr','onnxruntime','numpy','opencv-python','pillow']};assert versions=={'rapidocr':'3.9.2','onnxruntime':'1.30.0','numpy':'2.5.3','opencv-python':'5.0.0.93','pillow':'12.3.0'},versions
 out.mkdir(parents=True,exist_ok=True);os.chdir(out)
 import onnxruntime as ort
 ort.disable_telemetry_events()
 import numpy as np
 import cv2
 from PIL import Image
 from rapidocr.ch_ppocr_det.main import TextDetector
 from rapidocr.ch_ppocr_det.utils import DetPreProcess
 source=ROOT/'ocrlab/src/main/assets/trilingual-v1';source_bytes=(source/'manifest.json').read_bytes();assert sha(source_bytes)==SOURCE_SHA;source_manifest=json.loads(source_bytes)
 model=models/'ch_PP-OCRv5_det_mobile.onnx';model_bytes=model.read_bytes();assert len(model_bytes)==4819576 and sha(model_bytes)==MODEL_SHA
 options=ort.SessionOptions();options.intra_op_num_threads=1;options.inter_op_num_threads=1;options.enable_cpu_mem_arena=False;options.execution_mode=ort.ExecutionMode.ORT_SEQUENTIAL;options.graph_optimization_level=ort.GraphOptimizationLevel.ORT_ENABLE_ALL
 session=ort.InferenceSession(str(model),sess_options=options,providers=['CPUExecutionProvider']);assert [v.name for v in session.get_inputs()]==['x'] and [v.name for v in session.get_outputs()]==['fetch_name_0']
 obj=TextDetector.__new__(TextDetector);obj.limit_type='max';obj.limit_side_len=1280;obj.mean=[.5,.5,.5];obj.std=[.5,.5,.5]
 files={};cases=[];properties=['# Fixed upstream detector data; generated before Android implementation','cases='+','.join(IDS)]
 def write(name,data,**meta):
  assert '/' not in name and len(data)<=2*1024*1024
  (out/name).write_bytes(data);files[name]={'bytes':len(data),'sha256':sha(data),**meta};return name
 def zipped(name,data,**meta):return write(name,gzip.compress(data,mtime=0),decodedBytes=len(data),decodedSha256=sha(data),**meta)
 for id in IDS:
  if id=='color-control' or id.startswith('wide-'):
   dims=(53,67) if id=='color-control' else (100,int(id.split('-')[1]))
   y,x=np.indices(dims);bgr=np.stack(((x*13+y*7)%256,(x*3+y*17+19)%256,(x*29+y*5+31)%256),axis=-1).astype(np.uint8);Image.fromarray(bgr[:,:,::-1]).save(out/(id+'-source.png'));png=(out/(id+'-source.png')).read_bytes()
  else:
   entry=next(v for v in source_manifest['images'] if v['asset']==id+'.png');png=(source/entry['asset']).read_bytes();assert sha(png)==entry['pngSha256'];bgr=np.asarray(Image.open(source/entry['asset']).convert('RGB'))[:,:,::-1].copy()
  h,w=bgr.shape[:2];source_asset=write(id+'-source.png',png,width=w,height=h,rawBgrSha256=sha(bgr.tobytes()))
  pre=obj.get_preprocess(max(h,w));resized=pre.resize(bgr);tensor=pre(bgr);assert tensor.dtype==np.float32 and tensor.shape[0:2]==(1,3);rh,rw=resized.shape[:2];assert tensor.shape==(1,3,rh,rw) and tensor.size<=3*1024*1024
  reference_png=id+'-resized.png';Image.fromarray(resized[:,:,::-1]).save(out/reference_png);write(reference_png,(out/reference_png).read_bytes(),width=rw,height=rh,rawBgrSha256=sha(resized.tobytes()))
  argb=(np.uint32(0xff000000)|(resized[:,:,2].astype(np.uint32)<<16)|(resized[:,:,1].astype(np.uint32)<<8)|resized[:,:,0].astype(np.uint32)).astype('<u4')
  argb_asset=zipped(id+'-resized.argbz',argb.tobytes(),width=rw,height=rh)
  tensor_asset=zipped(id+'-input.f32z',np.ascontiguousarray(tensor,dtype='<f4').tobytes())
  probability=session.run(['fetch_name_0'],{'x':tensor})[0];assert probability.dtype==np.float32 and probability.shape==(1,1,rh,rw) and np.isfinite(probability).all() and probability.min()>=0 and probability.max()<=1
  again=session.run(['fetch_name_0'],{'x':tensor})[0];assert np.array_equal(probability.view(np.uint32),again.view(np.uint32))
  output_asset=zipped(id+'-output.f32z',np.ascontiguousarray(probability,dtype='<f4').tobytes());mask=(probability>np.float32(.3)).astype(np.uint8)
  mask_asset=zipped(id+'-mask.u8z',mask.tobytes())
  row={'id':id,'source':source_asset,'resized':reference_png,'resizedArgb':argb_asset,'sourceWidth':w,'sourceHeight':h,'width':rw,'height':rh,'effectiveLimit':pre.limit_side_len,'input':tensor_asset,'inputShape':list(tensor.shape),'output':output_asset,'outputShape':list(probability.shape),'mask':mask_asset,'positivePixels':int(mask.sum()),'nearThresholdPixels':int((np.abs(probability-np.float32(.3))<=.0001).sum()),'minProbability':float(probability.min()),'maxProbability':float(probability.max()),'hostRepeatBitwiseEqual':True};cases.append(row)
  for key in ['width','height','sourceWidth','sourceHeight','resizedArgb','input'] :properties.append(f'{id}.{key}={row[key]}')
  properties.append(f'{id}.inputSha256={files[tensor_asset]["decodedSha256"]}')
 dims=[]
 for h,w in [(100,959),(100,960),(100,1280),(100,1499),(100,1500),(100,2001),(100,2500),(16,48),(16,80),(17,48),(48,17),(80,80),(112,112),(2500,100)]:
  pre=obj.get_preprocess(max(h,w));t=pre(np.zeros((h,w,3),np.uint8));dims.append({'sourceWidth':w,'sourceHeight':h,'effectiveLimit':pre.limit_side_len,'shape':None if t is None else list(t.shape)})
 source_hashes={str(Path(inspect.getsourcefile(c))).split('/site-packages/')[-1]:sha(Path(inspect.getsourcefile(c)).read_bytes()) for c in [TextDetector,DetPreProcess]}
 prop_data=('\n'.join(properties)+'\n').encode();write('manifest.properties',prop_data)
 manifest={'schema':1,'scope':'fixed_synthetic_detector_probability_parity_not_boxes_crops_or_OCR_quality','sourceManifestSha256':SOURCE_SHA,'model':{'asset':'detector-model/ch_PP-OCRv5_det_mobile.onnx','bytes':4819576,'sha256':MODEL_SHA,'inputName':'x','outputName':'fetch_name_0','dtype':'FLOAT','layout':'NCHW','outputLayout':'N1HW','sourceUrl':'https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv5/det/ch_PP-OCRv5_det_mobile.onnx'},'versions':versions,'sourceHashes':source_hashes,'normalization':{'channelOrder':'BGR','mean':[.5,.5,.5],'std':[.5,.5,.5],'scale':'float32(1/255)','order':'uint8 to float32, multiply float32 scale, cast to float64 for subtract mean/divide std, cast result to float32','resize':'pinned DetPreProcess + cv2.INTER_LINEAR','configuredLimit':1280,'limitType':'max'},'runtime':{'provider':'CPUExecutionProvider','intraOpThreads':1,'interOpThreads':1,'optimization':'ALL','execution':'SEQUENTIAL','cpuArena':False},'acceptance':ACCEPTANCE,'limits':{'maxSourceDimension':4096,'maxResizedDimension':2048,'maxResizedPixels':1048576,'maxCompressedFileBytes':2097152,'maxModelBytes':6291456},'cases':cases,'dimensionCases':dims,'files':files}
 raw=(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode();(out/'manifest.json').write_bytes(raw)
 print(json.dumps({'manifestSha256':sha(raw),'cases':[(c['id'],c['inputShape'],c['positivePixels']) for c in cases],'files':len(files)+1,'bytes':sum(p.stat().st_size for p in out.iterdir() if p.name in files or p.name=='manifest.json')},ensure_ascii=False))
if __name__=='__main__':main()
