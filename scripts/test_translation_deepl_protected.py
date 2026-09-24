import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock
from translation_deepl import DeepLError
from translation_deepl_regression import request_body as previous_body
from translation_deepl_protected import request_body, plan, execute, bind_response


class ProtectedProtocolTest(unittest.TestCase):
    def test_frozen_plan_preserves_full_source_context_without_rubric(self):
        planned = plan()
        self.assertEqual(36,planned['calls'])
        self.assertEqual({'plain':40,'protected-money':18,'local-date':10,'withheld':4},planned['routesPerRound'])
        self.assertLessEqual(planned['maxSourceCharacters'],5000)
        for row in planned['rows']:
            if int(row['page'][1:]) < 500:
                self.assertEqual(previous_body(row['page'],row['target'])['context'],row['request']['context'])
            self.assertEqual(['keep'],row['request']['ignore_tags'])
            self.assertNotIn('criterion',json.dumps(row['request']))
        for page,target in [('p106','b004'),('p503','b005'),('p401','b003')]:
            with self.assertRaises(ValueError):request_body(page,target)

    def test_response_ownership_and_marker_failure(self):
        response={'translations':[{'text':'<segment>每月<keep id="m0">$9.95</keep></segment>','detected_source_language':'EN'}]}
        good=bind_response('p501','b003',response)
        self.assertEqual('每月$9.95',good['displayText'])
        self.assertEqual([40,420,1040,520],good['source']['bounds'])
        self.assertEqual('original-with-warning',bind_response('p501','b004',response)['state'])
        with self.assertRaises(ValueError):bind_response('p502','b003',response)
        with self.assertRaises(ValueError):bind_response('p501','b003',{'translations':response['translations']*2})

    def test_quota_and_first_http_error_stop_without_retry(self):
        client=Mock();client.usage.return_value={'character_count':999999,'character_limit':1000000}
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual('INSUFFICIENT_QUOTA',execute(Path(d),client,Mock())['error'])
            client._request.assert_not_called()
        client.usage.return_value={'character_count':0,'character_limit':1000000}
        client._request.side_effect=DeepLError('HTTP_429')
        with tempfile.TemporaryDirectory() as d:
            status=execute(Path(d),client,Mock())
            self.assertEqual('HTTP_429',status['error'])
            self.assertEqual(1,status['attempted'])
            self.assertEqual(1,client._request.call_count)

    def test_malformed_success_is_preserved_and_stops(self):
        client=Mock();client.usage.return_value={'character_count':0,'character_limit':1000000}
        client._request.return_value={'translations':[]}
        with tempfile.TemporaryDirectory() as d:
            status=execute(Path(d),client,Mock())
            self.assertEqual('stopped',status['status'])
            self.assertEqual(0,status['completed'])
            self.assertEqual({'translations':[]},json.loads((Path(d)/'responses.jsonl').read_text())['response'])
            self.assertEqual(1,client._request.call_count)


if __name__ == '__main__': unittest.main()
