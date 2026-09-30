package com.kandong.modelprobe

import com.kandong.modelprobe.SemanticContextEvalCorpus.Item
import com.kandong.modelprobe.SemanticContextEvalCorpus.Page

/** Generated INPUTS only. Authored candidate geometry, never model output; no semantic oracle here. */
internal object ConditionGuardCorpus {
    const val SHA="9cb4274bd1b6d455e34182827cd7574a6575cf86fd329b123062c38347f912ab"
    fun pages():List<Page> = listOf(
        Page("en-unless-before","en",1200,2400,listOf(
            Item("title","Tickets",0,listOf(GeometryProbeContract.Point(50.0,40.0),GeometryProbeContract.Point(470.0,40.0),GeometryProbeContract.Point(470.0,70.0),GeometryProbeContract.Point(50.0,70.0)),0.9),
            Item("head","No refunds after",1,listOf(GeometryProbeContract.Point(90.0,950.0),GeometryProbeContract.Point(510.0,950.0),GeometryProbeContract.Point(510.0,980.0),GeometryProbeContract.Point(90.0,980.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(90.0,990.0),GeometryProbeContract.Point(310.0,990.0),GeometryProbeContract.Point(310.0,1020.0),GeometryProbeContract.Point(90.0,1020.0)),0.9),
            Item("modifier0","Unless the carrier cancels.",1,listOf(GeometryProbeContract.Point(90.0,910.0),GeometryProbeContract.Point(530.0,910.0),GeometryProbeContract.Point(530.0,940.0),GeometryProbeContract.Point(90.0,940.0)),0.9),
        )),
        Page("en-provided-distant","en",1200,2400,listOf(
            Item("title","Tickets",0,listOf(GeometryProbeContract.Point(50.0,40.0),GeometryProbeContract.Point(470.0,40.0),GeometryProbeContract.Point(470.0,70.0),GeometryProbeContract.Point(50.0,70.0)),0.9),
            Item("head","No refunds after",1,listOf(GeometryProbeContract.Point(90.0,950.0),GeometryProbeContract.Point(510.0,950.0),GeometryProbeContract.Point(510.0,980.0),GeometryProbeContract.Point(90.0,980.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(90.0,990.0),GeometryProbeContract.Point(310.0,990.0),GeometryProbeContract.Point(310.0,1020.0),GeometryProbeContract.Point(90.0,1020.0)),0.9),
            Item("modifier0","Provided that the journey takes place.",2,listOf(GeometryProbeContract.Point(90.0,1700.0),GeometryProbeContract.Point(530.0,1700.0),GeometryProbeContract.Point(530.0,1730.0),GeometryProbeContract.Point(90.0,1730.0)),0.9),
        )),
        Page("en-only-nbsp","en",1200,2400,listOf(
            Item("title","Tickets",0,listOf(GeometryProbeContract.Point(50.0,40.0),GeometryProbeContract.Point(470.0,40.0),GeometryProbeContract.Point(470.0,70.0),GeometryProbeContract.Point(50.0,70.0)),0.9),
            Item("head","No refunds after",1,listOf(GeometryProbeContract.Point(90.0,950.0),GeometryProbeContract.Point(510.0,950.0),GeometryProbeContract.Point(510.0,980.0),GeometryProbeContract.Point(90.0,980.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(90.0,990.0),GeometryProbeContract.Point(310.0,990.0),GeometryProbeContract.Point(310.0,1020.0),GeometryProbeContract.Point(90.0,1020.0)),0.9),
            Item("modifier0","ONLY WITH YOUR RECEIPT.",1,listOf(GeometryProbeContract.Point(90.0,1030.0),GeometryProbeContract.Point(530.0,1030.0),GeometryProbeContract.Point(530.0,1060.0),GeometryProbeContract.Point(90.0,1060.0)),0.9),
        )),
        Page("en-except-split","en",1200,2400,listOf(
            Item("title","Tickets",0,listOf(GeometryProbeContract.Point(50.0,40.0),GeometryProbeContract.Point(470.0,40.0),GeometryProbeContract.Point(470.0,70.0),GeometryProbeContract.Point(50.0,70.0)),0.9),
            Item("head","No refunds after",1,listOf(GeometryProbeContract.Point(90.0,950.0),GeometryProbeContract.Point(510.0,950.0),GeometryProbeContract.Point(510.0,980.0),GeometryProbeContract.Point(90.0,980.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(90.0,990.0),GeometryProbeContract.Point(310.0,990.0),GeometryProbeContract.Point(310.0,1020.0),GeometryProbeContract.Point(90.0,1020.0)),0.9),
            Item("modifier0","Except",1,listOf(GeometryProbeContract.Point(90.0,1030.0),GeometryProbeContract.Point(530.0,1030.0),GeometryProbeContract.Point(530.0,1060.0),GeometryProbeContract.Point(90.0,1060.0)),0.9),
            Item("modifier1","when the operator cancels.",1,listOf(GeometryProbeContract.Point(90.0,1070.0),GeometryProbeContract.Point(530.0,1070.0),GeometryProbeContract.Point(530.0,1100.0),GeometryProbeContract.Point(90.0,1100.0)),0.9),
        )),
        Page("en-negated-heading","en",1200,2400,listOf(
            Item("title","Tickets",0,listOf(GeometryProbeContract.Point(50.0,40.0),GeometryProbeContract.Point(470.0,40.0),GeometryProbeContract.Point(470.0,70.0),GeometryProbeContract.Point(50.0,70.0)),0.9),
            Item("head","No refunds after",1,listOf(GeometryProbeContract.Point(90.0,950.0),GeometryProbeContract.Point(510.0,950.0),GeometryProbeContract.Point(510.0,980.0),GeometryProbeContract.Point(90.0,980.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(90.0,990.0),GeometryProbeContract.Point(310.0,990.0),GeometryProbeContract.Point(310.0,1020.0),GeometryProbeContract.Point(90.0,1020.0)),0.9),
            Item("modifier0","This rule is not applicable:",0,listOf(GeometryProbeContract.Point(90.0,120.0),GeometryProbeContract.Point(530.0,120.0),GeometryProbeContract.Point(530.0,150.0),GeometryProbeContract.Point(90.0,150.0)),0.9),
        )),
        Page("en-required-column","en",1200,2400,listOf(
            Item("title","Tickets",0,listOf(GeometryProbeContract.Point(50.0,40.0),GeometryProbeContract.Point(470.0,40.0),GeometryProbeContract.Point(470.0,70.0),GeometryProbeContract.Point(50.0,70.0)),0.9),
            Item("head","No refunds after",1,listOf(GeometryProbeContract.Point(90.0,950.0),GeometryProbeContract.Point(510.0,950.0),GeometryProbeContract.Point(510.0,980.0),GeometryProbeContract.Point(90.0,980.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(90.0,990.0),GeometryProbeContract.Point(310.0,990.0),GeometryProbeContract.Point(310.0,1020.0),GeometryProbeContract.Point(90.0,1020.0)),0.9),
            Item("modifier0","Membership required.",1,listOf(GeometryProbeContract.Point(700.0,1030.0),GeometryProbeContract.Point(1140.0,1030.0),GeometryProbeContract.Point(1140.0,1060.0),GeometryProbeContract.Point(700.0,1060.0)),0.9),
        )),
        Page("fr-sauf-before","fr",1200,2400,listOf(
            Item("title","Billets",0,listOf(GeometryProbeContract.Point(50.0,40.0),GeometryProbeContract.Point(470.0,40.0),GeometryProbeContract.Point(470.0,70.0),GeometryProbeContract.Point(50.0,70.0)),0.9),
            Item("head","Aucun remboursement après",1,listOf(GeometryProbeContract.Point(90.0,950.0),GeometryProbeContract.Point(510.0,950.0),GeometryProbeContract.Point(510.0,980.0),GeometryProbeContract.Point(90.0,980.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(90.0,990.0),GeometryProbeContract.Point(310.0,990.0),GeometryProbeContract.Point(310.0,1020.0),GeometryProbeContract.Point(90.0,1020.0)),0.9),
            Item("modifier0","Sauf annulation du service.",1,listOf(GeometryProbeContract.Point(90.0,910.0),GeometryProbeContract.Point(530.0,910.0),GeometryProbeContract.Point(530.0,940.0),GeometryProbeContract.Point(90.0,940.0)),0.9),
        )),
        Page("fr-reserve-distant","fr",1200,2400,listOf(
            Item("title","Billets",0,listOf(GeometryProbeContract.Point(50.0,40.0),GeometryProbeContract.Point(470.0,40.0),GeometryProbeContract.Point(470.0,70.0),GeometryProbeContract.Point(50.0,70.0)),0.9),
            Item("head","Aucun remboursement après",1,listOf(GeometryProbeContract.Point(90.0,950.0),GeometryProbeContract.Point(510.0,950.0),GeometryProbeContract.Point(510.0,980.0),GeometryProbeContract.Point(90.0,980.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(90.0,990.0),GeometryProbeContract.Point(310.0,990.0),GeometryProbeContract.Point(310.0,1020.0),GeometryProbeContract.Point(90.0,1020.0)),0.9),
            Item("modifier0","Sous réserve de disponibilité.",2,listOf(GeometryProbeContract.Point(90.0,1700.0),GeometryProbeContract.Point(530.0,1700.0),GeometryProbeContract.Point(530.0,1730.0),GeometryProbeContract.Point(90.0,1730.0)),0.9),
        )),
        Page("fr-condition-split","fr",1200,2400,listOf(
            Item("title","Billets",0,listOf(GeometryProbeContract.Point(50.0,40.0),GeometryProbeContract.Point(470.0,40.0),GeometryProbeContract.Point(470.0,70.0),GeometryProbeContract.Point(50.0,70.0)),0.9),
            Item("head","Aucun remboursement après",1,listOf(GeometryProbeContract.Point(90.0,950.0),GeometryProbeContract.Point(510.0,950.0),GeometryProbeContract.Point(510.0,980.0),GeometryProbeContract.Point(90.0,980.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(90.0,990.0),GeometryProbeContract.Point(310.0,990.0),GeometryProbeContract.Point(310.0,1020.0),GeometryProbeContract.Point(90.0,1020.0)),0.9),
            Item("modifier0","À condition",1,listOf(GeometryProbeContract.Point(90.0,1030.0),GeometryProbeContract.Point(530.0,1030.0),GeometryProbeContract.Point(530.0,1060.0),GeometryProbeContract.Point(90.0,1060.0)),0.9),
            Item("modifier1","de présenter le reçu.",1,listOf(GeometryProbeContract.Point(90.0,1070.0),GeometryProbeContract.Point(530.0,1070.0),GeometryProbeContract.Point(530.0,1100.0),GeometryProbeContract.Point(90.0,1100.0)),0.9),
        )),
        Page("fr-negative-heading","fr",1200,2400,listOf(
            Item("title","Billets",0,listOf(GeometryProbeContract.Point(50.0,40.0),GeometryProbeContract.Point(470.0,40.0),GeometryProbeContract.Point(470.0,70.0),GeometryProbeContract.Point(50.0,70.0)),0.9),
            Item("head","Aucun remboursement après",1,listOf(GeometryProbeContract.Point(90.0,950.0),GeometryProbeContract.Point(510.0,950.0),GeometryProbeContract.Point(510.0,980.0),GeometryProbeContract.Point(90.0,980.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(90.0,990.0),GeometryProbeContract.Point(310.0,990.0),GeometryProbeContract.Point(310.0,1020.0),GeometryProbeContract.Point(90.0,1020.0)),0.9),
            Item("modifier0","Cette règle n’est pas applicable :",0,listOf(GeometryProbeContract.Point(90.0,120.0),GeometryProbeContract.Point(530.0,120.0),GeometryProbeContract.Point(530.0,150.0),GeometryProbeContract.Point(90.0,150.0)),0.9),
        )),
        Page("fr-sans-column","fr",1200,2400,listOf(
            Item("title","Billets",0,listOf(GeometryProbeContract.Point(50.0,40.0),GeometryProbeContract.Point(470.0,40.0),GeometryProbeContract.Point(470.0,70.0),GeometryProbeContract.Point(50.0,70.0)),0.9),
            Item("head","Aucun remboursement après",1,listOf(GeometryProbeContract.Point(90.0,950.0),GeometryProbeContract.Point(510.0,950.0),GeometryProbeContract.Point(510.0,980.0),GeometryProbeContract.Point(90.0,980.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(90.0,990.0),GeometryProbeContract.Point(310.0,990.0),GeometryProbeContract.Point(310.0,1020.0),GeometryProbeContract.Point(90.0,1020.0)),0.9),
            Item("modifier0","Sans justificatif, cette règle change.",1,listOf(GeometryProbeContract.Point(700.0,1030.0),GeometryProbeContract.Point(1140.0,1030.0),GeometryProbeContract.Point(1140.0,1060.0),GeometryProbeContract.Point(700.0,1060.0)),0.9),
        )),
        Page("fr-uniquement-after","fr",1200,2400,listOf(
            Item("title","Billets",0,listOf(GeometryProbeContract.Point(50.0,40.0),GeometryProbeContract.Point(470.0,40.0),GeometryProbeContract.Point(470.0,70.0),GeometryProbeContract.Point(50.0,70.0)),0.9),
            Item("head","Aucun remboursement après",1,listOf(GeometryProbeContract.Point(90.0,950.0),GeometryProbeContract.Point(510.0,950.0),GeometryProbeContract.Point(510.0,980.0),GeometryProbeContract.Point(90.0,980.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(90.0,990.0),GeometryProbeContract.Point(310.0,990.0),GeometryProbeContract.Point(310.0,1020.0),GeometryProbeContract.Point(90.0,1020.0)),0.9),
            Item("modifier0","Uniquement pour le tarif flexible.",1,listOf(GeometryProbeContract.Point(90.0,1030.0),GeometryProbeContract.Point(530.0,1030.0),GeometryProbeContract.Point(530.0,1060.0),GeometryProbeContract.Point(90.0,1060.0)),0.9),
        )),
        Page("en-plain","en",1200,2400,listOf(
            Item("title","Tickets",0,listOf(GeometryProbeContract.Point(50.0,40.0),GeometryProbeContract.Point(470.0,40.0),GeometryProbeContract.Point(470.0,70.0),GeometryProbeContract.Point(50.0,70.0)),0.9),
            Item("head","No refunds after",1,listOf(GeometryProbeContract.Point(90.0,950.0),GeometryProbeContract.Point(510.0,950.0),GeometryProbeContract.Point(510.0,980.0),GeometryProbeContract.Point(90.0,980.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(90.0,990.0),GeometryProbeContract.Point(310.0,990.0),GeometryProbeContract.Point(310.0,1020.0),GeometryProbeContract.Point(90.0,1020.0)),0.9),
        )),
        Page("fr-plain","fr",1200,2400,listOf(
            Item("title","Billets",0,listOf(GeometryProbeContract.Point(50.0,40.0),GeometryProbeContract.Point(470.0,40.0),GeometryProbeContract.Point(470.0,70.0),GeometryProbeContract.Point(50.0,70.0)),0.9),
            Item("head","Aucun remboursement après",1,listOf(GeometryProbeContract.Point(90.0,950.0),GeometryProbeContract.Point(510.0,950.0),GeometryProbeContract.Point(510.0,980.0),GeometryProbeContract.Point(90.0,980.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(90.0,990.0),GeometryProbeContract.Point(310.0,990.0),GeometryProbeContract.Point(310.0,1020.0),GeometryProbeContract.Point(90.0,1020.0)),0.9),
        )),
        Page("en-plain-neighbour","en",1200,2400,listOf(
            Item("title","Tickets",0,listOf(GeometryProbeContract.Point(50.0,40.0),GeometryProbeContract.Point(470.0,40.0),GeometryProbeContract.Point(470.0,70.0),GeometryProbeContract.Point(50.0,70.0)),0.9),
            Item("head","No refunds after",1,listOf(GeometryProbeContract.Point(90.0,950.0),GeometryProbeContract.Point(510.0,950.0),GeometryProbeContract.Point(510.0,980.0),GeometryProbeContract.Point(90.0,980.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(90.0,990.0),GeometryProbeContract.Point(310.0,990.0),GeometryProbeContract.Point(310.0,1020.0),GeometryProbeContract.Point(90.0,1020.0)),0.9),
            Item("modifier0","Contact the help desk.",1,listOf(GeometryProbeContract.Point(90.0,1030.0),GeometryProbeContract.Point(530.0,1030.0),GeometryProbeContract.Point(530.0,1060.0),GeometryProbeContract.Point(90.0,1060.0)),0.9),
        )),
        Page("fr-plain-neighbour","fr",1200,2400,listOf(
            Item("title","Billets",0,listOf(GeometryProbeContract.Point(50.0,40.0),GeometryProbeContract.Point(470.0,40.0),GeometryProbeContract.Point(470.0,70.0),GeometryProbeContract.Point(50.0,70.0)),0.9),
            Item("head","Aucun remboursement après",1,listOf(GeometryProbeContract.Point(90.0,950.0),GeometryProbeContract.Point(510.0,950.0),GeometryProbeContract.Point(510.0,980.0),GeometryProbeContract.Point(90.0,980.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(90.0,990.0),GeometryProbeContract.Point(310.0,990.0),GeometryProbeContract.Point(310.0,1020.0),GeometryProbeContract.Point(90.0,1020.0)),0.9),
            Item("modifier0","Contactez notre équipe.",1,listOf(GeometryProbeContract.Point(90.0,1030.0),GeometryProbeContract.Point(530.0,1030.0),GeometryProbeContract.Point(530.0,1060.0),GeometryProbeContract.Point(90.0,1060.0)),0.9),
        )),
        Page("en-independent-if","en",1200,2400,listOf(
            Item("title","Tickets",0,listOf(GeometryProbeContract.Point(50.0,40.0),GeometryProbeContract.Point(470.0,40.0),GeometryProbeContract.Point(470.0,70.0),GeometryProbeContract.Point(50.0,70.0)),0.9),
            Item("head","No refunds after",1,listOf(GeometryProbeContract.Point(90.0,950.0),GeometryProbeContract.Point(510.0,950.0),GeometryProbeContract.Point(510.0,980.0),GeometryProbeContract.Point(90.0,980.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(90.0,990.0),GeometryProbeContract.Point(310.0,990.0),GeometryProbeContract.Point(310.0,1020.0),GeometryProbeContract.Point(90.0,1020.0)),0.9),
            Item("modifier0","If you need help, contact support.",2,listOf(GeometryProbeContract.Point(90.0,1700.0),GeometryProbeContract.Point(530.0,1700.0),GeometryProbeContract.Point(530.0,1730.0),GeometryProbeContract.Point(90.0,1730.0)),0.9),
        )),
        Page("fr-independent-si","fr",1200,2400,listOf(
            Item("title","Billets",0,listOf(GeometryProbeContract.Point(50.0,40.0),GeometryProbeContract.Point(470.0,40.0),GeometryProbeContract.Point(470.0,70.0),GeometryProbeContract.Point(50.0,70.0)),0.9),
            Item("head","Aucun remboursement après",1,listOf(GeometryProbeContract.Point(90.0,950.0),GeometryProbeContract.Point(510.0,950.0),GeometryProbeContract.Point(510.0,980.0),GeometryProbeContract.Point(90.0,980.0)),0.9),
            Item("tail","confirmation.",1,listOf(GeometryProbeContract.Point(90.0,990.0),GeometryProbeContract.Point(310.0,990.0),GeometryProbeContract.Point(310.0,1020.0),GeometryProbeContract.Point(90.0,1020.0)),0.9),
            Item("modifier0","Si vous avez besoin d’aide, contactez-nous.",2,listOf(GeometryProbeContract.Point(90.0,1700.0),GeometryProbeContract.Point(530.0,1700.0),GeometryProbeContract.Point(530.0,1730.0),GeometryProbeContract.Point(90.0,1730.0)),0.9),
        )),
    )
}
