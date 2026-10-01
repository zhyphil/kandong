package com.kandong.compat

import android.graphics.Canvas
import com.kandong.liveocr.OcrBlock

/** Cached source-space layouts shared by live drawing and pixel regression tests. */
internal class SnapshotTextPainter(blocks: List<OcrBlock>,translations: Map<String,String>) {
    private data class Row(val source: OcrBlock,val bounds: Box,val layout: SnapshotTextLayout)
    private val rows=blocks.map { block ->
        val bounds=SnapshotTextContent.displayBounds(block,blocks)
        Row(block,bounds,SnapshotTextLayout(translations[block.id] ?: block.text,bounds.width,bounds.height))
    }
    fun draw(canvas: Canvas,crop: Box) {
        val selected=rows.filter { SnapshotTextContent.intersects(it.source,crop) }
        // No later background may erase a glyph already drawn by a neighboring box.
        for(background in listOf(true,false)) selected.forEach { row ->
            canvas.save(); canvas.translate((row.bounds.left-crop.left).toFloat(),(row.bounds.top-crop.top).toFloat())
            if(background) row.layout.drawBackground(canvas) else row.layout.drawText(canvas)
            canvas.restore()
        }
    }
}
