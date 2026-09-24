from pathlib import Path
import json
import re
import sys

source=Path(sys.argv[1])
groups={}
for line in source.read_text().splitlines():
    match=re.match(r'I/KDCaptureLab\(\s*(\d+)\): (\{.*\})$',line)
    if not match: continue
    data=json.loads(match.group(2)); key=(int(match.group(1)),data['session'])
    groups.setdefault(key,[]).append(data)
ordered=['BASELINE','COVER','SECURE_COVER','HIDE','RESTORE','SECURE_ACTIVITY','PUBLIC_RETURN']
reports=[]
for (pid,session), events in groups.items():
    phases=[e for e in events if e['event']=='PHASE']
    ends=[e for e in events if e['event']=='END']
    if not ends: continue
    assert len(ends)==1
    end=ends[0]
    controls=[e for e in phases if e['phase'] not in ('SECURE_COVER','SECURE_ACTIVITY')]
    complete=[e['phase'] for e in phases]==ordered and end['reason']=='COMPLETED'
    passed=(complete and all(e['verdict']=='CURRENT_CONFIRMED' and e['streak']>=3 for e in controls)
        and phases[2]['verdict'] in ('BLACK_OBSERVED','UNDERLYING_OBSERVED')
        and phases[5]['verdict']=='BLACK_OBSERVED'
        and all(e['streak']>=3 for e in phases)
        and end.get('coverControls')=='CONTROLLED_OBSERVATION'
        and end.get('activityControls')=='CONTROLLED_OBSERVATION'
        and end['displays']==1 and end['acquired']==end['closed'])
    reports.append(dict(pid=pid,session=session,sequence_completed=complete,
        owned_fixture_checks_passed=passed, phases=phases, end=end))
target=source.with_suffix('.audit.json')
target.write_text(json.dumps(reports,indent=2)+'\n')
for r in reports:
    print(json.dumps({k:v for k,v in r.items() if k not in ('phases','end')},ensure_ascii=False),
          r['end']['reason'],r['end']['acquired'],r['end']['closed'])
