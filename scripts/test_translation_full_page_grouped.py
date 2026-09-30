import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import translation_full_page_grouped as m
from test_translation_full_page_recorded import FakeClient


class LayoutTests(unittest.TestCase):
    def page(self): return copy.deepcopy(m.v1.inputs()['pages'][1])

    def pair(self):
        page=self.page(); keys=['s0/c3-r3-f3','s0/c2-r2-f2']
        raw=[c for c in page['ocr']['rawCandidates'] if m.stable_key(c['id']) in keys]
        ids={c['id'] for c in raw}
        page['ocr']['rawCandidates']=raw
        page['ocr']['association']['groups']=[g for g in page['ocr']['association']['groups'] if set(g['memberIds'])<=ids]
        return page

    def test_archive_top_bottom_and_middle_four_unchanged(self):
        data=m.inputs();original=m.v1.inputs()
        self.assertEqual(53,sum(len(p['ocr']['rawCandidates']) for p in data['pages']))
        for page,old in zip(data['pages'],original['pages']):
            self.assertEqual(old['ocr'],page['ocr'])
            self.assertEqual(m.v1._canonical_fields(old),page['canonicalFields'][:len(m.v1._canonical_fields(old))])
        fr=data['pages'][1];layout=fr['layout']
        self.assertEqual([True,False,True],[g['eligible'] for g in layout['groups']])
        self.assertEqual([2,4,2],[len(g['memberKeys']) for g in layout['groups']])
        self.assertEqual(20,len(layout['context'].splitlines()))
        self.assertIn('Pnx totar',layout['context'])
        self.assertTrue(layout['orderUncertain'])
        self.assertTrue(all('NON_AXIS_ALIGNED' in next(g['reasons'] for g in fr['ocr']['association']['groups'] if c['id'] in g['memberIds'])
            for c in fr['ocr']['rawCandidates'] if c['rawText'].startswith('Aucun')))
        self.assertEqual([],data['pages'][0]['layout']['groups'])

    def test_spatial_columns_gap_competitor_intervening_clip_duplicate_and_tilt(self):
        def mutate_quad(c,dx=0,dy=0): c['pageQuad']=[[x+dx,y+dy] for x,y in c['pageQuad']]
        for mode in ('column','gap','intervening','duplicate','overlap','clip','tilt','unsupported'):
            page=self.pair();raw=page['ocr']['rawCandidates'];a=next(c for c in raw if m.head(c['rawText'],'fr'));b=next(c for c in raw if m.tail(c['rawText']))
            if mode=='column': mutate_quad(b,dx=100)
            if mode=='gap': mutate_quad(b,dy=60)
            if mode=='tilt': a['pageQuad'][1][1]+=20
            if mode in ('clip','unsupported'): page['ocr']['association']['groups'][0]['reasons']=[{'clip':'POSSIBLE_CLIP','unsupported':'UNSUPPORTED_GEOMETRY'}[mode]]
            if mode in ('intervening','duplicate','overlap'):
                c=copy.deepcopy(b);c['id']=c['id'].rsplit('/',1)[0]+'/c99-r99-f99'
                if mode!='duplicate': c['rawText']='other'
                if mode=='intervening': c['pageQuad']=[[70,313],[100,313],[100,320],[70,320]]
                raw.append(c);page['ocr']['association']['groups'].append({'memberIds':[c['id']],'text':'SINGLE','geometry':'ISOLATED','reasons':[]})
            with self.subTest(mode=mode): self.assertFalse(any(g['eligible'] for g in m.derive_layout(page)['groups']))

    def test_geometry_frozen_boundaries_and_order_verification(self):
        c=self.pair()['ocr']['rawCandidates'][0]
        for q,expected in [([[0,0],[100,2],[100,32],[0,30]],True),([[0,0],[100,2.001],[100,32],[0,30]],False),
                           ([[0,0],[100,0],[101.5,30],[1.5,30]],True),([[0,0],[100,0],[101.501,30],[1.5,30]],False),
                           ([[0,0],[100,30],[100,0],[0,30]],False),([[100,0],[0,0],[0,30],[100,30]],False)]:
            c['pageQuad']=q;self.assertEqual(expected,m.near_horizontal(c))
        a=copy.deepcopy(c);b=copy.deepcopy(c)
        a['pageQuad']=[[0,0],[100,0],[100,40],[0,40]]
        b['pageQuad']=[[20,70],[120,70],[120,110],[20,110]]
        self.assertTrue(m.adjacent(a,b))
        b['pageQuad'][0][0]+=0.001;b['pageQuad'][3][0]+=0.001
        self.assertFalse(m.adjacent(a,b))

    def test_id_renaming_and_input_permutation_do_not_choose_winner(self):
        page=self.page();expected=m.derive_layout(page)
        renamed=copy.deepcopy(page);mapping={c['id']:c['id'].replace('/c','/c9').replace('-r','-r9').replace('-f','-f9') for c in renamed['ocr']['rawCandidates']}
        for c in renamed['ocr']['rawCandidates']:c['id']=mapping[c['id']]
        for g in renamed['ocr']['association']['groups']:g['memberIds']=[mapping[i] for i in g['memberIds']]
        changed=m.derive_layout(renamed)
        self.assertEqual(expected['context'],changed['context'])
        self.assertEqual([g['eligible'] for g in expected['groups']],[g['eligible'] for g in changed['groups']])
        page['ocr']['rawCandidates'].reverse();self.assertEqual(expected,m.derive_layout(page))

    def test_unsupported_heads_and_lone_tails_never_requested(self):
        for source in ('Aucun remboursement après seulement','Aucun remboursement sauf après','Aucun remboursement après.'):
            page=self.pair()
            next(c for c in page['ocr']['rawCandidates'] if m.head(c['rawText'],'fr'))['rawText']=source
            self.assertEqual([],m.derive_layout(page)['targets'])


class HostTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='kandong-grouped-');self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)

    def complete(self,client=None):
        out=self.root/'run';m.new_run(out);client=client or FakeClient();pauses=[]
        self.assertEqual('completed',m.execute(out,client,sleep=pauses.append)['status'])
        self.assertEqual(10,len(client.calls));self.assertEqual(2,client.usage_calls);self.assertEqual([2]*9,pauses)
        return out

    def review(self,out):
        review=m.review_template(out);review['reviewer']='offline test only'
        for r in review['entries']:r.update(verdict='uncertain',sourceQuality='uncertain',contextDefects='Not evaluated',notes='Not a supplier result')
        path=self.root/'review.json';m.save(path,review);return path,review

    def test_frozen_plan_exact_budget_and_context(self):
        p=m.plan();self.assertEqual(10,p['calls']);self.assertEqual({m.AUTHORED:8,m.OCR:2},p['countsByTrack']);self.assertLessEqual(p['maxSourceCharacters'],1000)
        for r in p['rows']:
            self.assertEqual(r['sourceText'],r['request']['text'][0]);self.assertEqual(r['context'],r['request']['context'])
            self.assertEqual('nonewlines',r['request'].get('split_sentences'))
            self.assertNotRegex(json.dumps(r['request'],ensure_ascii=False),r'[\u4e00-\u9fff]')
            if r['track']==m.OCR:self.assertEqual(20,len(r['context'].splitlines()))
        self.assertEqual({'en','fr'},{r['language'] for r in p['rows']})

    def test_dry_run_no_credentials_output_reuse_refused(self):
        with patch('deepl_credentials.load_key',side_effect=AssertionError('credential read')):
            self.assertEqual(0,m.main(['--out',str(self.root/'dry')]))
            self.assertEqual(1,m.main(['--out',str(self.root/'dry')]))

    def test_first_failure_no_retry_partial_export_resume_refused(self):
        out=self.root/'run';m.new_run(out);client=FakeClient(failure={'translations':[]})
        self.assertEqual('stopped',m.execute(out,client,sleep=lambda _:self.fail())['status'])
        self.assertEqual(1,len(client.calls));self.assertEqual(1,client.usage_calls)
        self.assertEqual({'translations':[]},m.read(out/'response-00.json'))
        with self.assertRaisesRegex(ValueError,'PARTIAL_RUN'):m.review_template(out)
        with self.assertRaisesRegex(ValueError,'RESUME_REFUSED'):m.execute(out,client)

    def test_four_page_export_is_exact_and_all_echoes_withheld(self):
        out=self.complete();path,_=self.review(out);packet=m.export_packet(out,path)
        self.assertFalse(packet['semanticVerified']);self.assertFalse(packet['qualityAccepted']);self.assertEqual(4,len(packet['pages']))
        for p in packet['pages']:
            self.assertEqual(p['targetKeys'],[o['key'] for o in p['outcomes']])
            self.assertEqual(m.canonical_fields(p['id']),p['canonicalFields'])
            for o in p['outcomes']:
                self.assertEqual('KEEP_ORIGINAL',o['kind']);self.assertIsNone(o['chinese'])
                self.assertEqual('ALREADY_CHINESE' if p['language'].startswith('zh') else ('CHECK_UNVERIFIED' if len(o['memberKeys'])==2 else 'NO_RECORDED_RESULT'),o['reason'])

    def test_only_correct_reviewed_rule_pass_ocr_can_enter_asset(self):
        class Chinese(FakeClient):
            def _request(self,path,body):
                reply=super()._request(path,body)
                reply['translations'][0]['text']='确认后不能退款。'
                return reply
        out=self.complete(Chinese());path,review=self.review(out)
        for r in review['entries']:
            if r['track']==m.AUTHORED:r.update(verdict='pass',sourceQuality='correct')
        m.save(path,review);self.assertFalse(any(o['chinese'] for p in m.export_packet(out,path)['pages'] for o in p['outcomes']))
        selected=next(r for r in review['entries'] if r['track']==m.OCR);selected.update(verdict='pass',sourceQuality='incorrect')
        m.save(path,review);self.assertFalse(any(o['chinese'] for p in m.export_packet(out,path)['pages'] for o in p['outcomes']))
        selected['sourceQuality']='correct';m.save(path,review)
        candidates=[o for p in m.export_packet(out,path)['pages'] for o in p['outcomes'] if o['chinese']]
        self.assertEqual(1,len(candidates));self.assertEqual(selected['key'],candidates[0]['key']);self.assertEqual(2,len(candidates[0]['memberKeys']))

    def test_review_source_members_track_response_hashes_and_run_tamper_rejected(self):
        out=self.complete();path,review=self.review(out)
        for key,value in [('memberKeys',['other']),('track',m.OCR),('rawResponseSha256','0'*64),('requestSha256','0'*64),('sourceQuality',None)]:
            bad=copy.deepcopy(review);bad['entries'][0][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):m.validate_review(out,bad)
        bad=copy.deepcopy(review);bad['runSha256']='0'*64
        with self.assertRaises(ValueError):m.validate_review(out,bad)
        (out/'response-00.json').write_bytes((out/'response-00.json').read_bytes()+b' ')
        with self.assertRaises(ValueError):m.export_packet(out,path)

    def test_input_source_and_order_hash_changes_rejected(self):
        page=copy.deepcopy(m.inputs()['pages'][1]);fields=m.grouped_fields(page)
        page['ocr']['rawCandidates'][0]['rawText']+='!';self.assertNotEqual(fields,m.grouped_fields(page))
        out=self.complete();hashes=m.read(out/'source-hashes.json');hashes['scripts/translation_grouped_checks.py']='0'*64;m.save(out/'source-hashes.json',hashes)
        with self.assertRaisesRegex(ValueError,'SOURCE_HASHES_CHANGED'):m.review_template(out)


if __name__=='__main__': unittest.main()
