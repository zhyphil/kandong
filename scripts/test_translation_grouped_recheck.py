"""Offline fake transport tests; echoed sample text is not a quality result."""
import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import translation_full_page_grouped as base
import translation_grouped_recheck as m
from test_translation_full_page_recorded import FakeClient


class WrappedClient(FakeClient):
    def _request(self,path,body):
        result=super()._request(path,body)
        result['translations'][0]['text']='确认后\n恕不退款。'
        return result


class RecheckTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='kandong-recheck-');self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);self.run=self.root/'run'
        base.new_run(self.run)
        self.assertEqual('completed',base.execute(self.run,WrappedClient(),sleep=lambda _:None)['status'])
        self.review=base.review_template(self.run);self.review['reviewer']='offline fake fixture'
        for item in self.review['entries']:
            item.update(sourceQuality='correct',verdict='pass',contextDefects='Not evaluated',notes='Fake transport only')
        self.path=self.root/'review.json';base.save(self.path,self.review)

    def test_recheck_keeps_original_failures_and_exact_raw_response_without_network(self):
        before={p.name:p.read_bytes() for p in self.run.iterdir()}
        with patch('deepl_credentials.load_key',side_effect=AssertionError('credential read')):
            packet=m.export_packet(self.run,self.path)
        self.assertEqual(before,{p.name:p.read_bytes() for p in self.run.iterdir()})
        original=base.export_packet(self.run,self.path)
        self.assertEqual(base.sha(base.encoded(original)+b'\n'),packet['recheck']['basePacketSha256'])
        self.assertTrue(packet['recheck']['postObservation']);self.assertEqual(0,packet['recheck']['newNetworkCalls'])
        outputs=[o for p in packet['pages'] for o in p['outcomes'] if o['chinese']]
        self.assertEqual(2,len(outputs));self.assertTrue(all(o['chinese']=='确认后\n恕不退款。' for o in outputs))
        self.assertTrue(all(len(o['memberKeys'])==2 and not o['baseRulePassed'] and o['rulePassed'] for o in outputs))
        self.assertEqual(0,packet['report']['baseRulePassed'])
        self.assertFalse(packet['qualityAccepted']);self.assertFalse(packet['semanticVerified'])

    def test_source_or_review_failure_never_becomes_candidate(self):
        rows=[r for r in self.review['entries'] if r['track']==base.OCR]
        rows[0]['sourceQuality']='incorrect';rows[1]['verdict']='uncertain';base.save(self.path,self.review)
        self.assertFalse(any(o['chinese'] for p in m.export_packet(self.run,self.path)['pages'] for o in p['outcomes']))

    def test_corrupted_base_response_review_and_derived_input_reject(self):
        bad=copy.deepcopy(self.review);bad['entries'][-1]['memberKeys'].reverse();base.save(self.path,bad)
        with self.assertRaises(ValueError):m.export_packet(self.run,self.path)
        base.save(self.path,self.review)
        (self.run/'response-00.json').write_bytes((self.run/'response-00.json').read_bytes()+b' ')
        with self.assertRaises(ValueError):m.export_packet(self.run,self.path)

    def test_output_reuse_refused(self):
        dest=self.root/'packet.json';dest.write_text('keep')
        self.assertEqual(1,m.main(['--run',str(self.run),'--review',str(self.path),'--out',str(dest)]))
        self.assertEqual('keep',dest.read_text())


if __name__=='__main__':unittest.main()
