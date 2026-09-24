package com.kandong.modelprobe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Packaged synthetic PNGs only. Neither screen input nor a production preprocessing choice. */
@RunWith(AndroidJUnit4::class)
internal class OriginalImageProbeTest : DetectorProbeTestSupport() {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val assets = instrumentation.context.assets
    private var bitmapOpened = 0
    private var bitmapRecycled = 0
    private var matOpened = 0
    private var matReleaseAttempts = 0
    private var matReleased = 0

    private fun bgr(argb: IntArray): ByteArray {
        require(argb.size in 1..DetectorPacking.MAX_PIXELS && argb.all { it ushr 24 == 255 })
        return ByteArray(argb.size * 3).also { bytes ->
            argb.forEachIndexed { i, p -> for (c in 0..2) bytes[i * 3 + c] = (p ushr (c * 8)).toByte() }
        }
    }
    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        require(it.all { p -> p ushr 24 == 255 })
    }
    private fun recycle(bitmap: Bitmap) { check(!bitmap.isRecycled); bitmap.recycle(); bitmapRecycled++ }
    private fun release(mat: Mat) {
        matReleaseAttempts++
        mat.release()
        check(mat.empty())
        matReleased++ // Pixel allocation freed. Native header deletion remains owned by OpenCV's finalizer.
    }
    private fun resizeOpenCv(argb: IntArray, width: Int, height: Int, outWidth: Int, outHeight: Int,
        afterResize: () -> Unit = {}): IntArray {
        require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= DetectorPacking.MAX_PIXELS)
        require(outWidth in 1..2048 && outHeight in 1..2048 && outWidth.toLong() * outHeight <= DetectorPacking.MAX_PIXELS)
        require(argb.size.toLong() == width.toLong() * height)
        val bytes = bgr(argb) // All limits and opacity checked before any native allocation.
        val source = Mat(height, width, CvType.CV_8UC3).also { matOpened++ }
        try {
            check(source.put(0, 0, bytes) == bytes.size)
            val resized = Mat().also { matOpened++ }
            try {
                Imgproc.resize(source, resized, Size(outWidth.toDouble(), outHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
                check(resized.rows() == outHeight && resized.cols() == outWidth && resized.type() == CvType.CV_8UC3)
                afterResize()
                val output = ByteArray(outWidth * outHeight * 3)
                check(resized.get(0, 0, output) == output.size)
                return IntArray(outWidth * outHeight) { i -> 0xff000000.toInt() or
                    (output[i * 3].toInt() and 255) or ((output[i * 3 + 1].toInt() and 255) shl 8) or
                    ((output[i * 3 + 2].toInt() and 255) shl 16) }
            } finally { release(resized) }
        } finally { release(source) }
    }
    private fun resizeBitmap(source: Bitmap, width: Int, height: Int): IntArray {
        DetectorPacking.elements(longArrayOf(1, 3, height.toLong(), width.toLong()), 3)
        val resized = Bitmap.createScaledBitmap(source, width, height, true)
        if (resized !== source) bitmapOpened++
        try { return pixels(resized) }
        finally { if (resized !== source) recycle(resized) }
    }
    private fun decode(reader: DetectorProbeInputs, case: DetectorCase, files: JSONObject): Bitmap {
        val raw = reader.asset(case.source)
        require(case.sourceWidth.toLong() * case.sourceHeight <= DetectorPacking.MAX_PIXELS)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        require(bounds.outWidth == case.sourceWidth && bounds.outHeight == case.sourceHeight && bounds.outMimeType == "image/png")
        val options = BitmapFactory.Options().apply {
            inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(raw, 0, raw.size, options)).also { bitmapOpened++ }
        try {
            check(bitmap.width == case.sourceWidth && bitmap.height == case.sourceHeight && bitmap.config == Bitmap.Config.ARGB_8888)
            ProbeInputs.verifyHash(bgr(pixels(bitmap)), files.getJSONObject(case.source).getString("rawBgrSha256"))
            return bitmap
        } catch (e: Throwable) { recycle(bitmap); throw e }
    }
    private fun pixelMetrics(actual: IntArray, reference: IntArray): JSONObject {
        require(actual.size == reference.size && actual.isNotEmpty())
        var changes = 0; var maxError = 0; var sum = 0L
        for (i in actual.indices) for (c in 0..2) {
            val error = abs(((actual[i] ushr (8 * c)) and 255) - ((reference[i] ushr (8 * c)) and 255))
            if (error != 0) changes++
            maxError = maxOf(maxError, error); sum += error
        }
        return JSONObject().put("comparedBgrChannels", actual.size * 3).put("changedBgrChannels", changes)
            .put("maxChannelError", maxError).put("meanChannelError", sum.toDouble() / (actual.size * 3))
    }
    private fun <T> withOpenCv(block: () -> T): T {
        check(OpenCVLoader.initLocal()) { "OPENCV_LOAD" }
        check(Core.getVersionString() == "5.0.0") { "OPENCV_VERSION" }
        val previous = Core.getNumThreads()
        try { Core.setNumThreads(1); check(Core.getNumThreads() == 1); return block() }
        finally { Core.setNumThreads(previous) }
    }

    @Test fun resizeKeepsBgrChannelsAndInterpolatesAtPixelCenters() = withOpenCv {
        val source = intArrayOf(0xff204060.toInt(), 0xff6080a0.toInt())
        assertArrayEquals(intArrayOf(0xff204060.toInt(), 0xff406080.toInt(), 0xff6080a0.toInt()),
            resizeOpenCv(source, 2, 1, 3, 1))
        assertEquals(2, matOpened); assertEquals(matOpened, matReleased)
        assertEquals(matOpened, matReleaseAttempts)
    }

    @Test fun resizeValidatesBeforeAllocationAndReleasesOnFailure() = withOpenCv {
        val source = intArrayOf(0xff204060.toInt())
        for ((w, h) in listOf(0 to 1, 4097 to 1, 4096 to 4096, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { resizeOpenCv(source, w, h, 32, 32) }
        }
        assertThrows(IllegalArgumentException::class.java) { resizeOpenCv(source, 1, 1, 2048, 2048) }
        assertThrows(IllegalArgumentException::class.java) { resizeOpenCv(intArrayOf(0x112233), 1, 1, 32, 32) }
        assertThrows(IllegalArgumentException::class.java) { resizeOpenCv(source, 2, 2, 32, 32) }
        assertEquals(0, matOpened)
        val failure = assertThrows(IllegalStateException::class.java) {
            resizeOpenCv(source, 1, 1, 32, 32) { throw IllegalStateException("deliberate-after-native-resize") }
        }
        assertEquals("deliberate-after-native-resize", failure.message)
        assertEquals(2, matOpened); assertEquals(matOpened, matReleaseAttempts); assertEquals(matOpened, matReleased)
        assertTrue(resizeOpenCv(source, 1, 1, 32, 32).all { it == source[0] })
        assertEquals(4, matOpened); assertEquals(matOpened, matReleased)
    }

    @Test fun compareOriginalPngThroughBothResizersAndDetector() {
        val atomic = AtomicFile(File(instrumentation.targetContext.filesDir, "original-image-probe-report.json"))
        atomic.delete()
        val started = SystemClock.elapsedRealtime()
        val cleanup = Cleanup(); val rows = JSONArray()
        val report = JSONObject().put("schema", 1).put("runId", UUID.randomUUID().toString())
            .put("scope", "synthetic_original_PNG_resize_pack_detector_only_not_boxes_crops_OCR_quality_or_real_screens")
            .put("fixtureManifestSha256", DetectorProbeInputs.MANIFEST_SHA).put("modelSha256", DetectorProbeInputs.MODEL_SHA)
            .put("api", Build.VERSION.SDK_INT).put("deviceModel", Build.MODEL).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("openCvArtifact", "org.opencv:opencv:5.0.0.1")
            .put("openCvAarSha256", "edb1406a223d2820460b8366a790238b400f5d5c9ea2e98d44b889f0f3c66849")
            .put("sourceDecode", "BitmapFactory ARGB_8888 sRGB inScaled=false; raw BGR hash authenticated")
            .put("acceptance", JSONObject().put("openCvPixelDifferences", 0).put("openCvInputBitDifferences", 0)
                .put("absoluteTolerance", DetectorComparison.ATOL).put("relativeTolerance", DetectorComparison.RTOL)
                .put("meanAbsoluteMax", DetectorComparison.MEAN_ABS_MAX).put("maskFlipsMax", 0)
                .put("bitmapPolicy", "observational baseline; mismatches recorded without changing golden data or tolerance"))
            .put("matLifetime", "release() frees pixel storage; native headers are finalized by OpenCV")
            .put("cases", rows)
        var failure: Throwable? = null; var opencvParity = true; var baselineParity = true
        try {
            withOpenCv {
                report.put("openCvRuntime", Core.getVersionString()).put("openCvThreads", Core.getNumThreads())
                    .put("openCvBuildInfoSha256", ProbeInputs.sha(Core.getBuildInformation().toByteArray()))
                    .put("openCvOptimized", Core.useOptimized())
                val raw = assets.open("detector-v1/manifest.json").use { ProbeInputs.bounded(it, DetectorProbeInputs.MANIFEST_CAP) }
                ProbeInputs.verifyHash(raw, DetectorProbeInputs.MANIFEST_SHA)
                val files = JSONObject(String(raw, Charsets.UTF_8)).getJSONObject("files")
                val reader = DetectorProbeInputs { assets.open(it) }; val cases = reader.manifest()
                val engine = OrtProbeEngine(cleanup)
                require(engine.runtime == "1.30.0"); report.put("ortRuntime", engine.runtime)
                engine.withModel(reader.model()) { session ->
                    for (case in cases) {
                        val row = JSONObject().put("id", case.id).put("sourceSha256", reader.metadata(case.source).sha)
                            .put("sourceBgrSha256", files.getJSONObject(case.source).getString("rawBgrSha256"))
                            .put("inputShape", JSONArray(case.plan.inputShape.toList())).put("status", "started")
                        rows.put(row)
                        val reference = reader.load(case)
                        val source = decode(reader, case, files)
                        try {
                            for (route in listOf("bitmap", "opencv")) {
                                val result = JSONObject().put("method", if (route == "bitmap") "createScaledBitmap filter=true" else "CV_8UC3 BGR INTER_LINEAR")
                                row.put(route, result)
                                val resizeStart = SystemClock.elapsedRealtimeNanos()
                                val argb = if (route == "bitmap") resizeBitmap(source, case.plan.width, case.plan.height)
                                    else resizeOpenCv(pixels(source), case.sourceWidth, case.sourceHeight, case.plan.width, case.plan.height)
                                result.put("resizeNs", SystemClock.elapsedRealtimeNanos() - resizeStart)
                                    .put("pixels", pixelMetrics(argb, reference.argb)).put("actualBgrSha256", ProbeInputs.sha(bgr(argb)))
                                val packed = DetectorPacking.pack(case.plan.width, case.plan.height, argb)
                                val bitDiffs = packed.indices.count { packed[it].toRawBits() != reference.referenceInput[it].toRawBits() }
                                result.put("inputBitDifferences", bitDiffs).put("actualInputSha256", ProbeInputs.sha(serialized(packed)))
                                val compared = infer(session, tensor(packed, case.plan.inputShape), case.plan.outputShape, cleanup) { output, shape ->
                                    result.put("actualOutputShape", JSONArray(shape.toList())).put("actualOutputSha256", floatHash(output))
                                    DetectorComparison.compare(output, reference.referenceOutput, reference.referenceMask)
                                }
                                val blankOk = case.id != "blank-negative-24" || compared.positivePixels == 0
                                val parity = argb.contentEquals(reference.argb) && bitDiffs == 0 && compared.passed && blankOk
                                result.put("comparison", metrics(compared)).put("fullNumericParityPassed", parity)
                                    .put("blankMaskZero", if (case.id == "blank-negative-24") blankOk else JSONObject.NULL)
                                require(compared.nonFinite == 0 && compared.outOfRange == 0 && blankOk) { "INVALID_DETECTOR_RESULT" }
                                if (route == "opencv") opencvParity = opencvParity && parity else baselineParity = baselineParity && parity
                            }
                            row.put("status", "compared")
                        } finally { recycle(source) }
                    }
                }
            }
        } catch (e: Throwable) { failure = e }
        finally {
            val clean = cleanup.opened == cleanup.closed && cleanup.opened == cleanup.closeAttempts && !cleanup.uncertain &&
                bitmapOpened == bitmapRecycled && matOpened == matReleaseAttempts && matOpened == matReleased
            val completed = failure == null && rows.length() == 10 && clean
            report.put("comparisonCompleted", completed).put("openCvNumericParityPassed", completed && opencvParity)
                .put("bitmapNumericParityPassed", completed && baselineParity)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("bitmapOpened", bitmapOpened).put("bitmapRecycled", bitmapRecycled)
                .put("matOpened", matOpened).put("matReleaseAttempts", matReleaseAttempts).put("matBuffersReleased", matReleased)
                .put("ortOpened", cleanup.opened).put("ortCloseAttempts", cleanup.closeAttempts).put("ortClosed", cleanup.closed)
                .put("cleanupUncertain", !clean).put("failureClass", failure?.javaClass?.simpleName ?: JSONObject.NULL)
            save(atomic, report) // Persist differences and failures before applying the predeclared gate.
        }
        failure?.let { throw it }
        assertTrue("Comparison incomplete; inspect private original-image-probe-report.json", report.getBoolean("comparisonCompleted"))
        assertTrue("OpenCV original-image numeric parity failed; goldens remain unchanged", report.getBoolean("openCvNumericParityPassed"))
    }
}
