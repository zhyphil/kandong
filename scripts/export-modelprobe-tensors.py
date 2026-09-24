#!/usr/bin/env python3
"""Extract bounded tensors from the pinned host detector/recognizer pipeline, preserving real batch order."""
from pathlib import Path
import argparse,json,gzip,hashlib,os
from PIL import Image
import numpy as np
parser=argparse.ArgumentParser(description='Export actual synthetic pipeline tensors; not full Android OCR.')
parser.add_argument('--models',type=Path,required=True);parser.add_argument('--processed-inputs',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
root=Path(__file__).resolve().parents[1];models=args.models.resolve();args.processed_inputs=args.processed_inputs.resolve();out=args.output.resolve()
assert out!=root and root not in out.parents, 'Use an output directory outside the project'
out.mkdir(parents=True,exist_ok=True);os.chdir(out)
# ORT may initialize its SDK before the telemetry API is callable. Keep initialization files in this explicit temporary directory.
import onnxruntime as ort
ort.disable_telemetry_events()
from rapidocr import RapidOCR,ModelType,OCRVersion,LangRec
provenance=json.loads((root/'docs/evidence/rapidocr/2026-09-24/models.json').read_text())
for name,entry in provenance['files'].items():
 assert hashlib.sha256((models/name).read_bytes()).hexdigest()==entry['sha256']
def sha(b):return hashlib.sha256(b).hexdigest()
selected=['en-quality-16','fr-nonrefundable-16','zh-hans-quality-16','zh-hant-quality-16','mixed-quality-16'];report=json.loads((root/'docs/evidence/rapidocr/2026-09-24/baseline72.json').read_text());tasks=[];model_records=[]
class Recorder:
 def __init__(self,original):self.original=original;self.calls=[]
 def __call__(self,tensor):
  result=self.original(tensor);self.calls.append((tensor.copy(),result.copy()));return result
for lang in [LangRec.CH,LangRec.LATIN]:
 name=lang.value+'_PP-OCRv5_rec_mobile.onnx';params={'Global.model_root_dir':str(models),'Global.use_cls':False,'Global.text_score':0.0,'Global.log_level':'warning','Det.ocr_version':OCRVersion.PPOCRV5,'Det.model_type':ModelType.MOBILE,'Det.model_path':str(models/'ch_PP-OCRv5_det_mobile.onnx'),'Det.limit_side_len':1280,'Det.limit_type':'max','Cls.model_path':str(models/'ch_ppocr_mobile_v2.0_cls_mobile.onnx'),'Rec.ocr_version':OCRVersion.PPOCRV5,'Rec.model_type':ModelType.MOBILE,'Rec.lang_type':lang,'Rec.model_path':str(models/name),'EngineConfig.onnxruntime.intra_op_num_threads':1,'EngineConfig.onnxruntime.inter_op_num_threads':1}
 engine=RapidOCR(params=params);recorder=Recorder(engine.text_rec.session);engine.text_rec.session=recorder
 dictionary=list(engine.text_rec.postprocess_op.character);dict_bytes=(json.dumps(dictionary,ensure_ascii=False,separators=(',',':'))+'\n').encode();dict_name=lang.value+'-dictionary.json';(out/dict_name).write_bytes(dict_bytes)
 session=recorder.original.session
 model_records.append({'id':lang.value,'asset':'models/'+name,'sha256':sha((models/name).read_bytes()),'bytes':(models/name).stat().st_size,'dictionaryAsset':'probes/'+dict_name,'dictionarySha256':sha(dict_bytes),'vocabularySize':len(dictionary),'inputName':session.get_inputs()[0].name,'outputName':session.get_outputs()[0].name})
 for input_id in selected:
  recorder.calls.clear();p=args.processed_inputs/(input_id+'-2x.png')
  expected=next(r for r in report['results'] if r['inputId']==input_id and r['scale']==2 and r['recognizer']==lang.value)
  assert sha(Image.open(p).convert('RGBA').tobytes())==expected['inputPixelSha256']
  output=engine(str(p),use_cls=False,text_score=0.0);assert len(recorder.calls)==1 and list(output.txts)==expected['lines']
  tensor,predictions=recorder.calls[0];assert tensor.dtype==np.float32 and tensor.ndim==4 and tensor.shape[0]<=4 and tensor.shape[1:3]==(3,48) and tensor.shape[3]<=2048 and tensor.size<=1200000
  assert np.isfinite(tensor).all() and tensor.min()>=-1 and tensor.max()<=1
  raw=tensor.astype('<f4').tobytes();data=gzip.compress(raw,mtime=0);asset=f'{lang.value}-{input_id}.f32z';(out/asset).write_bytes(data)
  decoded,_=engine.text_rec.postprocess_op(predictions,False);indices=predictions.argmax(axis=2).astype('<i4');row={'id':lang.value+'/'+input_id,'sourceInputId':input_id,'sourcePixelSha256':expected['inputPixelSha256'],'model':lang.value,'asset':'probes/'+asset,'compressedSha256':sha(data),'tensorSha256':sha(raw),'compressedBytes':len(data),'tensorBytes':len(raw),'shape':list(tensor.shape),'dtype':'float32LE','outputShape':list(predictions.shape),'expectedHostRaw':[x[0] for x in decoded],'expectedHostArgmaxSha256':sha(indices.tobytes()),'hostPipelineLinesReadingOrder':list(output.txts),'note':'Tensor batches are sorted by crop aspect ratio before recognition; reference strings are in actual tensor row order, not page reading order.'};tasks.append(row);print(row['id'],row['shape'],row['outputShape'],row['expectedHostRaw'],flush=True)
manifest={'schema':1,'fixtureVersion':'ppocr-mobile-tensor-v1','scope':'packaged_synthetic_model_feasibility_not_full_OCR','hostRuntime':'onnxruntime1.30.0_CPU_1thread_RapidOCR3.9.2','sourceManifestSha256':report['manifestSha256'],'models':model_records,'tasks':tasks,'ordering':'models then five inputs; every recognizer sees every input; expected reference only comparison after inference'}
b=(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode();(out/'manifest.json').write_bytes(b);print('MANIFEST',sha(b),'bytes',len(b),'tensorGzipBytes',sum(r['compressedBytes'] for r in tasks))
