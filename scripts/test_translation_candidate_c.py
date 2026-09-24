import json
import unittest
from translation_candidate_c import cases,request_body,validate
class CompactProtocolTest(unittest.TestCase):
    def test_complete_context_and_source_are_bound_before_model(self):
        for c in cases():
            data=json.loads(request_body(c,True)['messages'][1]['content'])
            self.assertEqual(data['screen']['blocks'],c['blocks'])
            self.assertEqual(data['targets'],{b['id']:b['text'] for b in c['blocks'] if b['id'] in c['targetIds']})
            self.assertNotIn('criteria',data)
    def test_response_cannot_change_source_or_geometry(self):
        c=cases()[0];r=validate(c,'{"b005":"预订"}')[0]
        self.assertEqual(r['source']['text'],'Book');self.assertEqual(r['source']['bounds'],c['blocks'][-1]['bounds'])
    def test_missing_unknown_duplicate_and_nested_values_rejected(self):
        for raw in ['{}','{"bad":"书"}','{"b005":"书","b005":"预订"}','{"b005":{"source":"Book","text":"预订"}}']:
            with self.assertRaises(ValueError):validate(cases()[0],raw)
    def test_uncertainty_and_currency_issues_are_preserved(self):
        self.assertEqual(validate(cases()[0],'{"b005":null}')[0]['status'],'uncertain')
        c=cases()[2];raw={i:next(b['text'] for b in c['blocks'] if b['id']==i) for i in c['targetIds']};raw['b004']='每晚120.00元'
        self.assertEqual(validate(c,json.dumps(raw))[0]['literalIssues'],['missing-currency-symbol'])
    def test_schema_contains_only_target_ids_not_reference_answers(self):
        r=request_body(cases()[0],True);self.assertEqual(r['format']['properties'],{'b005':{'type':['string','null']}})
    def test_fresh_counts(self):
        data=cases();self.assertEqual(len(data),24)
        self.assertEqual(sum(len(c['targetIds']) for c in data if not c['sourceLanguage'].startswith('zh')),48)
if __name__=='__main__':unittest.main()
