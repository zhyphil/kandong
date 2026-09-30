"""Offline, explicitly post-observation recheck of a verified grouped synthetic run.

There is no execution/key/network branch. Original requests/responses/checks and
their frozen code stay intact; the asset retains both original and new verdicts.
"""
import argparse
from pathlib import Path
import translation_full_page_grouped as base
import translation_grouped_checks_v2 as guard

PROTOCOL='docs/evidence/semantic-groups/2026-09-30/RECHECK_PROTOCOL.md'


def source_hashes():
    files=['scripts/translation_grouped_recheck.py','scripts/translation_grouped_checks_v2.py',
           'scripts/test_translation_grouped_checks_v2.py','scripts/test_translation_grouped_recheck.py',PROTOCOL]
    return {name:base.sha((base.ROOT/name).read_bytes()) for name in files}


def export_packet(run,review_path):
    original=base.export_packet(run,review_path)
    original_sha=base.sha(base.encoded(original)+b'\n')
    frozen,status,records=base.verified_run(run)
    review_raw=Path(review_path).read_bytes()
    reviews=base.validate_review(run,base.parse(review_raw))
    by_target={};diagnostics=[]
    for index,(row,receipt,previous) in enumerate(records):
        response=base.read(Path(run)/f'response-{index:02d}.json')
        text=response['translations'][0]['text']
        checked=guard.evaluate(row['sourceText'],text,row['language'])
        # Exact request routing and provider binding were already validated by verified_run.
        base.require(base.v1.prepare(row['sourceText'],row['language'])['route']=='plain','UNSUPPORTED_ROUTE')
        review=reviews[row['id']]
        passed=checked['status']=='candidate-unverified'
        allowed=review['sourceQuality']=='correct' and review['verdict']=='pass' and passed
        diagnostic={**base.review_identity(row,receipt),'sourceQuality':review['sourceQuality'],
                    'verdict':review['verdict'],'baseRulePassed':previous['state']=='candidate-unverified',
                    'baseReasons':previous['check']['reasons'],'recheck':checked,
                    'uiCandidate':row['track']==base.OCR and allowed,'semanticVerified':False}
        diagnostics.append(diagnostic)
        if row['track']==base.OCR:
            by_target[(row['page'],row['key'])]={
                'chinese':text if allowed else None,'kind':'CANDIDATE' if allowed else 'KEEP_ORIGINAL',
                'origin':'RECORDED_DEEPL' if allowed else 'SOURCE',
                'reason':None if allowed else 'CHECK_UNVERIFIED','rulePassed':passed,
                'guardVersion':guard.VERSION,'baseRulePassed':diagnostic['baseRulePassed']}
    # The base packet is newly constructed; never mutate archived files or parsed responses.
    for page in original['pages']:
        for outcome in page['outcomes']:
            update=by_target.get((page['id'],outcome['key']))
            if update is not None:
                base.require(len(outcome['memberKeys'])==2,'NOT_AN_OCR_PHRASE')
                outcome.update(update)
    original['baseReport']=original.pop('report')
    hashes=source_hashes()
    original['recheck']={'version':guard.VERSION,'postObservation':True,'newNetworkCalls':0,
                         'basePacketSha256':original_sha,'baseRunSha256':base.run_hash(run),
                         'sourceHashes':hashes,'sourceHashesSha256':base.sha(base.encoded(hashes)),
                         'reviewSha256':base.sha(review_raw),'qualityAccepted':False,'semanticVerified':False}
    original['report']={'diagnostics':diagnostics,'providerCompleted':len(records),
                        'baseRulePassed':sum(d['baseRulePassed'] for d in diagnostics),
                        'recheckPassed':sum(d['recheck']['status']=='candidate-unverified' for d in diagnostics),
                        'ocrUiCandidates':sum(d['uiCandidate'] for d in diagnostics),
                        'countsByTrack':frozen['countsByTrack'],'status':status,
                        'reviewKind':'DEVELOPER_POST_OBSERVATION_RECHECK_NOT_BLIND_QUALITY_ACCEPTANCE'}
    return original


def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run',type=Path,required=True);p.add_argument('--review',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True);args=p.parse_args(argv)
    try:
        base.require(not args.out.exists(),'OUTPUT_EXISTS')
        packet=export_packet(args.run,args.review);base.save(args.out,packet)
        return 0
    except (ValueError,OSError,KeyError,TypeError,AttributeError,IndexError,StopIteration):
        print('GROUPED_RECHECK_REJECTED',flush=True)
        return 1


if __name__=='__main__':raise SystemExit(main())
