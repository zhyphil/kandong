package com.kandong.liveocr

import android.content.Context
import android.os.Looper
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Synchronous, offline, single-frame OCR. Call off main and keep RGBA immutable until return.
 * Caller owns/wipes RGBA and supplies consent, privacy filtering and freshness through isCurrent.
 * No capture, network, logging, disk writes, retained page state or automatic retries.
 */
class LiveOcrEngine(private val context: Context) {
    fun recognize(rgba: ByteArray, width: Int, height: Int, language: String,
        isCurrent: () -> Boolean): OcrPage {
        requireOcr(Looper.myLooper() != Looper.getMainLooper(), OcrFailure.MAIN_THREAD)
        requireOcr(running.compareAndSet(false, true), OcrFailure.BUSY)
        val ort = Cleanup()
        val geometry = GeometryCleanup()
        var oldThreads: Int? = null
        var restoreFailed = false
        try {
            requireOcr(!poisoned.get(), OcrFailure.CLEANUP_UNCERTAIN)
            val current = OcrCurrent(isCurrent)
            current.check()
            SingleFrameStripInput.validate(rgba, width, height)
            val model = OcrAssets.forLanguage(language)
            val assets = OcrAssets { context.applicationContext.assets.open(it) }
            requireOcr(OpenCVLoader.initLocal() && Core.VERSION == "5.0.0", OcrFailure.RUNTIME_VERSION)
            oldThreads = Core.getNumThreads()
            Core.setNumThreads(1)
            requireOcr(Core.getNumThreads() == 1, OcrFailure.RUNTIME_VERSION)
            current.check()
            val engine = OrtRuntime(ort, current)
            val dictionary = assets.dictionary(model)
            current.check()
            val detectorBytes = assets.detector()
            val candidates = try {
                current.check()
                engine.withModel(detectorBytes) { detector ->
                    val modelBytes = assets.model(model)
                    try {
                        current.check()
                        engine.withModel(modelBytes) { recognizer ->
                            FullPageOcrPipeline(engine, detector, recognizer, model, dictionary, geometry, current)
                                .run(rgba, width, height)
                        }
                    } finally { modelBytes.fill(0) }
                }
            } finally { detectorBytes.fill(0) }
            // Both model sessions/options must be closed before a single block can be published.
            requireOcr(ort.balanced && geometry.balanced, OcrFailure.CLEANUP_UNCERTAIN)
            oldThreads?.let { Core.setNumThreads(it) }
            oldThreads = null
            current.check()
            return OcrPageContract.publish(candidates, width, height, current)
        } catch (e: LiveOcrException) {
            throw e
        } catch (_: CancellationException) {
            throw LiveOcrException(OcrFailure.CANCELLED_OR_STALE)
        } catch (_: Exception) {
            throw LiveOcrException(OcrFailure.PIPELINE_FAILED)
        } catch (_: LinkageError) {
            throw LiveOcrException(OcrFailure.RUNTIME_VERSION)
        } catch (_: OutOfMemoryError) {
            throw LiveOcrException(OcrFailure.PIPELINE_FAILED)
        } finally {
            try {
                oldThreads?.let {
                    try { Core.setNumThreads(it) } catch (_: Throwable) { restoreFailed = true }
                }
                if (!ort.balanced || !geometry.balanced || restoreFailed) {
                    poisoned.set(true)
                    throw LiveOcrException(OcrFailure.CLEANUP_UNCERTAIN)
                }
            } finally { running.set(false) }
        }
    }

    private companion object {
        // OpenCV thread settings are process-global; never let two library instances race them.
        val running = AtomicBoolean(false)
        val poisoned = AtomicBoolean(false)
    }
}
