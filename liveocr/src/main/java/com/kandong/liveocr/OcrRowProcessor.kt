package com.kandong.liveocr

/** Local geometry/size exclusions are separate from cancellation and native/runtime failures. */
internal object OcrRowProcessor {
    fun recognize(strip: FullPageStripPlanner.Strip, box: BoxPipelineContract.Box, rank: Int,
        pageHeight: Int, current: OcrCurrent,
        recognize: (GeometryProbeContract.Row, RecognitionPacking.Plan) -> String): OcrPageContract.Candidate {
        current.check()
        val candidate=OcrPageContract.candidate(strip,box,"",pageHeight)
        // The neighboring core processes halo pixels. Do not spend native inference
        // on a duplicate partial box, or let its extreme aspect ratio fail the page.
        if(!candidate.ownsCoreCenter || candidate.touchesReadBoundary) return candidate
        val prepared=try {
            val row=BoxPipelineContract.cropRow("live-page",box,rank,strip.read.width,strip.read.height)
            val plan=RecognitionPacking.plan(listOf(RecognitionPacking.Size(row.plan.outputWidth,row.plan.outputHeight)))
            row to plan
        } catch(_: IllegalArgumentException) {
            // Pure preflight only: invalid quad/homography or unsupported row size.
            // No native work or cleanup failure is swallowed here.
            return candidate
        }
        current.check()
        val text=recognize(prepared.first,prepared.second)
        current.check()
        return candidate.copy(text=text)
    }
}
