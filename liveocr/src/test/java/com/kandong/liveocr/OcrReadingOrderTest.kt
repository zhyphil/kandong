package com.kandong.liveocr

import org.junit.Assert.*
import org.junit.Test

class OcrReadingOrderTest {
    private fun block(id: String,x: Int,y: Int,w: Int=100,h: Int=40)=OcrBlock(id,id,x,y,x+w,y+h,.9)
    @Test fun realFrenchArrivalBoxesReadLeftToRightDespiteThreePixelTopDifference() {
        val arrival=OcrBlock("arrival","Arrivée",32,362,214,423,.9)
        val after=OcrBlock("after","après 16h30",201,359,544,426,.9)
        for(input in listOf(listOf(arrival,after),listOf(after,arrival))) {
            assertEquals(listOf(arrival,after),OcrReadingOrder.order(input))
        }
    }
    @Test fun wordOrderIsScaleIndependentAndKeepsEveryField() {
        for(scale in listOf(1,2,5)) {
            val left=block("left",20*scale,103*scale,60*scale,40*scale)
            val right=block("right",80*scale,100*scale,100*scale,40*scale)
            val lower=block("lower",10*scale,180*scale,100*scale,40*scale)
            val input=listOf(right,lower,left)
            assertEquals(listOf(left,right,lower),OcrReadingOrder.order(input))
            assertEquals(listOf(right,lower,left),input)
        }
    }
    @Test fun successiveSmallOffsetsCannotMergeDifferentLines() {
        val top=block("top",80,100)
        val middle=block("middle",40,109)
        val bottom=block("bottom",0,118)
        assertEquals(listOf(middle,top,bottom),OcrReadingOrder.order(listOf(bottom,middle,top)))
    }
    @Test fun widelyDifferentHeightAndSeparateRowsAreNotOneLine() {
        val heading=block("heading",100,10,200,60)
        val small=block("small",10,32,30,12)
        val body=block("body",20,100,200,30)
        assertEquals(listOf(heading,small,body),OcrReadingOrder.order(listOf(body,heading,small)))
    }
    @Test fun identicalPositionsKeepStableIdentityAndNoMutableResultEscapes() {
        val a=block("a",20,20); val b=a.copy(id="b")
        val result=OcrReadingOrder.order(listOf(b,a))
        assertEquals(listOf(a,b),result)
        try { (result as MutableList<OcrBlock>).clear(); fail("Must be immutable") }
        catch(_: UnsupportedOperationException) { }
        assertTrue(OcrReadingOrder.order(emptyList()).isEmpty())
    }
}
