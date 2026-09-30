import copy
import unittest
from unittest.mock import patch
import semantic_context_eval as m

class SemanticEvaluationTests(unittest.TestCase):
    def base(self,id='en-trailing-exception'):
        p=next(p for p in m.load()['pages'] if p['id']==id)
        obs={'id':id,'language':p['language'],'track':'AUTHORED_CANDIDATES_NOT_OCR','corpusSha256':m.CORPUS_SHA,
             'published':True,'rawFieldsPreserved':True,'orderUncertain':True,'raw':copy.deepcopy(p['items']),
             'contextLabels':[i['label'] for i in p['items']],
             'rawInContext':[dict(label=i['label'],text=i['text']) for i in p['items']], 'requestTargets':[]}
        return p,obs
    def target(self,p,labels):
        return {'labels':labels,'sourceText':' '.join(next(i['text'] for i in p['items'] if i['label']==label) for label in labels)}
    def test_oracle_is_not_given_to_kotlin(self):
        v=m.load();a=m.kotlin(v);changed=copy.deepcopy(v)
        for p in changed['pages']:p['oracle']={'anything':'changed'}
        self.assertEqual(a,m.kotlin(changed))
        self.assertNotIn('rationale',a);self.assertNotIn('completeGroups',a)
    def test_partial_pair_and_separate_exception_are_unsafe_even_with_full_context(self):
        p,o=self.base();o['requestTargets']=[self.target(p,['head','tail']),self.target(p,['exception'])]
        r=m.grade_page(p,o)
        self.assertEqual(2,len(r['unsafeTargets']));self.assertEqual([],r['conservativelyWithheldGroups'])
        self.assertEqual(1,len(r['missingCompleteGroups']))
    def test_full_condition_and_withholding_are_distinct_outcomes(self):
        p,o=self.base();o['requestTargets']=[self.target(p,['head','tail','exception'])]
        r=m.grade_page(p,o);self.assertEqual(1,len(r['correctCompleteTargets']));self.assertEqual([],r['unsafeTargets'])
        o['requestTargets']=[];r=m.grade_page(p,o);self.assertEqual(1,len(r['conservativelyWithheldGroups']))
        self.assertEqual([],r['correctCompleteTargets']);self.assertEqual([],r['unsafeTargets'])
    def test_neutral_neighbour_is_not_forced_into_refund_condition(self):
        p,o=self.base('en-neutral-neighbour');o['requestTargets']=[self.target(p,['head','tail']),self.target(p,['support'])]
        r=m.grade_page(p,o);self.assertEqual([],r['unsafeTargets']);self.assertEqual(1,r['unrelatedTargets'])
    def test_source_context_and_binding_tampering_are_rejected(self):
        for mutate in [lambda o:o['raw'].pop(),lambda o:o['raw'][1].update(text='corrected'),
                       lambda o:o['raw'][1]['quad'][0].__setitem__(0,999),lambda o:o['contextLabels'].pop(),
                       lambda o:o['rawInContext'][1].update(text='other'),lambda o:o.update(corpusSha256='bad'),
                       lambda o:o.update(rawFieldsPreserved=False),lambda o:o.update(orderUncertain=False)]:
            p,o=self.base();mutate(o)
            with self.assertRaises(ValueError):m.grade_page(p,o)
        p,o=self.base();o['requestTargets']=[self.target(p,['head','head'])]
        with self.assertRaises(ValueError):m.grade_page(p,o)
    def test_duplicate_source_deletion_and_chinese_requests_cannot_improve_score(self):
        p,o=self.base('fr-seam-exact-duplicate');o['requestTargets']=[self.target(p,['a-head','a-tail'])]
        self.assertEqual(1,len(m.grade_page(p,o)['unsafeTargets']))
        p,o=self.base('hans-source-only');o['requestTargets']=[self.target(p,['condition'])]
        with self.assertRaises(ValueError):m.grade_page(p,o)
    def test_oracle_references_and_duplicate_labels_are_validated(self):
        v=m.load();v['pages'][0]['oracle']['completeGroups']=[['head','missing']]
        with self.assertRaises(ValueError):m.validate(v)
        v=m.load();v['pages'][0]['items'][1]['label']='title'
        with self.assertRaises(ValueError):m.validate(v)
    def test_corpus_bytes_must_match_frozen_identity(self):
        with patch.object(m,'CORPUS_SHA','0'*64):
            with self.assertRaises(ValueError):m.load()

if __name__=='__main__':unittest.main()
