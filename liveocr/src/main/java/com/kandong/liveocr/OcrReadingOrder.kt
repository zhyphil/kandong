package com.kandong.liveocr

import java.util.Collections
import kotlin.math.abs

/** Geometric horizontal-line ordering for EN/FR/ZH, not semantic paragraph grouping. */
object OcrReadingOrder {
    fun order(blocks: List<OcrBlock>): List<OcrBlock> {
        fun center(b: OcrBlock)=(b.top.toDouble()+b.bottom)/2
        fun sameLine(a: OcrBlock,b: OcrBlock): Boolean {
            val ah=a.bottom-a.top; val bh=b.bottom-b.top
            val overlap=minOf(a.bottom,b.bottom)-maxOf(a.top,b.top)
            return ah>0 && bh>0 && abs(center(a)-center(b))<=minOf(ah,bh)*.25 &&
                overlap.toDouble()*2>=maxOf(ah,bh)
        }
        val result=ArrayList<OcrBlock>(blocks.size)
        val line=ArrayList<OcrBlock>()
        fun flush() {
            result.addAll(line.sortedWith(compareBy<OcrBlock> { it.left }.thenBy { it.top }.thenBy { it.id }))
            line.clear()
        }
        for(block in blocks.sortedWith(compareBy<OcrBlock> { it.top }.thenBy { it.left }.thenBy { it.id })) {
            // Compare with EVERY line member: a tall/slanted chain cannot bridge two lines.
            if(line.isNotEmpty() && line.any { !sameLine(it,block) }) flush()
            line.add(block)
        }
        flush()
        return Collections.unmodifiableList(result)
    }
}
