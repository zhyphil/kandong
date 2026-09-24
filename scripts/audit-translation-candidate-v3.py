#!/usr/bin/env python3
"""Check frozen local candidate evidence, including failures. Semantic review is separate."""
import argparse
import gzip
import hashlib
import json
import math
import statistics
from pathlib import Path
from translation_probe import ROOT,preserve_chinese
from translation_candidate_v3 import MANIFEST,cases,request_body,validate

def audit(folder,protocol="v3"):
    if protocol=="C":
        from translation_candidate_c import MANIFEST,cases,request_body,validate
    elif protocol=="D":
        from translation_candidate_d import MANIFEST,cases,request_body,validate
    else:
        from translation_candidate_v3 import MANIFEST,cases,request_body,validate
    m=json.loads((folder/'metadata.json').read_text());thinking=m['thinking']
    for name,digest in m['sourceHashes'].items():
        assert hashlib.sha256((ROOT/'scripts'/name).read_bytes()).hexdigest()==digest, name
    records=[json.loads(line) for line in gzip.decompress((folder/'responses.jsonl.gz').read_bytes()).splitlines()]
    expected={(round_id,c['id'],mode) for round_id in (1,2) for c in cases()
              for mode in (['original'] if c['sourceLanguage'].startswith('zh') else ['full'] if thinking else ['full','target-only'])}
    actual={(r['round'],r['page'],r['mode']) for r in records}
    assert len(actual)==len(records) and actual<=expected
    source={c['id']:c for c in cases()};pairs={};times=[];loaded=[];errors={};unchanged=0;literal=0;original=0;thought=[]
    for r in records:
        c=source[r['page']];assert r['thinking']==thinking
        if not r['modelCalled']:
            assert r['mode']=='original' and r['bound']==preserve_chinese(c) and 'request' not in r
            original+=len(r['bound']);continue
        assert r['request']==request_body(c,r['mode']=='full',thinking)
        times.append(r['seconds']);res=r.get('response')
        if res:
            thought.append(len(res['message'].get('thinking','')))
            assert res['prompt_eval_count']+res['eval_count']<=r['request']['options']['num_ctx']
            pairs.setdefault((r['page'],r['mode']),[]).append(res['message']['content'])
            if not res['done'] or res.get('done_reason')!='stop':
                assert r['error']=='ValueError: Incomplete generation' and 'bound' not in r
            else:
                try:bound=validate(c,res['message']['content'])
                except ValueError as e:assert r['error']==f'ValueError: {e}' and 'bound' not in r
                else:
                    assert r.get('error') is None and r['bound']==bound
                    literal+=sum(bool(b['literalIssues']) for b in bound)
                    unchanged+=sum(b['text']==b['source']['text'] for b in bound)
        else:assert r.get('error') and 'bound' not in r
        if r.get('error'):errors[r['error']]=errors.get(r['error'],0)+1
        for model in r['loadedModel']['models']:
            assert model['digest']==MANIFEST and model['context_length']==8192
            loaded.append(model['size_vram'])
    after=json.loads((folder/'after-unload.json').read_text());assert after=={'models':[]}
    repeated=[v for v in pairs.values() if len(v)==2]
    return dict(complete=actual==expected,records=len(records),expectedRecords=len(expected),modelCalls=len(times),
        errors=errors,unchangedForeignTargets=unchanged,literalFlags=literal,chineseOriginalTargets=original,
        pairedResponses=len(repeated),identicalResponsePairs=sum(v[0]==v[1] for v in repeated),
        thinkingReturnedCalls=sum(x>0 for x in thought),medianSeconds=statistics.median(times),
        p95Seconds=sorted(times)[math.ceil(.95*len(times))-1],maxSeconds=max(times),
        runtimeReportedLoadedBytes=max(loaded,default=0),modelUnloaded=True,
        semanticQuality='Not inferred from structural/number checks; see manual-review.json')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('folder',type=Path);p.add_argument('--protocol',choices=['v3','C','D'],default='v3');a=p.parse_args();print(json.dumps(audit(a.folder,a.protocol),indent=2))
