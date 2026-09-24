#!/usr/bin/env python3
"""Audit geometry and pairing in the pinned host dependency, not model quality."""
from pathlib import Path
import os,json,hashlib,inspect,importlib.metadata,argparse
parser=argparse.ArgumentParser(description="Check pinned upstream detector geometry without loading models")
parser.add_argument("--output",type=Path,required=True)
out=parser.parse_args().output.resolve();root=Path(__file__).resolve().parents[1]
assert out!=root and root not in out.parents, "Output must stay outside the project"
assert importlib.metadata.version("rapidocr")=="3.9.2"
out.mkdir(parents=True,exist_ok=True);os.chdir(out)
import onnxruntime as ort
ort.disable_telemetry_events()
import numpy as np
from rapidocr.ch_ppocr_det.main import TextDetector
from rapidocr.ch_ppocr_det.utils import DetPreProcess
# No constructor/models/inference: execute only the installed, pinned upstream shape policy.
obj=TextDetector.__new__(TextDetector);obj.limit_type='max';obj.limit_side_len=1280;obj.mean=[.5,.5,.5];obj.std=[.5,.5,.5]
rows=[]
for h,w in [(100,959),(100,960),(100,1280),(100,1499),(100,1500),(100,2001),(100,2500),(16,48),(16,80),(17,48)]:
 pre=obj.get_preprocess(max(h,w));image=np.zeros((h,w,3),np.uint8);result=pre(image);rows.append({'height':h,'width':w,'configuredLimit':1280,'effectiveLimit':pre.limit_side_len,'outputShape':None if result is None else list(result.shape),'dtype':None if result is None else str(result.dtype)})
assert [r['effectiveLimit'] for r in rows[:7]]==[960,1500,1500,1500,2000,2000,2000]
assert rows[3]['outputShape'][3]==1504 and rows[7]['outputShape'] is None and rows[9]['outputShape']==[1,3,32,64]
# Two synthetic boxes expose the return-value association without any neural inference.
boxes=np.array([[[20,1],[30,1],[30,10],[20,10]],[[0,1],[10,1],[10,10],[0,10]]],dtype=np.float32)
scores=[0.2,0.9]
obj.session=lambda value: np.zeros((1,1,32,32),dtype=np.float32)
obj.postprocess_op=lambda pred,shape: (boxes.copy(),scores.copy())
result=obj(np.zeros((100,100,3),dtype=np.uint8))
assert np.array_equal(result.boxes,boxes[[1,0]]) and result.scores==scores
association={"scope":"synthetic_postprocess_stub_no_neural_inference","inputScores":scores,
             "inputBoxes":boxes.tolist(),"returnedBoxes":result.boxes.tolist(),"returnedScores":result.scores,
             "expectedPairedScoresIfSortedTogether":[0.9,0.2]}
sources={}
for cls in [TextDetector,DetPreProcess]:
 p=Path(inspect.getsourcefile(cls));sources[str(p).split('/site-packages/')[-1]]=hashlib.sha256(p.read_bytes()).hexdigest()
r={'scope':'installed_upstream_resize_policy_only_no_model_inference','rapidocrVersion':importlib.metadata.version('rapidocr'),'numpyVersion':np.__version__,'sourceHashes':sources,'cases':rows,'scoreAssociation':association,'findings':['Configured max limit 1280 is replaced by 960/1500/2000 based on the input max dimension','Output width/height rounded to multiples of 32 with Python ties-to-even; can slightly exceed effective limit','Too-thin dimension can round to zero and return no tensor','TextDetector sorts boxes but leaves scores in prior order; preserve paired identities in future port'],'references':['https://github.com/RapidAI/RapidOCR/blob/v3.9.2/python/rapidocr/ch_ppocr_det/main.py','https://github.com/RapidAI/RapidOCR/blob/v3.9.2/python/rapidocr/ch_ppocr_det/utils.py']}
(out/'audit.json').write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n');print(json.dumps(rows))
