#!/usr/bin/env python3
"""Freeze already accepted synthetic geometry crops to recognition references.

No detector inference, download, screen access, answer correction or model choice.
Both cached recognizers see all 13 crops; eight empty cases cause no inference.
Calls pinned RapidOCR resizing and CTC, with explicit geometry/row identity.
"""
from pathlib import Path
import argparse, gzip, hashlib, importlib.metadata, inspect, json, math, os

ROOT = Path(__file__).resolve().parents[1]
PARENT_SHA = '1aa14f2efb50e62c5a2d95b45b7d5b5044d4ec90afa47a976e8dea50bbe466a6'
PROBE_SHA = '126d8d3860d5a4ea098f0875838dbed51a55d8a321f50d409805d394a460061c'
VERSIONS = {'rapidocr':'3.9.2','numpy':'2.5.3','opencv-python':'5.0.0.93',
            'pillow':'12.3.0','onnxruntime':'1.30.0'}
def sha(raw): return hashlib.sha256(raw).hexdigest()
def encoded(value): return (json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False)+'\n').encode()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    out=parser.parse_args().output.resolve()
    assert out!=ROOT and ROOT not in out.parents
    assert {k:importlib.metadata.version(k) for k in VERSIONS}==VERSIONS
    out.mkdir(parents=True,exist_ok=True)
    assert not any(out.iterdir()),'Use an empty output directory'
    os.chdir(out)
    import onnxruntime as ort
    ort.disable_telemetry_events()
    import cv2
    import numpy as np
    from PIL import Image
    from rapidocr.ch_ppocr_rec.main import TextRecognizer
    from rapidocr.ch_ppocr_rec.utils import CTCLabelDecode
    cv2.setNumThreads(1)
    parent_root=ROOT/'docs/fixtures/detector-geometry-v1'
    assets=ROOT/'modelprobe/src/main/assets'
    parent_bytes=(parent_root/'manifest.json').read_bytes()
    probe_bytes=(assets/'probes/manifest.json').read_bytes()
    assert sha(parent_bytes)==PARENT_SHA and sha(probe_bytes)==PROBE_SHA
    parent,probe=json.loads(parent_bytes),json.loads(probe_bytes)
    source_hashes={}
    for cls in [TextRecognizer,CTCLabelDecode]:
        source=Path(inspect.getsourcefile(cls))
        source_hashes[str(source).split('/site-packages/')[-1]]=sha(source.read_bytes())
    assert source_hashes=={
        'rapidocr/ch_ppocr_rec/main.py':'84b7a55a8972d14a92800b66facc73976b8d0b06bd8888e551414dbec8d6d326',
        'rapidocr/ch_ppocr_rec/utils.py':'ee16b3e71c85a8e55e572cd9b2d251f302642447fc4219d174d2092b9f3231f7'}
    # No recognizer constructor: it may resolve/download assets. Only the pinned
    # numeric resize method is used; model and full indexed dictionary are local.
    prep=TextRecognizer.__new__(TextRecognizer);prep.rec_image_shape=[3,48,320]
    files,cases={},[]
    def write(name,raw,compress=False,**metadata):
        assert name not in files and '/' not in name and 0<len(raw)<=4_718_592
        data=gzip.compress(raw,mtime=0) if compress else raw
        assert len(data)<=1_048_576
        (out/name).write_bytes(data)
        files[name]=dict(bytes=len(data),sha256=sha(data),**metadata)
        if compress:files[name].update(decodedBytes=len(raw),decodedSha256=sha(raw))
        return name
    def png(name,bgr):
        import io
        buf=io.BytesIO();Image.fromarray(bgr[:,:,::-1]).save(buf,format='PNG')
        return write(name,buf.getvalue(),width=bgr.shape[1],height=bgr.shape[0],rawBgrSha256=sha(bgr.tobytes()))
    tensors={}
    for case in parent['cases']:
        rows=[];crops=[]
        for box in case['boxes']:
            meta=parent['files'][box['crop']];data=(parent_root/box['crop']).read_bytes()
            assert len(data)==meta['bytes'] and sha(data)==meta['sha256']
            with Image.open(parent_root/box['crop']) as image:
                assert image.mode=='RGB' and image.size==(meta['width'],meta['height'])
                crop=np.asarray(image)[:,:,::-1].copy()
            assert sha(crop.tobytes())==meta['rawBgrSha256']
            assert 0<crop.shape[0]<=2048 and 0<crop.shape[1]<=4096
            crops.append(crop)
            rows.append(dict(boxId=box['id'],originalIndex=box['originalIndex'],readingOrder=box['readingOrder'],
                quad=box['quad'],detectorScore=box['detectorScore'],crop=box['crop'],
                cropSha256=meta['sha256'],cropBgrSha256=meta['rawBgrSha256'],width=meta['width'],height=meta['height'],
                rotateCounterClockwise90=box['rotateCounterClockwise90'],
                sourceToPreRotationCrop=box['sourceToPreRotationCrop']))
        assert [r['readingOrder'] for r in rows]==list(range(len(rows)))
        entry=dict(id=case['id'],status='READY' if rows else 'EMPTY_NO_INFERENCE',rows=rows,
            tensorRowToReadingOrder=[],models=[])
        if crops:
            assert len(crops)<=3
            ratios=np.array([x.shape[1]/float(x.shape[0]) for x in crops])
            order=np.argsort(ratios).tolist()
            assert order==sorted(range(len(crops)),key=lambda i:(ratios[i],i)), 'Tie order differs; do not silently remap'
            ratio=max(320/48,float(ratios.max()));width=int(48*ratio)
            assert 320<=width<=2048
            norm=[]
            for tensor_row,i in enumerate(order):
                crop=crops[i];rw=min(width,math.ceil(48*ratios[i]))
                resized=cv2.resize(crop,(rw,48),interpolation=cv2.INTER_LINEAR)
                actual=prep.resize_norm_img(crop,ratio)
                assert actual.dtype==np.float32 and actual.shape==(3,48,width)
                # Separate reconstruction verifies channel order, float operation
                # order and all positive-zero right padding against real upstream.
                expected=np.zeros((3,48,width),np.float32)
                expected[:,:,:rw]=(resized.astype(np.float32).transpose(2,0,1)/np.float32(255)-np.float32(.5))/np.float32(.5)
                assert actual.tobytes()==expected.tobytes()
                norm.append(actual)
                row=rows[i];prefix=f'{case["id"]}-row-{i}'
                argb=(np.uint32(255)<<24)|(resized[:,:,2].astype(np.uint32)<<16)|(resized[:,:,1].astype(np.uint32)<<8)|resized[:,:,0].astype(np.uint32)
                row.update(tensorRow=tensor_row,resizedWidth=rw,resizedHeight=48,
                    resized=png(prefix+'-resized.png',resized),
                    resizedArgb=write(prefix+'-resized.argbz',argb.astype('<u4').tobytes(),True))
            tensor=np.stack(norm).astype('<f4');assert np.isfinite(tensor).all() and tensor.min()>=-1 and tensor.max()<=1
            entry.update(tensorRowToReadingOrder=order,shape=list(tensor.shape),
                tensor=write(case['id']+'.f32z',tensor.tobytes(),True))
            tensors[case['id']]=tensor
        cases.append(entry)
    assert len(cases)==16 and len(tensors)==8 and sum(len(c['rows']) for c in cases)==13
    models=[]
    for model in probe['models']:
        raw=(assets/model['asset']).read_bytes();assert len(raw)==model['bytes'] and sha(raw)==model['sha256']
        rawdict=(assets/model['dictionaryAsset']).read_bytes();assert sha(rawdict)==model['dictionarySha256']
        dictionary=json.loads(rawdict);assert len(dictionary)==model['vocabularySize'] and dictionary[0]=='blank'
        decoder=CTCLabelDecode.__new__(CTCLabelDecode);decoder.character=dictionary
        options=ort.SessionOptions();options.log_severity_level=3
        options.execution_mode=ort.ExecutionMode.ORT_SEQUENTIAL
        options.graph_optimization_level=ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        options.intra_op_num_threads=options.inter_op_num_threads=1
        options.enable_cpu_mem_arena=False
        session=ort.InferenceSession(raw,sess_options=options,providers=['CPUExecutionProvider'])
        assert session.get_providers()==['CPUExecutionProvider']
        assert [x.name for x in session.get_inputs()]==['x'] and [x.name for x in session.get_outputs()]==['fetch_name_0']
        models.append(model)
        for case in cases:
            if case['status']=='EMPTY_NO_INFERENCE':continue
            result=session.run(['fetch_name_0'],{'x':tensors[case['id']]})[0]
            assert result.dtype==np.float32 and result.ndim==3 and result.shape[0]==len(case['rows'])
            assert result.shape[2]==len(dictionary) and 0<result.size<=4_000_000 and np.isfinite(result).all()
            decoded,_=decoder(result,False)
            indices=result.argmax(axis=2).astype('<i4')
            texts=[r[0] for r in decoded];reading=['']*len(texts)
            bindings=[]
            for tensor_row,reading_order in enumerate(case['tensorRowToReadingOrder']):
                row=case['rows'][reading_order];reading[reading_order]=texts[tensor_row]
                bindings.append(dict(boxId=row['boxId'],originalIndex=row['originalIndex'],readingOrder=reading_order,
                    tensorRow=tensor_row,raw=texts[tensor_row]))
            case['models'].append(dict(model=model['id'],outputShape=list(result.shape),
                argmax=write(f'{case["id"]}-{model["id"]}-argmax.i32z',indices.tobytes(),True),
                expectedHostRaw=texts,readingOrderRaw=reading,bindings=bindings))
            print(model['id'],case['id'],list(result.shape),reading,flush=True)
            del result
        del session,options,raw
    assert len(files)==50 and sum(len(c['models']) for c in cases)==16
    manifest=dict(schema=1,scope='fixed_synthetic_geometry_crops_to_two_recognizers_not_quality_or_end_to_end_detection',
        parentManifestSha256=PARENT_SHA,probeManifestSha256=PROBE_SHA,versions=VERSIONS,
        sourceHashes=source_hashes,scriptSha256=sha(Path(__file__).read_bytes()),models=models,cases=cases,files=files,
        contract=dict(height=48,baseWidth=320,maxWidth=2048,maxBatch=3,channels='BGR',layout='NCHW',
            resize='OpenCV INTER_LINEAR',normalization='float32 /255 then -0.5 then /0.5; right positive zero',
            rowOrder='pinned upstream argsort of aspect ratios; verified against stable order for these inputs only',
            empty='no resize, tensor, model run or invented text',runtime='ORT CPU sequential 1/1 threads ALL_OPT cpu arena off',
            noModelRouting=True,noTextNormalization=True))
    (out/'manifest.json').write_bytes(encoded(manifest))
    print('MANIFEST',sha((out/'manifest.json').read_bytes()),'files',len(files)+1,flush=True)

if __name__=='__main__':main()
