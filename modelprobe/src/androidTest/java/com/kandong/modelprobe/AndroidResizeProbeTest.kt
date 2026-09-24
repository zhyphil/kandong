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
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/** Test-only comparison. No screen, intent image, external file, network or UI input. */
@RunWith(AndroidJUnit4::class)
class AndroidResizeProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testAssets = instrumentation.context.assets
    private val target = instrumentation.targetContext
    private var bitmapOpened = 0
    private var bitmapClosed = 0
    private val manifestSha = "dd09f0b5d98e5cd2d00f0fc61bf65c89321815eb528cb63aac9e4ff73100b3be"
    private lateinit var files: JSONObject
    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
    private fun JSONArray.longs() = LongArray(length()) { getLong(it) }
    private fun bytes(path: String): ByteArray {
        check(path.matches(Regex("[a-z0-9.-]+")) && files.has(path))
        return testAssets.open(path).use { ProbeInputs.bounded(it, 256 * 1024) }
            .also { ProbeInputs.verifyHash(it, files.getJSONObject(path).getString("sha256")) }
    }
    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        check(it.all { p -> p ushr 24 == 255 })
    }
    private fun bgr(pixels: IntArray): ByteArray = ByteArray(pixels.size * 3).also { raw ->
        pixels.forEachIndexed { i, p -> for (c in 0..2) raw[i * 3 + c] = (p ushr (c * 8)).toByte() }
    }
    private fun decode(path: String): Bitmap {
        val data = bytes(path)
        val meta = files.getJSONObject(path)
        val width = meta.getInt("width"); val height = meta.getInt("height")
        check(width in 1..1024 && height in 1..2048 && width.toLong() * height <= 100_000)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        check(bounds.outWidth == width && bounds.outHeight == height)
        val options = BitmapFactory.Options().apply {
            inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(data, 0, data.size, options))
        bitmapOpened++
        try {
            check(bitmap.width == width && bitmap.height == height && bitmap.config == Bitmap.Config.ARGB_8888)
            ProbeInputs.verifyHash(bgr(pixels(bitmap)), meta.getString("rawBgrSha256"))
            return bitmap
        } catch (e: Throwable) { recycle(bitmap); throw e }
    }
    private fun recycle(bitmap: Bitmap) { check(!bitmap.isRecycled); bitmap.recycle(); bitmapClosed++ }
    private fun serialized(values: FloatArray) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        .also { out -> values.forEach { out.putFloat(it) } }.array()
    private fun tensor(values: FloatArray, shape: LongArray): TensorInput {
        check(values.size.toLong() == ProbeInputs.product(shape, 1_200_000))
        val storage = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        val floats = storage.asFloatBuffer(); floats.put(values).flip()
        return TensorInput(storage, floats, shape.copyOf())
    }
    private fun rgbDelta(actual: IntArray, expected: IntArray): JSONObject {
        check(actual.size == expected.size && actual.isNotEmpty())
        var changed = 0; var max = 0; var sum = 0L
        for (i in actual.indices) for (c in 0..2) {
            val delta = abs(((actual[i] ushr (c * 8)) and 255) - ((expected[i] ushr (c * 8)) and 255))
            if (delta != 0) changed++
            max = maxOf(max, delta); sum += delta
        }
        return JSONObject().put("comparedRgbChannels", actual.size * 3).put("changedRgbChannels", changed)
            .put("maxAbsChannelError", max).put("meanAbsChannelError", sum.toDouble() / (actual.size * 3))
    }
    private fun floatDelta(actual: FloatArray, expected: ByteArray): JSONObject {
        check(actual.isNotEmpty() && actual.size * 4 == expected.size)
        val reference = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        var changed = 0; var max = 0.0; var sum = 0.0
        actual.forEach {
            val other = reference.get(); check(it.isFinite() && other.isFinite())
            if (it.toRawBits() != other.toRawBits()) changed++
            val delta = abs(it.toDouble() - other.toDouble()); max = maxOf(max, delta); sum += delta
        }
        return JSONObject().put("comparedFloats", actual.size).put("changedFloatBits", changed)
            .put("maxAbsFloatError", max).put("meanAbsFloatError", sum / actual.size)
    }
    @Test fun comparisonMetricsDistinguishChannelsBitsAndMagnitude() {
        val rgb = rgbDelta(intArrayOf(0xff112233.toInt()), intArrayOf(0xff10243a.toInt()))
        assertEquals(3, rgb.getInt("changedRgbChannels")); assertEquals(7, rgb.getInt("maxAbsChannelError"))
        assertEquals(10.0 / 3, rgb.getDouble("meanAbsChannelError"), 1e-12)
        val floats = floatDelta(floatArrayOf(0f, 0.5f, -1f), serialized(floatArrayOf(-0f, 0.25f, -1f)))
        assertEquals(2, floats.getInt("changedFloatBits")); assertEquals(0.25, floats.getDouble("maxAbsFloatError"), 0.0)
        assertEquals(0.25 / 3, floats.getDouble("meanAbsFloatError"), 1e-12)
    }
    @Test fun extremeDimensionsKeepConstantColorAndCorrectOwnership() {
        var copies = 0; var aliases = 0
        for (mutable in listOf(true, false)) for ((w, h) in listOf(1 to 1, 1 to 48, 48 to 1, 17 to 7)) {
            val seed = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff2468ac.toInt()) }
            bitmapOpened++
            val source = if (mutable) seed else try {
                checkNotNull(seed.copy(Bitmap.Config.ARGB_8888, false)).also { bitmapOpened++ }
            } finally { recycle(seed) }
            try {
                val unchanged = Bitmap.createScaledBitmap(source, w, h, true)
                if (unchanged === source) aliases++ else { copies++; bitmapOpened++ }
                try {
                    assertEquals(w, unchanged.width); assertEquals(h, unchanged.height)
                    assertTrue(pixels(unchanged).all { it == 0xff2468ac.toInt() })
                } finally { if (unchanged !== source) recycle(unchanged) }
                val scaled = Bitmap.createScaledBitmap(source, 320, 48, true)
                if (scaled !== source) bitmapOpened++
                try { assertTrue(pixels(scaled).all { it == 0xff2468ac.toInt() }) }
                finally { if (scaled !== source) recycle(scaled) }
            } finally { recycle(source) }
        }
        assertEquals(8, copies + aliases)
        assertEquals(bitmapOpened, bitmapClosed)
        println("RESIZE_OWNERSHIP aliases=$aliases copies=$copies opened=$bitmapOpened recycled=$bitmapClosed")
    }
    @Test fun compareBitmapResizeAndBothRecognizersOnFixedCrops() {
        val reportFile = File(target.filesDir, "resize-probe-report.json")
        val atomic = AtomicFile(reportFile); atomic.delete() // Never mistake a previous run for this run.
        val started = SystemClock.elapsedRealtime()
        val manifestRaw = testAssets.open("manifest.json").use { ProbeInputs.bounded(it, 128 * 1024) }
        ProbeInputs.verifyHash(manifestRaw, manifestSha)
        val manifest = JSONObject(String(manifestRaw, Charsets.UTF_8)); files = manifest.getJSONObject("files")
        check(manifest.getInt("schema") == 1 && manifest.getInt("caseCount") == 6)
        check(manifest.getString("probeManifestSha256") == ProbeInputs.MANIFEST_SHA)
        val cases = manifest.getJSONArray("cases").objects()
        check(cases.map { it.getString("id") } == ProbeInputs.SOURCES + "color-controls")
        val inputs = ProbeInputs { target.assets.open(it) }; val probe = inputs.manifest()
        val tensors = linkedMapOf<String, FloatArray>(); val caseReports = JSONArray()
        for (case in cases) {
            val id = case.getString("id"); val rows = case.getJSONArray("rows").objects().sortedBy { it.getInt("originalIndex") }
            check(rows.map { it.getInt("originalIndex") } == rows.indices.toList())
            val sizes = rows.map { files.getJSONObject(it.getString("crop")).let { m ->
                RecognitionPacking.Size(m.getInt("width"), m.getInt("height")) } }
            val plan = RecognitionPacking.plan(sizes)
            val scaledRows = arrayListOf<RecognitionPacking.Resized>(); val pixelReports = JSONArray()
            rows.forEachIndexed { index, row ->
                val source = decode(row.getString("crop"))
                try {
                    val scaled = Bitmap.createScaledBitmap(source, plan.resizedWidths[index], 48, true)
                    if (scaled !== source) bitmapOpened++
                    try {
                        val actual = pixels(scaled)
                        val reference = decode(row.getString("resized"))
                        try {
                            check(scaled.width == reference.width && scaled.height == reference.height)
                            val delta = rgbDelta(actual, pixels(reference)).put("originalIndex", index)
                                .put("sourceWidth", source.width).put("sourceHeight", source.height)
                                .put("resizedWidth", scaled.width).put("actualBgrSha256", ProbeInputs.sha(bgr(actual)))
                            pixelReports.put(delta)
                        } finally { recycle(reference) }
                        scaledRows += RecognitionPacking.Resized(scaled.width, scaled.height, actual)
                    } finally { if (scaled !== source) recycle(scaled) }
                } finally { recycle(source) }
            }
            val values = RecognitionPacking.pack(sizes, scaledRows)
            val shape = case.getJSONArray("shape").longs()
            check(shape.contentEquals(longArrayOf(rows.size.toLong(), 3, 48, plan.width.toLong())))
            check(plan.tensorRowToInput == rows.sortedBy { it.getInt("tensorRow") }.map { it.getInt("originalIndex") })
            val compressed = bytes(case.getString("expectedTensor"))
            val reference = GZIPInputStream(ByteArrayInputStream(compressed)).use { ProbeInputs.bounded(it, values.size * 4, values.size * 4) }
            ProbeInputs.verifyHash(reference, case.getString("tensorSha256"))
            caseReports.put(floatDelta(values, reference).put("id", id).put("shape", JSONArray(shape.toList()))
                .put("actualTensorSha256", ProbeInputs.sha(serialized(values)))
                .put("referenceTensorSha256", case.getString("tensorSha256"))
                .put("tensorRowToInput", JSONArray(plan.tensorRowToInput)).put("pixels", pixelReports))
            tensors[id] = values
        }
        assertEquals(bitmapOpened, bitmapClosed)
        val cleanup = Cleanup(); val engine = OrtProbeEngine(cleanup); val modelReports = JSONArray()
        for (model in probe.models) {
            val dictionary = inputs.dictionary(model)
            engine.withModel(inputs.model(model)) { session ->
                for (task in probe.tasks.filter { it.model == model.id }) {
                    val baseline = engine.infer(session, inputs.tensor(task), task.outputShape, dictionary)
                    assertEquals(task.expectedRaw, baseline.raw); assertEquals(task.expectedArgmax, baseline.argmaxSha256)
                    val actual = engine.infer(session, tensor(checkNotNull(tensors[task.source]), task.shape), task.outputShape, dictionary)
                    modelReports.put(JSONObject().put("id", task.id).put("modelSha256", model.sha)
                        .put("baselineRaw", JSONArray(baseline.raw)).put("resizedRaw", JSONArray(actual.raw))
                        .put("rawMatchesBaseline", actual.raw == baseline.raw)
                        .put("baselineArgmaxSha256", baseline.argmaxSha256).put("resizedArgmaxSha256", actual.argmaxSha256)
                        .put("argmaxMatchesBaseline", actual.argmaxSha256 == baseline.argmaxSha256))
                }
            }
        }
        assertFalse(cleanup.uncertain); assertEquals(cleanup.opened, cleanup.closed); assertEquals(10, modelReports.length())
        val report = JSONObject().put("schema", 1).put("runId", UUID.randomUUID().toString())
            .put("scope", "Android_Bitmap_scaling_of_fixed_host_crops_not_full_OCR_or_quality_acceptance")
            .put("operationalStatus", "completed").put("fixtureManifestSha256", manifestSha)
            .put("api", Build.VERSION.SDK_INT).put("model", Build.MODEL).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("runtime", engine.runtime).put("elapsedMs", SystemClock.elapsedRealtime() - started)
            .put("scaler", "Bitmap.createScaledBitmap(filter=true); software_ARGB8888_sRGB; inScaled=false")
            .put("cases", caseReports).put("models", modelReports)
            .put("bitmapOpened", bitmapOpened).put("bitmapRecycled", bitmapClosed)
            .put("nativeOpened", cleanup.opened).put("nativeClosed", cleanup.closed)
            .put("allTenRawMatch", modelReports.objects().all { it.getBoolean("rawMatchesBaseline") })
            .put("allTenArgmaxMatch", modelReports.objects().all { it.getBoolean("argmaxMatchesBaseline") })
        val output = report.toString().toByteArray(Charsets.UTF_8); check(output.size <= 256 * 1024)
        val stream = atomic.startWrite()
        try { stream.write(output); atomic.finishWrite(stream) } catch (e: Throwable) { atomic.failWrite(stream); throw e }
        assertArrayEquals(output, atomic.openRead().use { ProbeInputs.bounded(it, 256 * 1024, output.size) })
        // Differences are evidence, never rewritten into a false assertion of quality acceptance.
        println("RESIZE_PROBE_COMPLETED rawMatches=" + report.getBoolean("allTenRawMatch"))
    }
}
