import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock
from translation_candidate_c import cases as old_cases
from translation_deepl import DeepLError
from translation_deepl_plain import plain_request
from translation_deepl_regression import cases, plan, request_body, bind_response, execute


class DeepLRegressionTest(unittest.TestCase):
    def test_old_requests_unchanged_and_new_sources_are_whole_page_only(self):
        for c in old_cases():
            for target in c['targetIds']:
                if c['sourceLanguage'].startswith('zh'):
                    with self.assertRaises(ValueError):request_body(c['id'],target)
                else:self.assertEqual(plain_request(c['id'],target),request_body(c['id'],target))
        for c in cases()[-4:]:
            for target in c['targetIds']:
                body=request_body(c['id'],target)
                self.assertEqual([b['text'] for b in c['blocks']],body['context'].splitlines())
                self.assertEqual(next(b['text'] for b in c['blocks'] if b['id']==target),body['text'][0])
                self.assertNotIn('criteria',body['context'])
        frozen=plan()
        self.assertEqual(112,frozen['calls'])
        self.assertEqual(112,len({(r['round'],r['page'],r['target']) for r in frozen['rows']}))
        self.assertLessEqual(frozen['sourceCharacters'],5000)

    def test_new_response_binds_only_its_known_target(self):
        response={'translations':[{'text':'停车位','detected_source_language':'FR'}]}
        bound=bind_response('p403','b003',response)
        self.assertEqual('Place',bound['source']['text'])
        self.assertEqual([40,330,1040,430],bound['source']['bounds'])
        for page,target in [('p403','unknown'),('invented','b003')]:
            with self.assertRaises(ValueError):bind_response(page,target,response)
        with self.assertRaises(ValueError):bind_response('p401','b003',response)
        with self.assertRaises(ValueError):bind_response('p403','b003',{'translations':response['translations']*2})

    def test_quota_refusal_and_http_failure_never_continue(self):
        client=Mock();client.usage.return_value={'character_count':999999,'character_limit':1000000}
        with tempfile.TemporaryDirectory() as folder:
            self.assertEqual('INSUFFICIENT_QUOTA',execute(Path(folder),client,Mock())['error'])
            client._request.assert_not_called()
        client.usage.return_value={'character_count':0,'character_limit':1000000}
        client._request.side_effect=DeepLError('HTTP_429')
        with tempfile.TemporaryDirectory() as folder:
            self.assertEqual(0,execute(Path(folder),client,Mock())['completed'])
            self.assertEqual(1,client._request.call_count)
            self.assertEqual([],list(Path(folder).glob('response-*.json')))


if __name__=='__main__':unittest.main()
