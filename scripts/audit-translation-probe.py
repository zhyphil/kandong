#!/usr/bin/env python3
"""Rebuild exact request/binding evidence; never infers translation quality from JSON validity."""
import hashlib
import json
from pathlib import Path
import statistics
from translation_probe import ROOT,load_cases,request_body,bind,preserve_chinese
from translation_probe_v2 import request_body as v2_request,validate

EVIDENCE=ROOT/'docs/evidence/translation-model/2026-09-24'

def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def audit():
    first=load_cases();new=json.loads((ROOT/'docs/fixtures/translation-holdout-v2.json').read_text())['cases']
    assert sha(ROOT/'docs/fixtures/translation-holdout-v2.json')=='e3446777b269cd3fa2f8f5fe8bdc9080079b23e047b24c2d8f41a154f9229955'
    summary={}
    for revision,cases,make_request,validator in [('v1',first,request_body,bind),('v2',first+new,v2_request,validate)]:
        folder=EVIDENCE/revision
        metadata=json.loads((folder/'metadata.json').read_text())
        assert 'sha256-098cb604ff3cc846891b7e8c00abe4f52f5c6fdc936e21e7e41f2eaf22c1c7cb' in metadata['model']['modelfile']
        for filename,digest in metadata['scripts'].items():
            path=folder/'runner.py' if revision=='v1' and filename=='run-translation-probe.py' else ROOT/'scripts'/filename
            assert sha(path)==digest, ('Script identity changed', revision,filename)
        expected_files=set();times=[];complete=0;failed=0;literal=0;kept=0;uncertain=0;output_pairs={}
        for round_id in (1,2):
            for c in cases:
                for mode in (['original'] if c['sourceLanguage'].startswith('zh') else ['full','target-only']):
                    path=folder/f'r{round_id}-{c["id"]}-{mode}.json';expected_files.add(path.name)
                    row=json.loads(path.read_text())
                    assert (row['round'],row['page'],row['mode'])==(round_id,c['id'],mode)
                    if mode=='original':
                        assert row['modelCalled'] is False and 'request' not in row and 'response' not in row
                        assert row['bound']==preserve_chinese(c);kept+=len(row['bound'])
                    else:
                        assert row['modelCalled'] is True and row['request']==make_request(c,mode=='full')
                        res=row['response'];assert res['done'] and res['done_reason']=='stop'
                        assert not res['message'].get('tool_calls')
                        assert res['prompt_eval_count']+res['eval_count'] < row['request']['options']['num_ctx']
                        try:bound=validator(c,res['message']['content'])
                        except ValueError as e:
                            assert row['error']==f'ValueError: {e}' and 'bound' not in row;failed+=1
                        else:
                            assert 'error' not in row and row['bound']==bound;complete+=1
                            literal+=sum(bool(b.get('literalIssues')) for b in bound)
                            uncertain+=sum(b['text'] is None for b in bound)
                        times.append(row['elapsedSeconds'])
                        loaded=row['loadedModel']['models'];assert len(loaded)==1
                        assert loaded[0]['digest']=='ac9d6012e30ad523bc692bddb21e3345e9657362b9b067d4186abb432d797054'
                        assert loaded[0]['context_length']==8192
                        output_pairs.setdefault((c['id'],mode),[]).append(res['message']['content'])
        assert expected_files=={p.name for p in folder.glob('r[12]-*.json')}
        assert all(len(values)==2 and values[0]==values[1] for values in output_pairs.values())
        assert json.loads((folder/'after-unload.json').read_text())=={'models':[]}
        summary[revision]=dict(pages=len(cases),modelCalls=len(times),structurallyAcceptedResponses=complete,
            sourceEchoRejectedResponses=failed,literalRejectedTargetsAcrossModesAndRounds=literal,
            uncertainTargets=uncertain,chineseOriginalTargetsAcrossRounds=kept,
            responseTextIdenticalAcrossRounds=True,medianSeconds=statistics.median(times),maxSeconds=max(times),
            unloaded=True,semanticVerdict='See semantic-review.json; not inferred from technical checks')
    return summary

if __name__=='__main__':print(json.dumps(audit(),ensure_ascii=False,indent=2))
