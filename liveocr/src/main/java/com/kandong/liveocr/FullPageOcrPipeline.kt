package com.kandong.liveocr

import ai.onnxruntime.OrtSession
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Curated FullPageOcrPipeline.run/detect/resize; one live strip and one crop at a time. */
internal class FullPageOcrPipeline(private val engine: OrtRuntime, private val detector: OrtSession,
    private val recognizer: OrtSession, private val model: OcrModel, private val dictionary: List<String>,
    private val cleanup: GeometryCleanup, private val current: OcrCurrent) {

    fun run(rgba: ByteArray, width: Int, height: Int): List<OcrPageContract.Candidate> {
        require(dictionary.size == model.vocabulary)
        OcrPageContract.outputShape(model, 320)
        var boxCount = 0
        var chars = 0
        return SingleFrameStripInput.process(rgba, width, height, current) { strip, bgr ->
            current.check()
            val source = GeometryImage(strip.read.width, strip.read.height, bgr)
            val probability = detect(source, strip.detector)
            val boxes = try {
                current.check()
                BoxPipelineOpenCv(cleanup).run(strip.detector.outputShape, probability, source.width, source.height,
                    after = { current.check() })
            } finally { probability.fill(0f) }
            try {
                current.check()
                requireOcr(boxes.status == BoxPipelineContract.Status.COMPLETE, OcrFailure.PIPELINE_FAILED)
                OcrPageContract.boxBudget(boxes.boxes.size, boxCount)
                boxCount += boxes.boxes.size // Account for every strip before allocating its first crop.
                val rows = ArrayList<OcrPageContract.Candidate>()
                for ((rank, box) in boxes.boxes.withIndex()) {
                    current.check()
                    val row = BoxPipelineContract.cropRow("live-page", box, rank, source.width, source.height)
                    GeometryOpenCvProbe(cleanup).crop(source, row, current::check).use { crop ->
                        current.check()
                        val sizes = listOf(RecognitionPacking.Size(crop.width, crop.height))
                        val plan = RecognitionPacking.plan(sizes)
                        val shape = OcrPageContract.outputShape(model, plan.width)
                        require(plan.tensorRowToInput == listOf(0))
                        val resized = CropRecognitionPipeline(cleanup).resize(crop, plan.resizedWidths.single(), current::check)
                        val values = try { current.check(); RecognitionPacking.pack(sizes, listOf(resized)) }
                            finally { resized.argb.fill(0) }
                        val input = try {
                            require(values.size == 3 * RecognitionPacking.HEIGHT * plan.width &&
                                values.size <= 3 * RecognitionPacking.HEIGHT * RecognitionPacking.MAX_WIDTH)
                            OrtRuntime.tensor(values, longArrayOf(1, 3, 48, plan.width.toLong()))
                        } finally { values.fill(0f) }
                        val decoded = try {
                            current.check()
                            engine.recognize(recognizer, input, shape, dictionary).also { current.check() }
                        } finally { input.wipe() }
                        require(decoded.raw.size == 1)
                        val raw = decoded.raw.single()
                        chars = OcrPageContract.characterBudget(chars, raw.length)
                        // Keep empty, overlapping and unowned raw candidates until whole-page publication.
                        rows += OcrPageContract.candidate(strip, box, raw, height)
                    }
                }
                current.check()
                rows.toList()
            } finally { boxes.mask.fill(0); boxes.dilated?.fill(0) }
        }.flatten()
    }

    private fun detect(source: GeometryImage, plan: DetectorPacking.Plan): FloatArray {
        val argb = resize(source, plan)
        val packed = try { current.check(); DetectorPacking.pack(plan.width, plan.height, argb) }
            finally { argb.fill(0) }
        val input = try { OrtRuntime.tensor(packed, plan.inputShape) } finally { packed.fill(0f) }
        return try {
            current.check()
            engine.detect(detector, input, plan.outputShape).also { values ->
                try { current.check() } catch (e: Throwable) { values.fill(0f); throw e }
            }
        } finally { input.wipe() }
    }

    /** Same BGR INTER_LINEAR and detector rounding as the original full-page run. */
    private fun resize(source: GeometryImage, plan: DetectorPacking.Plan): IntArray {
        require(plan == DetectorPacking.plan(source.width, source.height))
        require(source.bgr.size == GeometryProbeContract.pixels(source.width, source.height) * 3)
        var pixels: IntArray? = null
        current.check()
        try {
            return OwnedMats(cleanup).use { mats ->
                val src = mats.own("fullPageResizeSource", Mat(source.height, source.width, CvType.CV_8UC3))
                val dst = mats.own("fullPageResizeDestination", Mat(plan.height, plan.width, CvType.CV_8UC3))
                check(src.put(0, 0, source.bgr) == source.bgr.size)
                current.check()
                Imgproc.resize(src, dst, Size(plan.width.toDouble(), plan.height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
                current.check()
                check(dst.type() == CvType.CV_8UC3 && dst.rows() == plan.height && dst.cols() == plan.width)
                val bytes = ByteArray(DetectorPacking.elements(plan.inputShape, 3))
                try {
                    check(dst.get(0, 0, bytes) == bytes.size)
                    IntArray(bytes.size / 3) { i -> -0x1000000 or (bytes[3 * i].toInt() and 255) or
                        ((bytes[3 * i + 1].toInt() and 255) shl 8) or ((bytes[3 * i + 2].toInt() and 255) shl 16) }
                        .also { pixels = it }
                } finally { bytes.fill(0) }
            }
        } catch (e: Throwable) { pixels?.fill(0); throw e }
    }
}
