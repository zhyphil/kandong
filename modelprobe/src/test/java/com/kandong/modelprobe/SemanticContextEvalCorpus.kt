package com.kandong.modelprobe

/** Generated INPUTS only. Authored candidate geometry, never model output; no semantic oracle here. */
internal object SemanticContextEvalCorpus {
    const val SHA="4ace88c9a391cd73826ff7e369f024a4c106c9aa0dcf5e3529676f4d496c29b6"
    data class Item(val label:String,val text:String,val strip:Int,val quad:List<GeometryProbeContract.Point>,val score:Double)
    data class Page(val id:String,val language:String,val width:Int,val height:Int,val items:List<Item>)
    fun pages():List<Page> = listOf(
        Page("en-shifted-before","en",1200,2400,listOf(
            Item("title","Booking details",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("head","No refunds before",0,listOf(GeometryProbeContract.Point(140.0,350.0),GeometryProbeContract.Point(610.0,350.0),GeometryProbeContract.Point(610.0,390.0),GeometryProbeContract.Point(140.0,390.0)),0.9),
            Item("tail","confirmation.",0,listOf(GeometryProbeContract.Point(142.0,406.0),GeometryProbeContract.Point(382.0,406.0),GeometryProbeContract.Point(382.0,446.0),GeometryProbeContract.Point(142.0,446.0)),0.9),
        )),
        Page("en-bottom-positive","en",1200,2400,listOf(
            Item("title","Booking details",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("head","Refunds are available after",3,listOf(GeometryProbeContract.Point(170.0,2050.0),GeometryProbeContract.Point(640.0,2050.0),GeometryProbeContract.Point(640.0,2090.0),GeometryProbeContract.Point(170.0,2090.0)),0.9),
            Item("tail","confirmation.",3,listOf(GeometryProbeContract.Point(172.0,2106.0),GeometryProbeContract.Point(412.0,2106.0),GeometryProbeContract.Point(412.0,2146.0),GeometryProbeContract.Point(172.0,2146.0)),0.9),
        )),
        Page("fr-accent-after","fr",1200,2400,listOf(
            Item("title","Conditions de réservation",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("head","Aucun remboursement après",1,listOf(GeometryProbeContract.Point(110.0,780.0),GeometryProbeContract.Point(580.0,780.0),GeometryProbeContract.Point(580.0,820.0),GeometryProbeContract.Point(110.0,820.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(112.0,836.0),GeometryProbeContract.Point(352.0,836.0),GeometryProbeContract.Point(352.0,876.0),GeometryProbeContract.Point(112.0,876.0)),0.9),
        )),
        Page("fr-right-column-before","fr",1200,2400,listOf(
            Item("title","Conditions de réservation",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("head","Remboursement possible avant",2,listOf(GeometryProbeContract.Point(590.0,1420.0),GeometryProbeContract.Point(1010.0,1420.0),GeometryProbeContract.Point(1010.0,1460.0),GeometryProbeContract.Point(590.0,1460.0)),0.9),
            Item("tail","confirmation.",2,listOf(GeometryProbeContract.Point(592.0,1476.0),GeometryProbeContract.Point(832.0,1476.0),GeometryProbeContract.Point(832.0,1516.0),GeometryProbeContract.Point(592.0,1516.0)),0.9),
        )),
        Page("en-two-columns","en",1200,2400,listOf(
            Item("title","Booking details",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("left-head","No refunds after",0,listOf(GeometryProbeContract.Point(45.0,320.0),GeometryProbeContract.Point(515.0,320.0),GeometryProbeContract.Point(515.0,360.0),GeometryProbeContract.Point(45.0,360.0)),0.9),
            Item("left-tail","confirmation.",0,listOf(GeometryProbeContract.Point(47.0,376.0),GeometryProbeContract.Point(287.0,376.0),GeometryProbeContract.Point(287.0,416.0),GeometryProbeContract.Point(47.0,416.0)),0.9),
            Item("right-head","Refunds are available before",0,listOf(GeometryProbeContract.Point(590.0,320.0),GeometryProbeContract.Point(1010.0,320.0),GeometryProbeContract.Point(1010.0,360.0),GeometryProbeContract.Point(590.0,360.0)),0.9),
            Item("right-tail","confirmation.",0,listOf(GeometryProbeContract.Point(592.0,376.0),GeometryProbeContract.Point(832.0,376.0),GeometryProbeContract.Point(832.0,416.0),GeometryProbeContract.Point(592.0,416.0)),0.9),
        )),
        Page("fr-seam-exact-duplicate","fr",1200,2400,listOf(
            Item("title","Conditions de réservation",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("a-head","Aucun remboursement après",1,listOf(GeometryProbeContract.Point(95.0,1150.0),GeometryProbeContract.Point(565.0,1150.0),GeometryProbeContract.Point(565.0,1186.0),GeometryProbeContract.Point(95.0,1186.0)),0.9),
            Item("a-tail","confirmation.",1,listOf(GeometryProbeContract.Point(97.0,1198.0),GeometryProbeContract.Point(337.0,1198.0),GeometryProbeContract.Point(337.0,1234.0),GeometryProbeContract.Point(97.0,1234.0)),0.9),
            Item("b-head","Aucun remboursement après",2,listOf(GeometryProbeContract.Point(95.0,1150.0),GeometryProbeContract.Point(565.0,1150.0),GeometryProbeContract.Point(565.0,1186.0),GeometryProbeContract.Point(95.0,1186.0)),0.9),
            Item("b-tail","confirmation.",2,listOf(GeometryProbeContract.Point(97.0,1198.0),GeometryProbeContract.Point(337.0,1198.0),GeometryProbeContract.Point(337.0,1234.0),GeometryProbeContract.Point(97.0,1234.0)),0.9),
        )),
        Page("en-seam-drift-duplicate","en",1200,2400,listOf(
            Item("title","Booking details",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("a-head","No refunds after",1,listOf(GeometryProbeContract.Point(100.0,1150.0),GeometryProbeContract.Point(570.0,1150.0),GeometryProbeContract.Point(570.0,1186.0),GeometryProbeContract.Point(100.0,1186.0)),0.9),
            Item("a-tail","confirmation.",1,listOf(GeometryProbeContract.Point(102.0,1198.0),GeometryProbeContract.Point(342.0,1198.0),GeometryProbeContract.Point(342.0,1234.0),GeometryProbeContract.Point(102.0,1234.0)),0.9),
            Item("b-head","No refunds after",2,listOf(GeometryProbeContract.Point(101.0,1151.0),GeometryProbeContract.Point(571.0,1151.0),GeometryProbeContract.Point(571.0,1187.0),GeometryProbeContract.Point(101.0,1187.0)),0.4),
            Item("b-tail","confirmation.",2,listOf(GeometryProbeContract.Point(103.0,1199.0),GeometryProbeContract.Point(343.0,1199.0),GeometryProbeContract.Point(343.0,1235.0),GeometryProbeContract.Point(103.0,1235.0)),0.7),
        )),
        Page("fr-seam-polarity-conflict","fr",1200,2400,listOf(
            Item("title","Conditions de réservation",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("a-head","Aucun remboursement après",1,listOf(GeometryProbeContract.Point(95.0,1150.0),GeometryProbeContract.Point(565.0,1150.0),GeometryProbeContract.Point(565.0,1186.0),GeometryProbeContract.Point(95.0,1186.0)),0.9),
            Item("a-tail","confirmation.",1,listOf(GeometryProbeContract.Point(97.0,1198.0),GeometryProbeContract.Point(337.0,1198.0),GeometryProbeContract.Point(337.0,1234.0),GeometryProbeContract.Point(97.0,1234.0)),0.9),
            Item("b-head","Remboursement possible après",2,listOf(GeometryProbeContract.Point(95.0,1150.0),GeometryProbeContract.Point(565.0,1150.0),GeometryProbeContract.Point(565.0,1186.0),GeometryProbeContract.Point(95.0,1186.0)),0.9),
            Item("b-tail","confirmation.",2,listOf(GeometryProbeContract.Point(97.0,1198.0),GeometryProbeContract.Point(337.0,1198.0),GeometryProbeContract.Point(337.0,1234.0),GeometryProbeContract.Point(97.0,1234.0)),0.9),
        )),
        Page("en-duplicate-tail","en",1200,2400,listOf(
            Item("title","Booking details",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("head","No refunds after",1,listOf(GeometryProbeContract.Point(100.0,1150.0),GeometryProbeContract.Point(570.0,1150.0),GeometryProbeContract.Point(570.0,1186.0),GeometryProbeContract.Point(100.0,1186.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(102.0,1198.0),GeometryProbeContract.Point(342.0,1198.0),GeometryProbeContract.Point(342.0,1234.0),GeometryProbeContract.Point(102.0,1234.0)),0.9),
            Item("tail-copy","confirmation.",2,listOf(GeometryProbeContract.Point(102.0,1198.0),GeometryProbeContract.Point(342.0,1198.0),GeometryProbeContract.Point(342.0,1234.0),GeometryProbeContract.Point(102.0,1234.0)),0.9),
        )),
        Page("en-same-strip-copy","en",1200,2400,listOf(
            Item("title","Booking details",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("head","No refunds after",0,listOf(GeometryProbeContract.Point(100.0,350.0),GeometryProbeContract.Point(570.0,350.0),GeometryProbeContract.Point(570.0,390.0),GeometryProbeContract.Point(100.0,390.0)),0.9),
            Item("tail","confirmation.",0,listOf(GeometryProbeContract.Point(102.0,406.0),GeometryProbeContract.Point(342.0,406.0),GeometryProbeContract.Point(342.0,446.0),GeometryProbeContract.Point(102.0,446.0)),0.9),
            Item("head-copy","No refunds after",0,listOf(GeometryProbeContract.Point(101.0,350.0),GeometryProbeContract.Point(571.0,350.0),GeometryProbeContract.Point(571.0,390.0),GeometryProbeContract.Point(101.0,390.0)),0.9),
        )),
        Page("fr-repeated-different-rows","fr",1200,2400,listOf(
            Item("title","Conditions de réservation",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("first-head","Aucun remboursement après",0,listOf(GeometryProbeContract.Point(100.0,300.0),GeometryProbeContract.Point(570.0,300.0),GeometryProbeContract.Point(570.0,340.0),GeometryProbeContract.Point(100.0,340.0)),0.9),
            Item("first-tail","confirmation.",0,listOf(GeometryProbeContract.Point(102.0,356.0),GeometryProbeContract.Point(342.0,356.0),GeometryProbeContract.Point(342.0,396.0),GeometryProbeContract.Point(102.0,396.0)),0.9),
            Item("second-head","Aucun remboursement après",0,listOf(GeometryProbeContract.Point(100.0,485.0),GeometryProbeContract.Point(570.0,485.0),GeometryProbeContract.Point(570.0,525.0),GeometryProbeContract.Point(100.0,525.0)),0.9),
            Item("second-tail","confirmation.",0,listOf(GeometryProbeContract.Point(102.0,541.0),GeometryProbeContract.Point(342.0,541.0),GeometryProbeContract.Point(342.0,581.0),GeometryProbeContract.Point(102.0,581.0)),0.9),
        )),
        Page("en-cross-column-fragment","en",1200,2400,listOf(
            Item("title","Booking details",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("head","No refunds after",0,listOf(GeometryProbeContract.Point(80.0,300.0),GeometryProbeContract.Point(550.0,300.0),GeometryProbeContract.Point(550.0,340.0),GeometryProbeContract.Point(80.0,340.0)),0.9),
            Item("tail","confirmation.",0,listOf(GeometryProbeContract.Point(600.0,356.0),GeometryProbeContract.Point(840.0,356.0),GeometryProbeContract.Point(840.0,396.0),GeometryProbeContract.Point(600.0,396.0)),0.9),
        )),
        Page("fr-intervening-label","fr",1200,2400,listOf(
            Item("title","Conditions de réservation",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("head","Aucun remboursement après",0,listOf(GeometryProbeContract.Point(80.0,300.0),GeometryProbeContract.Point(550.0,300.0),GeometryProbeContract.Point(550.0,340.0),GeometryProbeContract.Point(80.0,340.0)),0.9),
            Item("tail","confirmation.",0,listOf(GeometryProbeContract.Point(82.0,365.0),GeometryProbeContract.Point(322.0,365.0),GeometryProbeContract.Point(322.0,405.0),GeometryProbeContract.Point(82.0,405.0)),0.9),
            Item("intervening","Prix : 45 €.",0,listOf(GeometryProbeContract.Point(82.0,343.0),GeometryProbeContract.Point(332.0,343.0),GeometryProbeContract.Point(332.0,361.0),GeometryProbeContract.Point(82.0,361.0)),0.9),
        )),
        Page("en-spaced-complete","en",1200,2400,listOf(
            Item("title","Booking details",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("head","No refunds after",0,listOf(GeometryProbeContract.Point(100.0,300.0),GeometryProbeContract.Point(570.0,300.0),GeometryProbeContract.Point(570.0,340.0),GeometryProbeContract.Point(100.0,340.0)),0.9),
            Item("tail","confirmation.",0,listOf(GeometryProbeContract.Point(102.0,435.0),GeometryProbeContract.Point(342.0,435.0),GeometryProbeContract.Point(342.0,475.0),GeometryProbeContract.Point(102.0,475.0)),0.9),
        )),
        Page("fr-indented-complete","fr",1200,2400,listOf(
            Item("title","Conditions de réservation",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("head","Aucun remboursement après",1,listOf(GeometryProbeContract.Point(80.0,850.0),GeometryProbeContract.Point(550.0,850.0),GeometryProbeContract.Point(550.0,890.0),GeometryProbeContract.Point(80.0,890.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(110.0,906.0),GeometryProbeContract.Point(350.0,906.0),GeometryProbeContract.Point(350.0,946.0),GeometryProbeContract.Point(110.0,946.0)),0.9),
        )),
        Page("en-trailing-exception","en",1200,2400,listOf(
            Item("title","Booking details",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("head","No refunds after",0,listOf(GeometryProbeContract.Point(100.0,300.0),GeometryProbeContract.Point(570.0,300.0),GeometryProbeContract.Point(570.0,340.0),GeometryProbeContract.Point(100.0,340.0)),0.9),
            Item("tail","confirmation.",0,listOf(GeometryProbeContract.Point(102.0,356.0),GeometryProbeContract.Point(342.0,356.0),GeometryProbeContract.Point(342.0,396.0),GeometryProbeContract.Point(102.0,396.0)),0.9),
            Item("exception","Except when the service is cancelled.",0,listOf(GeometryProbeContract.Point(102.0,410.0),GeometryProbeContract.Point(822.0,410.0),GeometryProbeContract.Point(822.0,450.0),GeometryProbeContract.Point(102.0,450.0)),0.9),
        )),
        Page("fr-trailing-restriction","fr",1200,2400,listOf(
            Item("title","Conditions de réservation",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("head","Remboursement possible avant",1,listOf(GeometryProbeContract.Point(80.0,700.0),GeometryProbeContract.Point(550.0,700.0),GeometryProbeContract.Point(550.0,740.0),GeometryProbeContract.Point(80.0,740.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(82.0,756.0),GeometryProbeContract.Point(322.0,756.0),GeometryProbeContract.Point(322.0,796.0),GeometryProbeContract.Point(82.0,796.0)),0.9),
            Item("restriction","Uniquement avec votre reçu.",1,listOf(GeometryProbeContract.Point(82.0,810.0),GeometryProbeContract.Point(782.0,810.0),GeometryProbeContract.Point(782.0,850.0),GeometryProbeContract.Point(82.0,850.0)),0.9),
        )),
        Page("en-leading-negated-claim","en",1200,2400,listOf(
            Item("title","Booking details",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("negated-claim","The following is NOT our refund policy:",0,listOf(GeometryProbeContract.Point(100.0,240.0),GeometryProbeContract.Point(900.0,240.0),GeometryProbeContract.Point(900.0,280.0),GeometryProbeContract.Point(100.0,280.0)),0.9),
            Item("head","No refunds after",0,listOf(GeometryProbeContract.Point(100.0,300.0),GeometryProbeContract.Point(570.0,300.0),GeometryProbeContract.Point(570.0,340.0),GeometryProbeContract.Point(100.0,340.0)),0.9),
            Item("tail","confirmation.",0,listOf(GeometryProbeContract.Point(102.0,356.0),GeometryProbeContract.Point(342.0,356.0),GeometryProbeContract.Point(342.0,396.0),GeometryProbeContract.Point(102.0,396.0)),0.9),
        )),
        Page("fr-leading-prerequisite","fr",1200,2400,listOf(
            Item("title","Conditions de réservation",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("prerequisite","Si le service est annulé :",1,listOf(GeometryProbeContract.Point(80.0,640.0),GeometryProbeContract.Point(780.0,640.0),GeometryProbeContract.Point(780.0,680.0),GeometryProbeContract.Point(80.0,680.0)),0.9),
            Item("head","Remboursement possible après",1,listOf(GeometryProbeContract.Point(80.0,700.0),GeometryProbeContract.Point(550.0,700.0),GeometryProbeContract.Point(550.0,740.0),GeometryProbeContract.Point(80.0,740.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(82.0,756.0),GeometryProbeContract.Point(322.0,756.0),GeometryProbeContract.Point(322.0,796.0),GeometryProbeContract.Point(82.0,796.0)),0.9),
        )),
        Page("en-neutral-neighbour","en",1200,2400,listOf(
            Item("title","Booking details",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("head","No refunds after",0,listOf(GeometryProbeContract.Point(100.0,300.0),GeometryProbeContract.Point(570.0,300.0),GeometryProbeContract.Point(570.0,340.0),GeometryProbeContract.Point(100.0,340.0)),0.9),
            Item("tail","confirmation.",0,listOf(GeometryProbeContract.Point(102.0,356.0),GeometryProbeContract.Point(342.0,356.0),GeometryProbeContract.Point(342.0,396.0),GeometryProbeContract.Point(102.0,396.0)),0.9),
            Item("support","Support is available every day.",0,listOf(GeometryProbeContract.Point(102.0,410.0),GeometryProbeContract.Point(822.0,410.0),GeometryProbeContract.Point(822.0,450.0),GeometryProbeContract.Point(102.0,450.0)),0.9),
        )),
        Page("fr-independent-neighbour","fr",1200,2400,listOf(
            Item("title","Conditions de réservation",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("head","Aucun remboursement après",0,listOf(GeometryProbeContract.Point(45.0,320.0),GeometryProbeContract.Point(515.0,320.0),GeometryProbeContract.Point(515.0,360.0),GeometryProbeContract.Point(45.0,360.0)),0.9),
            Item("tail","confirmation.",0,listOf(GeometryProbeContract.Point(47.0,376.0),GeometryProbeContract.Point(287.0,376.0),GeometryProbeContract.Point(287.0,416.0),GeometryProbeContract.Point(47.0,416.0)),0.9),
            Item("delivery-title","Livraison :",0,listOf(GeometryProbeContract.Point(590.0,260.0),GeometryProbeContract.Point(940.0,260.0),GeometryProbeContract.Point(940.0,300.0),GeometryProbeContract.Point(590.0,300.0)),0.9),
            Item("delivery","Une adresse est nécessaire.",0,listOf(GeometryProbeContract.Point(590.0,350.0),GeometryProbeContract.Point(1020.0,350.0),GeometryProbeContract.Point(1020.0,390.0),GeometryProbeContract.Point(590.0,390.0)),0.9),
        )),
        Page("en-clipped-head-conflict","en",1200,2400,listOf(
            Item("title","Booking details",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("head-clipped","No refunds af",0,listOf(GeometryProbeContract.Point(100.0,634.0),GeometryProbeContract.Point(570.0,634.0),GeometryProbeContract.Point(570.0,663.0),GeometryProbeContract.Point(100.0,663.0)),0.9),
            Item("head-full","No refunds after",1,listOf(GeometryProbeContract.Point(100.0,630.0),GeometryProbeContract.Point(570.0,630.0),GeometryProbeContract.Point(570.0,674.0),GeometryProbeContract.Point(100.0,674.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(102.0,690.0),GeometryProbeContract.Point(342.0,690.0),GeometryProbeContract.Point(342.0,730.0),GeometryProbeContract.Point(102.0,730.0)),0.9),
        )),
        Page("hans-source-only","zh-Hans",1200,2400,listOf(
            Item("title","订单说明",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("condition","确认后不可退款。",0,listOf(GeometryProbeContract.Point(100.0,350.0),GeometryProbeContract.Point(750.0,350.0),GeometryProbeContract.Point(750.0,390.0),GeometryProbeContract.Point(100.0,390.0)),0.9),
            Item("context","请核对订单信息。",0,listOf(GeometryProbeContract.Point(100.0,430.0),GeometryProbeContract.Point(750.0,430.0),GeometryProbeContract.Point(750.0,470.0),GeometryProbeContract.Point(100.0,470.0)),0.9),
        )),
        Page("hant-source-only","zh-Hant",1200,2400,listOf(
            Item("title","訂單說明",0,listOf(GeometryProbeContract.Point(50.0,70.0),GeometryProbeContract.Point(700.0,70.0),GeometryProbeContract.Point(700.0,110.0),GeometryProbeContract.Point(50.0,110.0)),0.9),
            Item("condition","確認後不予退款。",0,listOf(GeometryProbeContract.Point(100.0,350.0),GeometryProbeContract.Point(750.0,350.0),GeometryProbeContract.Point(750.0,390.0),GeometryProbeContract.Point(100.0,390.0)),0.9),
            Item("context","請核對訂單資料。",0,listOf(GeometryProbeContract.Point(100.0,430.0),GeometryProbeContract.Point(750.0,430.0),GeometryProbeContract.Point(750.0,470.0),GeometryProbeContract.Point(100.0,470.0)),0.9),
        )),
    )
}
