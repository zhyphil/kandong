package com.kandong.modelprobe

import ai.onnxruntime.OrtSession
import com.kandong.ocrlab.context.capture.CaptureCheckpoint
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/** One worker, one live strip/crop; no ROI, fixture text, expected boxes or model selection.
 * Sessions belong to the caller and are reused for a fixed model's entire page set.
 * Native calls are synchronous: checkpoints reject results between calls, not mid-inference.
 */
internal class FullPageOcrPipeline(private val engine: OrtProbeEngine, private val detector: OrtSession,
    private val recognizer: OrtSession, private val model: ProbeModel, private val dictionary: List<String>,
    private val cleanup: GeometryCleanup, private val ort: Cleanup) : DetectorProbeTestSupport() {
    data class StripDiagnostic(val index: Int, val read: FullPageStripPlanner.Rect, val core: FullPageStripPlanner.Rect,
        val detectorWidth: Int, val detectorHeight: Int, val status: String, val contours: Int?, val boxes: Int)
    data class PageResult(val complete: Boolean, val reason: String?, val candidates: List<FullPageOcrContract.Candidate>,
        val strips: List<StripDiagnostic>, val detectorInvocations: Int, val recognitionInvocations: Int,
        val frameCloseAttempts: Int, val frameClosed: Int, val transportRejection: String?)
    class StagedPage(val page: PageResult, val metadata: RgbaFrameMetadata,
        private val pending: FullPageOcrPublication.Pending) {
        fun publish(scopeSucceeded: Boolean, resourcesClosed: Boolean) = pending.publish(scopeSucceeded, resourcesClosed)
        fun discard() = pending.discard()
    }
    private val owner = Thread.currentThread()
    init {
        FullPageOcrContract.outputShape(model.id, model.sha, model.vocabulary, 320)
        require(dictionary.size == model.vocabulary)
    }

    /** Same sessions/dictionary/model used by run(); no second, caller-supplied identity envelope.
     * The narrow runner authenticates detector bytes with DetectorProbeInputs and recognizer and
     * dictionary bytes with ProbeInputs BEFORE constructing this pipeline. ProbeModel is not proof.
     */
    fun runStaged(sourceBatch: String, pageFixtureId: String, frame: RgbaFrameLease, meta: RgbaFrameMetadata,
        checkpoint: () -> CaptureCheckpoint): StagedPage {
        val guard = FullPageOcrPublication.Guard(meta, checkpoint)
        try {
            val page = run(pageFixtureId, frame, meta, guard::checkpoint)
            val input = FullPageOcrPublication.Input(page.complete, page.reason, page.candidates,
                page.strips.map { FullPageOcrPublication.Strip(it.index, it.read, it.core, it.detectorWidth,
                    it.detectorHeight, it.status, it.boxes) }, page.detectorInvocations, page.recognitionInvocations,
                page.frameCloseAttempts, page.frameClosed, page.transportRejection)
            val pending = FullPageOcrPublication.stage(sourceBatch, pageFixtureId, meta,
                FullPageOcrContract.Model(model.id, model.sha, model.vocabulary), DetectorProbeInputs.MODEL_SHA,
                model.dictionarySha, input, guard)
            return StagedPage(page, meta, pending)
        } catch (e: Throwable) {
            guard.finish()
            throw e
        }
    }

    fun run(pageFixtureId: String, frame: RgbaFrameLease, meta: RgbaFrameMetadata,
        checkpoint: () -> CaptureCheckpoint): PageResult {
        var detectors = 0; var recognitions = 0; var chars = 0; var boxCount = 0
        var closeAttempts = 0; var closed = 0; var failure: String? = null
        val diagnostics = arrayListOf<StripDiagnostic>()
        val current = FullPageOcrContract.Current(meta, checkpoint)
        fun ready() {
            check(Thread.currentThread() === owner)
            current.check()
            check(!ort.uncertain) { "CLEANUP_UNCERTAIN" }
        }
        // process owns the Image through the entire synchronous consumer, including failure.
        val counted = object : RgbaFrameLease {
            override val metadata get() = frame.metadata.also { require(it == meta) }
            override fun plane() = frame.plane()
            override fun close() { closeAttempts++; frame.close(); closed++ }
        }
        val result = SingleFrameStripInput.process(counted, checkpoint) { strip, bgr ->
            try {
                ready()
                val source = GeometryImage(strip.read.width, strip.read.height, bgr, ProbeInputs.sha(bgr))
                val probability = detect(source, strip.detector, ::ready) { detectors++ }
                val boxes = try {
                    current.check()
                    BoxPipelineOpenCv(cleanup).run(strip.detector.outputShape, probability, source.width, source.height,
                        after = { current.check() })
                } finally { probability.fill(0f) }
                try {
                    ready()
                    diagnostics += StripDiagnostic(strip.index, strip.read, strip.core, strip.detector.width,
                        strip.detector.height, boxes.status.name, boxes.contourCount, boxes.boxes.size)
                    check(boxes.status == BoxPipelineContract.Status.COMPLETE) { boxes.status.name }
                    FullPageOcrContract.boxBudget(boxes.boxes.size, boxCount)
                    boxCount += boxes.boxes.size // Guard the whole strip before allocating its first crop.
                    val rows = arrayListOf<FullPageOcrContract.Candidate>()
                    for ((rank, box) in boxes.boxes.withIndex()) {
                        ready()
                        val provenance = FullPageOcrContract.provenance(meta.version, pageFixtureId, strip, box, rank)
                        val row = BoxPipelineContract.cropRow(pageFixtureId, box, rank, source.width, source.height)
                        val crop = GeometryOpenCvProbe(cleanup).crop(source, row, after = { current.check() })
                        try {
                            ready()
                            val sizes = listOf(RecognitionPacking.Size(crop.width, crop.height))
                            val plan = RecognitionPacking.plan(sizes)
                            val shape = FullPageOcrContract.outputShape(model.id, model.sha, model.vocabulary, plan.width)
                            require(plan.tensorRowToInput == listOf(0))
                            // The original row/indices remain exclusively in outer provenance.
                            val resized = CropRecognitionPipeline(cleanup).resize(
                                crop.copy(row = FullPageOcrContract.numericalRow(row)), plan.resizedWidths.single(),
                                afterAllocation = { current.check() })
                            val values = try { ready(); RecognitionPacking.pack(sizes, listOf(resized)) }
                                finally { resized.argb.fill(0) }
                            val input = try {
                                require(values.size == 3 * 48 * plan.width && values.size <= 147456 &&
                                    values.all { it.isFinite() && it in -1f..1f })
                                val storage = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
                                val floats = storage.asFloatBuffer(); floats.put(values).flip()
                                TensorInput(storage, floats, longArrayOf(1, 3, 48, plan.width.toLong()))
                            } finally { values.fill(0f) }
                            val decoded = try {
                                ready(); recognitions++
                                engine.infer(recognizer, input, shape, dictionary).also { ready() }
                            } finally { wipe(input) }
                            require(decoded.raw.size == 1)
                            val raw = decoded.raw.single()
                            chars = FullPageOcrContract.characterBudget(chars, raw.length)
                            // Empty strings, duplicates, fragments and non-core centers stay present.
                            rows += FullPageOcrContract.Candidate(provenance, model.id, raw, plan.width, decoded.shape[1].toInt())
                        } finally { crop.bgr.fill(0); crop.sourceToPreRotation.fill(0.0); crop.preRotationToSource.fill(0.0) }
                    }
                    ready(); rows.toList()
                } finally { boxes.mask.fill(0); boxes.dilated?.fill(0) }
            } catch (e: RuntimeException) {
                failure = when (e) {
                    is CancellationException -> "CANCELLED_OR_STALE"
                    is ProbeFailure -> e.code
                    else -> e.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: e.javaClass.simpleName
                }
                throw e
            }
        }
        val complete = result.rejection == null && failure == null && !ort.uncertain && cleanup.balanced &&
            closeAttempts == 1 && closed == 1
        return PageResult(complete, failure ?: result.rejection?.name ?: if (!complete) "CLEANUP_UNCERTAIN" else null,
            FullPageOcrContract.commit(complete, result.values.flatten()), diagnostics.toList(), detectors, recognitions,
            closeAttempts, closed, result.rejection?.name)
    }

    private fun detect(source: GeometryImage, plan: DetectorPacking.Plan, ready: () -> Unit,
        invoked: () -> Unit): FloatArray {
        val argb = resize(source, plan, ready)
        val packed = try { ready(); DetectorPacking.pack(plan.width, plan.height, argb) } finally { argb.fill(0) }
        val input = try { tensor(packed, plan.inputShape) } finally { packed.fill(0f) }
        return try {
            ready(); invoked()
            infer(detector, input, plan.outputShape, ort) { output, _ ->
                // Owned bounded probability copy; native output closes before geometry begins.
                currentOutput(output, ready)
            }.also { values ->
                try { ready() } catch (e: Throwable) { values.fill(0f); throw e }
            }
        } finally { wipe(input) }
    }

    private fun currentOutput(output: java.nio.FloatBuffer, ready: () -> Unit): FloatArray {
        ready()
        require(output.remaining() <= DetectorPacking.MAX_PIXELS)
        return FloatArray(output.remaining()).also { output.duplicate().get(it) }
    }

    /** Same BGR INTER_LINEAR and detector rounding as the frozen reference test. */
    private fun resize(source: GeometryImage, plan: DetectorPacking.Plan, ready: () -> Unit): IntArray {
        require(plan == DetectorPacking.plan(source.width, source.height))
        require(source.bgr.size == GeometryProbeContract.pixels(source.width, source.height) * 3)
        val owned = arrayListOf<Pair<String, Mat>>()
        fun own(kind: String, mat: Mat): Mat {
            owned += kind to mat; cleanup.acquired[kind] = (cleanup.acquired[kind] ?: 0) + 1; return mat
        }
        ready()
        try {
            val src = own("fullPageResizeSource", Mat(source.height, source.width, CvType.CV_8UC3))
            val dst = own("fullPageResizeDestination", Mat(plan.height, plan.width, CvType.CV_8UC3))
            check(src.put(0, 0, source.bgr) == source.bgr.size)
            ready()
            Imgproc.resize(src, dst, Size(plan.width.toDouble(), plan.height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            ready()
            check(dst.type() == CvType.CV_8UC3 && dst.rows() == plan.height && dst.cols() == plan.width)
            val bytes = ByteArray(DetectorPacking.elements(plan.inputShape, 3))
            try {
                check(dst.get(0, 0, bytes) == bytes.size)
                return IntArray(bytes.size / 3) { i -> -0x1000000 or (bytes[3 * i].toInt() and 255) or
                    ((bytes[3 * i + 1].toInt() and 255) shl 8) or ((bytes[3 * i + 2].toInt() and 255) shl 16) }
            } finally { bytes.fill(0) }
        } finally {
            var failure: Throwable? = null
            owned.asReversed().forEach { (kind, mat) ->
                cleanup.attempts[kind] = (cleanup.attempts[kind] ?: 0) + 1
                try { mat.release(); check(mat.empty()); cleanup.released[kind] = (cleanup.released[kind] ?: 0) + 1 }
                catch (e: Throwable) { if (failure == null) failure = e else failure!!.addSuppressed(e) }
            }
            failure?.let { throw it }
        }
    }

    private fun wipe(input: TensorInput) {
        val floats = input.storage.asFloatBuffer()
        for (i in 0 until floats.capacity()) floats.put(i, 0f)
    }
}
