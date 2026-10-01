package com.kandong.compat

import com.kandong.liveocr.OcrBlock
import org.junit.Assert.*
import org.junit.Test

class SnapshotTextContentTest {
    private fun row(id: String,x: Int,y: Int,text: String=id)=OcrBlock(id,text,x,y,x+100,y+30,0.9)
    @Test fun fullTextUsesPageLineOrderBeforeCroppingAndKeepsTranslationIdentity() {
        val left=OcrBlock("left","Arrivée",32,362,214,423,.9)
        val right=OcrBlock("right","après 16h30",201,359,544,426,.9)
        val blocks=listOf(right,left)
        val translations=mapOf("right" to "16点30分之后","left" to "入住")
        val full=SnapshotTextContent.select(blocks,translations,Box(0,300,600,200))
        assertEquals(listOf("left","right"),full.map { it.id })
        assertEquals(listOf("入住","16点30分之后"),full.map { it.translation })
        assertEquals(listOf("right"),SnapshotTextContent.select(blocks,translations,Box(215,350,100,100)).map { it.id })
    }
    @Test fun neighboringWordKeepsItsLeftEdgeDespiteOcrOrder() {
        val left=OcrBlock("to","to",20,50,100,82,0.9)
        val right=OcrBlock("enjoy","enjoy",80,48,260,80,0.9)
        for(order in listOf(listOf(left,right),listOf(right,left))) {
            assertEquals(Box(20,50,60,32),SnapshotTextContent.displayBounds(left,order))
            assertEquals(Box(80,48,180,32),SnapshotTextContent.displayBounds(right,order))
        }
        assertEquals(100,left.right) // Source geometry for selection/context is unchanged.
    }
    @Test fun separateRowsAndGapsDoNotShrinkSourceLayout() {
        val source=row("a",20,50)
        val below=row("b",80,76)
        val distant=row("c",200,50)
        assertEquals(Box(20,50,100,30),SnapshotTextContent.displayBounds(source,listOf(source,below,distant)))
    }
    @Test fun detailsMatchIntersectingSourceBlocksNotResponseOrder() {
        val blocks=listOf(row("lower",50,160),row("outside",400,100),row("upper",50,100))
        val result=SnapshotTextContent.select(blocks,linkedMapOf("lower" to "下方译文","upper" to "上方译文"),Box(60,120,160,80))
        assertEquals(listOf("upper","lower"),result.map { it.id })
        assertEquals(listOf("上方译文","下方译文"),result.map { it.translation })
    }
    @Test fun touchingWithoutOverlapIsExcludedAndPartialOverlapKeepsWholeText() {
        val source="Breakfast is not included. ".repeat(30)
        val translated="房费不含早餐。".repeat(30)
        val result=SnapshotTextContent.select(listOf(row("touch",0,100),row("part",90,110,source)),
            mapOf("part" to translated),Box(100,100,100,100))
        assertEquals(1,result.size)
        assertEquals(source,result.single().source)
        assertEquals(translated,result.single().translation)
    }
    @Test fun localReadingHasNoInventedTranslationAndMovingCropChangesSelectionOnly() {
        val blocks=listOf(row("a",20,20),row("b",20,200))
        val first=SnapshotTextContent.select(blocks,emptyMap(),Box(0,0,200,100))
        assertEquals("a",first.single().id); assertNull(first.single().translation)
        assertEquals("b",SnapshotTextContent.select(blocks,emptyMap(),Box(0,180,200,100)).single().id)
        assertEquals(listOf("a","b"),blocks.map { it.text })
    }
}
