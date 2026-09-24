#!/usr/bin/env python3
"""Freeze and score new authored two-font text with the unchanged candidate v2 rule."""
import argparse, hashlib, importlib.util, importlib.metadata, inspect, json, os
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
FONTS={'arial-unicode':Path('/System/Library/Fonts/Supplemental/Arial Unicode.ttf'),
       'heiti-medium':Path('/System/Library/Fonts/STHeiti Medium.ttc')}
def sha(b):return hashlib.sha256(b).hexdigest()
def load(name,file):
    s=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('mode',choices=['freeze','run'])
    p.add_argument('--output',type=Path,required=True);p.add_argument('--fixtures',type=Path);p.add_argument('--sha256');p.add_argument('--models',type=Path)
    a=p.parse_args();out=a.output.resolve();assert out!=ROOT and ROOT not in out.parents and not out.exists();out.mkdir(parents=True)
    from PIL import Image,ImageFont
    if a.mode=='freeze':
        generator=load('generator','generate-ocr-fixtures.py');spec_path=ROOT/'docs/fixtures/ocr-candidate-holdout-v2.json'
        spec=json.loads(spec_path.read_bytes());assert len(spec['cases'])==10 and spec['scope']=='project_authored_synthetic_only'
        rows=[];fonts={}
        for family,path in FONTS.items():
            f=ImageFont.truetype(str(path),24,index=0)
            fonts[family]=dict(name=f.getname(),sha256=sha(path.read_bytes()),index=0,fontRedistributed=False)
            for case in spec['cases']+[dict(id='blank',language='none',source='',critical=[])]:
                assert all(text in case['source'] for text in case['critical'])
                for size in ([24] if case['id']=='blank' else [16,24,32]):
                    im,lines=generator.render(case['source'],ImageFont.truetype(str(path),size,index=0))
                    name=f"{family}-{case['id']}-{size}.png";im.save(out/name)
                    rows.append(dict(**case,font=family,fontPx=size,asset=name,width=im.width,height=im.height,
                        pngSha256=sha((out/name).read_bytes()),pixelSha256=sha(im.tobytes()),renderedLines=lines))
        assert len(rows)==62
        manifest=dict(schema=1,scope=spec['scope'],ruleFrozenAt='d267f63',specSha256=sha(spec_path.read_bytes()),
            generatorSha256=sha(Path(__file__).read_bytes()),pillow=importlib.metadata.version('Pillow'),fonts=fonts,images=rows)
        (out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
        print('FROZEN',len(rows),sha((out/'manifest.json').read_bytes()));return
    assert a.fixtures and a.sha256 and a.models
    fixtures=a.fixtures.resolve();models=a.models.resolve();data=(fixtures/'manifest.json').read_bytes();assert sha(data)==a.sha256
    manifest=json.loads(data);assert manifest['scope']=='project_authored_synthetic_only' and manifest['ruleFrozenAt']=='d267f63' and len(manifest['images'])==62
    shared=load('shared','screen-shared-box-candidates.py');old=load('old','screen-block-recognizer-selection.py')
    v2=load('v2','export-candidate-selection-v2.py');screen=load('screen','screen-rapidocr-fixtures.py')
    assert {k:importlib.metadata.version(k) for k in shared.VERSIONS}==shared.VERSIONS
    for name,digest in screen.MODEL_HASHES.items():assert sha((models/name).read_bytes())==digest
    os.chdir(out)
    import onnxruntime as ort
    ort.disable_telemetry_events()
    import cv2,numpy as np
    from rapidocr import LangRec,ModelType,OCRVersion,RapidOCR
    from rapidocr.main import RapidOCRError
    from rapidocr.utils.process_img import map_boxes_to_original
    cv2.setNumThreads(1);source_root=Path(inspect.getsourcefile(RapidOCR)).parent
    for name,digest in shared.SOURCES.items():assert sha((source_root/name).read_bytes())==digest
    engines={}
    for lang in [LangRec.CH,LangRec.LATIN]:
        engines[lang.value]=RapidOCR(params={
            'Global.model_root_dir':str(models),'Global.use_cls':False,'Global.text_score':0.0,'Global.log_level':'warning',
            'Det.ocr_version':OCRVersion.PPOCRV5,'Det.model_type':ModelType.MOBILE,'Det.model_path':str(models/'ch_PP-OCRv5_det_mobile.onnx'),
            'Det.limit_side_len':1280,'Det.limit_type':'max','Cls.model_path':str(models/'ch_ppocr_mobile_v2.0_cls_mobile.onnx'),
            'Rec.ocr_version':OCRVersion.PPOCRV5,'Rec.model_type':ModelType.MOBILE,'Rec.lang_type':lang,
            'Rec.model_path':str(models/(lang.value+'_PP-OCRv5_rec_mobile.onnx')),
            'EngineConfig.onnxruntime.intra_op_num_threads':1,'EngineConfig.onnxruntime.inter_op_num_threads':1})
        for part in [engines[lang.value].text_det,engines[lang.value].text_cls,engines[lang.value].text_rec]:assert part.session.session.get_providers()==['CPUExecutionProvider']
    results=[];det_calls=rec_calls=0
    for item in manifest['images']:
        assert Path(item['asset']).name==item['asset'];path=fixtures/item['asset'];assert sha(path.read_bytes())==item['pngSha256']
        im=Image.open(path).convert('RGBA');assert im.size==(item['width'],item['height']) and sha(im.tobytes())==item['pixelSha256']
        for scale in [1,2]:
            image=im if scale==1 else screen.smooth2(im);pixel_sha=sha(image.tobytes());tmp=out/'current.png';image.save(tmp)
            engine=engines['ch'];original=engine.load_img(tmp);processed,ops=engine.preprocess_img(original);det_calls+=1
            try:crops,detector=engine.detect_and_crop(processed,ops)
            except RapidOCRError as e:
                assert str(e)=='The text detection result is empty';crops=[]
            assert len(crops)<=8
            quads=[] if not crops else (map_boxes_to_original(detector.boxes.copy(),ops,original.shape[0],original.shape[1])/scale).tolist()
            crop_hashes=[sha(c.tobytes()) for c in crops];raw={}
            for key,eng in engines.items():
                texts=[] if not crops else list(eng.recognize_txt(crops).txts)
                if crops:rec_calls+=1
                assert len(texts)==len(crops) and crop_hashes==[sha(c.tobytes()) for c in crops]
                raw[key]=[dict(boxId=f'{pixel_sha}.box-{i}',quad=q,raw=text) for i,(q,text) in enumerate(zip(quads,texts,strict=True))]
            blocks=shared.associate(raw['ch'],raw['latin'],lambda ch,la:v2.decide(ch,la,old.choose))
            text='\n'.join(b['selection']['raw'] for b in blocks) if all(b['selection']['raw'] is not None for b in blocks) else None
            # Source answers, language and critical phrases enter only after inference/selection.
            normalize=old.audit.normalize;answer=normalize(item['source']);ch_text='\n'.join(r['raw'] for r in raw['ch'])
            misses=[phrase for phrase in item['critical'] if text is None or normalize(phrase) not in normalize(text)]
            row=dict(id=item['id'],font=item['font'],fontPx=item['fontPx'],scale=scale,languageForScoringOnly=item['language'],
                source=item['source'],pngSha256=item['pngSha256'],pixelSha256=pixel_sha,blocks=blocks,
                selectedRaw=text,selectedExact=text is not None and normalize(text)==answer,chExact=normalize(ch_text)==answer,
                criticalMisses=misses,criticalCount=len(item['critical']),review=text is None)
            results.append(row);print(len(results),item['asset'],scale,'exact',row['selectedExact'],'missing',misses,flush=True)
    assert len(results)==124 and det_calls==124
    groups=[]
    for font in FONTS:
        for scale in [1,2]:
            rows=[r for r in results if r['font']==font and r['scale']==scale];text=[r for r in rows if r['source']]
            groups.append(dict(font=font,scale=scale,textImages=len(text),selectedExact=sum(r['selectedExact'] for r in text),
                chExact=sum(r['chExact'] for r in text),review=sum(r['review'] for r in text),
                regressions=[f"{r['id']}-{r['fontPx']}" for r in text if r['chExact'] and not r['selectedExact']],
                literalCriticalTotal=sum(r['criticalCount'] for r in text),literalCriticalMisses=sum(len(r['criticalMisses']) for r in text),
                blankPassed=all(r['selectedRaw']=='' for r in rows if not r['source'])))
    report=dict(schema=1,scope='New authored two-font synthetic host test, not Android or general accuracy',ruleFrozenAt='d267f63',
        manifestSha256=a.sha256,scriptSha256=sha(Path(__file__).read_bytes()),models=screen.MODEL_HASHES,versions=shared.VERSIONS,
        helperSha256={name:sha((ROOT/'scripts'/name).read_bytes()) for name in ['screen-shared-box-candidates.py','export-candidate-selection-v2.py','screen-block-recognizer-selection.py','screen-rapidocr-fixtures.py']},
        detectorCalls=det_calls,recognizerCalls=rec_calls,groups=groups,results=results,
        limits=['Authored after known failure analysis, not an independent external blind study','Literal phrase presence is not semantic fidelity','No rule/model tuning on these outputs','No phone, real screen, translation or product integration'])
    (out/'quality-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2,allow_nan=False)+'\n')
    print(json.dumps(groups,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
