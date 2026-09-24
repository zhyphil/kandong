from pathlib import Path
import json, argparse
p=argparse.ArgumentParser();p.add_argument('directory',type=Path);p.add_argument('--compare',type=Path);a=p.parse_args();root=a.directory
summary=json.loads((root/'full-page-ocr-summary.json').read_text());assert summary['technicalPassed'] and summary['technicalPassedPages']==20
rows=[];total=0;comparison=[]
keys=['modelId','rawText','stripIndex','read','core','contourIndex','rawBoxIndex','finalBoxIndex','stripReadingOrder','localQuad','pageQuad','ownsCoreCenter','recognitionInputShape','recognitionOutputShape']
for name in summary['reports']:
 d=json.loads((root/name).read_text());assert d['runId']==summary['runId'];seen=set()
 for c in d['rawCandidates']:
  assert c['id'] not in seen;seen.add(c['id'])
  for local,full in zip(c['localQuad'],c['pageQuad']):assert full==[local[0]+c['read'][0],local[1]+c['read'][1]]
  w=c['recognitionInputShape'][3];assert 320<=w<=1024
  assert c['recognitionOutputShape']==[1,(w+3)//8,d['vocabulary']]
 strips=d['strips'];assert strips[0]['core'][1]==0 and strips[-1]['core'][3]==d['layout']['height']
 for x,y in zip(strips,strips[1:]):assert x['core'][3]==y['core'][1]
 assert d['frameCloseAttempts']==d['frameClosed']==1
 total+=len(seen)
 q=d['quality'];rows.append({'model':d['modelId'],'page':d['pageFixtureId'],'expected':q['expectedLines'],'actual':len(seen),'missingExact':q['linesWithoutExactCandidate'],'duplicates':q['extraExactCandidates'],'extraOrError':len(q['fragmentOrErrorOrExtraCandidateIds']),'strict':q['strictExactPage'],'ms':d['elapsedMillis'],'moreThanThreeInStrip':d['actualStripWithMoreThanThreeCandidates']})
 if a.compare:
  old=json.loads((a.compare/name).read_text())
  before=[{k:c[k] for k in keys} for c in old['rawCandidates']];after=[{k:c[k] for k in keys} for c in d['rawCandidates']]
  comparison.append({'model':d['modelId'],'page':d['pageFixtureId'],'textAndPositionAndShapeExact':before==after,'detectorScoreBitsExact':[c['detectorScore'] for c in old['rawCandidates']]==[c['detectorScore'] for c in d['rawCandidates']]})
assert total==summary['recognitionInvocations']
assert all(r['moreThanThreeInStrip'] for r in rows if r['page']!='blank')
for n in ['full-page-ocr-lifecycle.json','full-page-ocr-session-failures.json','end-to-end-ocr-probe-report.json']:
 assert json.loads((root/n).read_text())['passed']
audit={'scope':'Independent checks of retained fixed-page inference reports; not current pixels, OCR quality, translation or exact glyph-quad acceptance.','runId':summary['runId'],'pages':20,'candidatesVerified':total,'coreCoverage':True,'sourceOffsetOnly':True,'uniqueIdsPerPage':True,'outputShapes':True,'imageClosedOncePerPage':True,'comparison':comparison}
(root/'quality-summary.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2)+'\n')
(root/'independent-host-audit.json').write_text(json.dumps(audit,indent=2)+'\n')
print(json.dumps({'pages':20,'candidates':total,'strictPages':sum(r['strict'] for r in rows),'includesBlankPages':2,'comparisonTextPositionShapeSame':sum(r['textAndPositionAndShapeExact'] for r in comparison),'comparisonScoreExact':sum(r['detectorScoreBitsExact'] for r in comparison)},ensure_ascii=False))
