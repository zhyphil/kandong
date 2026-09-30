package com.kandong.modelprobe

import com.kandong.ocrlab.context.*
import com.kandong.ocrlab.context.capture.CaptureCheckpoint
import com.kandong.ocrlab.context.capture.CaptureVersion

/** Test-only API skeleton; deliberately no behavior before the regression red run. */
internal class FullPageRegionController(private val source: () -> CaptureCheckpoint) {
    class Ticket internal constructor()
    data class Projection(val candidateId: String, val groupId: String,
        val sourceEnvelope: ContextRect, val sourceIntersection: ContextRect,
        val mirrorRect: ContextRect, val conservative: Boolean)
    data class Frame(val metadata: RgbaFrameMetadata, val page: FullPageOcrAssociation.Result,
        val roi: ContextRect, val transform: MirrorTransform, val selectedIds: List<String>,
        val visible: List<Projection>, val contextIds: List<String>, val unsupportedIds: List<String>)
    fun observePage(version: CaptureVersion) {}
    fun click(): Ticket? = null
    fun checkpoint(ticket: Ticket): CaptureCheckpoint = source()
    fun accept(ticket: Ticket, permit: FullPageOcrPublication.RegionPermit?): Boolean = false
    fun clear(reason: ClearReason) {}
    fun render(roi: ContextRect, transform: MirrorTransform): Frame? = null
}
