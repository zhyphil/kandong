package com.kandong.modelprobe

import com.kandong.ocrlab.context.ContextRect
import com.kandong.ocrlab.context.capture.CaptureVersion

/** SYNTHETIC ONLY. These are authored drawing coordinates, never image/model observations. */
internal object RegionVisualFixture {
    const val WIDTH = 1200
    const val HEIGHT = 800
    const val TTL = 60_000L
    const val NOTICE = "固定样例 · 不识别、不翻译、不读取屏幕"
    data class Line(val key: String, val text: String, val bounds: ContextRect)
    val lines = listOf(
        Line("title", "車票資料", ContextRect(80.0,70.0,550.0,135.0)),
        Line("condition", "優惠限平日使用", ContextRect(700.0,180.0,1120.0,230.0)),
        Line("normal", "成人單程", ContextRect(80.0,345.0,370.0,383.0)),
        Line("tax", "稅", ContextRect(100.0,395.0,180.0,435.0)),
        Line("empty", "", ContextRect(600.0,370.0,760.0,410.0)),
        Line("footer", "請保留車票", ContextRect(80.0,660.0,540.0,710.0)))
    fun candidates(version: CaptureVersion, pageId: String, blank: Boolean): List<FullPageOcrContract.Candidate> {
        if (blank) return emptyList()
        val plan = FullPageStripPlanner.plan(WIDTH, HEIGHT)
        val rows = lines.map { line -> Triple(line, if (line.key == "footer") 1 else 0, line.text) } +
            Triple(Line("tax-alternate", "税", ContextRect(102.0,397.0,182.0,437.0)),1,"税")
        val ranks = IntArray(plan.strips.size)
        return rows.mapIndexed { index, (line, stripIndex, raw) ->
            val strip = plan.strips[stripIndex]
            val b = line.bounds
            val quad = listOf(GeometryProbeContract.Point(b.left,b.top), GeometryProbeContract.Point(b.right,b.top),
                GeometryProbeContract.Point(b.right,b.bottom), GeometryProbeContract.Point(b.left,b.bottom))
            FullPageOcrContract.Candidate(FullPageOcrContract.Provenance(version,pageId,stripIndex,strip.read,strip.core,
                index,index,index,ranks[stripIndex]++,quad.map { it.copy(x=it.x-strip.read.left,y=it.y-strip.read.top) },quad,
                .85,strip.ownsSourceCenter((b.left+b.right)/2,(b.top+b.bottom)/2),"visual-synthetic/${line.key}"),
                "ch",raw,320,40)
        }
    }
    /** Contract fixtures only: model/dictionary identities and receipts assert no actual inference or Image close. */
    fun stage(controller: FullPageRegionController, ticket: FullPageRegionController.Ticket,
        metadata: RgbaFrameMetadata, batch: String, blank: Boolean): FullPageOcrPublication.Pending {
        val page = if (blank) "visual-blank" else "visual-hant"
        val cs = candidates(metadata.version,page,blank)
        val plan = FullPageStripPlanner.plan(WIDTH,HEIGHT)
        val input = FullPageOcrPublication.Input(true,null,cs,plan.strips.map { s ->
            FullPageOcrPublication.Strip(s.index,s.read,s.core,s.detector.width,s.detector.height,"COMPLETE",
                cs.count { it.provenance.stripIndex == s.index }) },plan.strips.size,cs.size,1,1,null)
        return FullPageOcrPublication.stage(batch,page,metadata,FullPageOcrContract.models.first(),
            "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae",
            "72ad5c46d5bbc22921613539feaa90e2c4c4567bfb107a7e741be72e5e54a1af",input,
            FullPageOcrPublication.Guard(metadata) { controller.checkpoint(ticket) })
    }
}
