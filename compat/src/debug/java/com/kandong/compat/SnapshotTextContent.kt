package com.kandong.compat

import com.kandong.liveocr.OcrBlock
import com.kandong.liveocr.OcrReadingOrder

/** Mirror and full-text reading share source bounds and stable OCR identity. */
internal object SnapshotTextContent {
    data class Entry(val id: String, val source: String, val translation: String?)
    fun intersects(block: OcrBlock,crop: Box)=block.left<crop.right && block.right>crop.left &&
        block.top<crop.bottom && block.bottom>crop.top
    fun displayBounds(block: OcrBlock,neighbors: List<OcrBlock>): Box {
        // Detector padding can overlap the next word. Reserve its left edge before
        // laying out this word, regardless of small baseline/top ordering differences.
        val right=neighbors.asSequence().filter { other ->
            val overlap=minOf(block.bottom,other.bottom)-maxOf(block.top,other.top)
            other.left>block.left && other.left<block.right &&
                overlap*2>=maxOf(block.bottom-block.top,other.bottom-other.top)
        }.minOfOrNull { it.left } ?: block.right
        return Box(block.left,block.top,right-block.left,block.bottom-block.top)
    }
    fun select(blocks: List<OcrBlock>,translations: Map<String,String>,crop: Box): List<Entry> =
        OcrReadingOrder.order(blocks).filter { intersects(it,crop) }
            .map { Entry(it.id,it.text,translations[it.id]) }
}
