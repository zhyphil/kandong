import copy
import json
import unittest
from unittest.mock import patch
from translation_probe import load_cases, payload, request_body, bind, preserve_chinese

class TranslationProbeTest(unittest.TestCase):
    def setUp(self): self.cases=load_cases(); self.case=self.cases[0]
    def response(self, rows): return json.dumps(dict(translations=rows))
    def test_full_page_and_target_only_differ_only_in_available_evidence(self):
        for c in self.cases[:6]:
            full=payload(c,True); local=payload(c,False)
            self.assertEqual(full['blocks'],c['blocks'])
            self.assertEqual({b['id'] for b in local['blocks']},set(c['targetIds']))
            self.assertEqual(full['targetIds'],local['targetIds'])
    def test_no_expected_answers_case_name_or_roi_in_model_input(self):
        c=copy.deepcopy(self.case);c.update(expected='secret',scenario='hotel',criteria='leak')
        request=request_body(c,True);data=json.loads(request['messages'][1]['content'])
        self.assertEqual(set(data),{'sourceLanguage','targetLanguage','viewport','blocks','groups','targetIds'})
        self.assertNotIn('secret',json.dumps(request));self.assertNotIn('leak',json.dumps(request))
    def test_binding_uses_source_geometry_even_when_response_reordered(self):
        c=self.cases[2]; rows=[dict(id=i,text='占位') for i in reversed(c['targetIds'])]
        bound=bind(c,self.response(rows))
        for row in bound: self.assertEqual(row['source'],next(b for b in c['blocks'] if b['id']==row['id']))
        bound[0]['source']['bounds'][0]=999
        self.assertNotEqual(c['blocks'][-1]['bounds'][0],999)
    def test_duplicate_missing_extra_or_unknown_ids_rejected(self):
        for rows in ([],[dict(id='bad',text='x')],[dict(id='b005',text='x')]*2):
            with self.assertRaises(ValueError): bind(self.case,self.response(rows))
        c=self.cases[2]; rows=[dict(id=i,text='x') for i in c['targetIds']];rows[-1]=rows[0]
        with self.assertRaises(ValueError): bind(c,self.response(rows))
    def test_duplicate_json_keys_and_model_geometry_rejected(self):
        for raw in ('{"translations":[],"translations":[]}', self.response([dict(id='b005',text='x',bounds=[1,2,3,4])])):
            with self.assertRaises(ValueError): bind(self.case,raw)
    def test_uncertainty_preserved_invalid_text_rejected(self):
        self.assertEqual(bind(self.case,self.response([dict(id='b005',text=None)]))[0]['status'],'uncertain')
        for value in ('',4,[],True,'x'*2001):
            with self.assertRaises(ValueError): bind(self.case,self.response([dict(id='b005',text=value)]))
    def test_chinese_is_exact_and_never_model_translation(self):
        with patch('translation_probe.local_api',side_effect=AssertionError('No model for Chinese')):
            for c in self.cases[6:]:
                for row in preserve_chinese(c):
                    self.assertEqual(row['text'],row['source']['text']);self.assertEqual(row['status'],'original')
    def test_payload_does_not_alias_source(self):
        x=payload(self.case,True);x['blocks'][0]['bounds'][0]=999;x['targetIds'].clear()
        self.assertEqual(self.case['blocks'][0]['bounds'][0],40);self.assertEqual(len(self.case['targetIds']),1)

if __name__=='__main__':unittest.main()
