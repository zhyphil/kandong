import unittest
from translation_critical_checks import evaluate


class EquivalentWordingTest(unittest.TestCase):
    def test_reviewed_equivalent_wording_is_not_withheld(self):
        pairs=[
            ('Taxes included','税费包含','en'),
            ('Taxes included','税费已包含','en'),
            ('Taxes not included','税费不包含','en'),
            ('Taxes not included','税费未包含','en'),
            ('Cancel before 09:15 on 6 November 2026. No refund after renewal.','在2026年11月6日09:15之前取消。续费后无退款。','en'),
            ('No changes or refunds after departure.','离站后无更改或退款。','en'),
            ('Free cancellation until 07:05 on 20 November 2026.','免费取消至 2026 年 11 月 20 日 07:05。','en'),
            ('Non échangeable et non remboursable.','不可退换。','fr'),
            ('Only valid on 21 November 2026.','仅限2026年11月21日使用。','en'),
            ('Non échangeable et non remboursable.','不可改签，不可退票。','fr'),
            ('No changes or refunds after departure.','发车后不可更改或退款。','en'),
            ('2 heures maximum.','最长2小时。','fr'),
        ]
        for source,target,lang in pairs:
            with self.subTest(source=source,target=target):
                self.assertEqual([],evaluate(source,target,lang)['reasons'])

    def test_similar_incorrect_expressions_still_withheld(self):
        pairs=[
            ('Taxes included','税费不包含','en'),
            ('Taxes not included','税费已包含','en'),
            ('No refund after departure.','离站后可退款。','en'),
            ('No refund after departure.','离站后无条件退款。','en'),
            ('Only valid on 21 November 2026.','仅限2026年11月21日之前使用。','en'),
            ('Non échangeable et non remboursable.','可退换。','fr'),
            ('Non échangeable et non remboursable.','不可改签，可以退票。','fr'),
            ('No changes or refunds after departure.','发车前不可更改或退款。','en'),
            ('2 heures maximum.','最短2小时。','fr'),
        ]
        for source,target,lang in pairs:
            with self.subTest(source=source,target=target):
                self.assertEqual('withheld',evaluate(source,target,lang)['status'])


if __name__=='__main__':unittest.main()
