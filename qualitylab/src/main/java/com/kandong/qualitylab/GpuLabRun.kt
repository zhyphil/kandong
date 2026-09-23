package com.kandong.qualitylab

import com.kandong.graphics.GpuC
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException

/** One serial, bounded synthetic run; no View/Activity/real-page references are retained here. */
internal class GpuLabRun(private val folder: File, val fullSuite: Boolean,
    private val progress: (String) -> Unit) {
    val runId: String = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
    private val tests = JSONArray()
    private val benchmarks = JSONArray()
    private val allocations = JSONArray()
    private val packing = JSONArray()
    private val report = JSONObject()
        .put("schemaVersion", 1).put("runId", runId).put("startedAtEpochMs", System.currentTimeMillis())
        .put("state", "running").put("fullSuite", fullSuite).put("model", Build.MODEL)
        .put("manufacturer", Build.MANUFACTURER).put("api", Build.VERSION.SDK_INT)
        .put("appVersion", BuildConfig.VERSION_NAME).put("source", "fixed synthetic fixtures only")
        .put("gpu", JSONObject.NULL).put("initializationMs", JSONObject.NULL)
        .put("expectedTestCount", if (fullSuite) 48 else 0).put("completedTestCount", 0)
        .put("activeWork", "initialization")
        .put("sampleWidth", LabRenderer.WIDTH).put("sampleHeight", LabRenderer.HEIGHT)
        .put("validationScales", JSONArray(listOf(1, 2, 3, 5)))
        .put("benchmarkScales", JSONArray(listOf(2, 3, 5)))
        .put("maxInputPixelsPerImage", GpuLabChecks.MAX_INPUT_PIXELS)
        .put("maxOutputPixelsPerImage", GpuLabChecks.MAX_OUTPUT_PIXELS)
        .put("thresholds", JSONObject().put("rgbMaxPerFixturePerChannelCodeValues", GpuLabChecks.MAX_RGB_ERROR)
            .put("rgbMeanPerFixturePerChannelCodeValues", GpuLabChecks.MAX_MEAN_ERROR)
            .put("unit", "8-bit code values; divide by 255 for normalized error")
            .put("oneTimesExactRequired", true).put("alpha255Required", true)
            .put("dimensionsAndOrientationRequired", true))
        .put("tests", tests).put("benchmarks", benchmarks).put("allocations", allocations)
        .put("inputPacking", packing).put("numericalPassed", false).put("timingGate3and5", false)
        .put("allPassed", false).put("ready", false)
        .put("limitations", JSONArray(listOf(
            "Independent synthetic experiment; no production integration or automatic production acceptance.",
            "Opaque 8-bit RGBA only; no HDR, wide gamut, alpha compositing or captured pages.",
            "Per-image bounds; fixtures and scales run serially, never as one large atlas.",
            "Short debug workload; not FPS, battery, thermal, sustained performance or all-OEM evidence.",
            "Upload + GPU completion includes CPU submission/driver/synchronization; NOT GPU-only timing.",
            "End-to-end excludes input packing, EGL/shader initialization, texture/buffer allocation, UI and PNG.",
            "Readback + Bitmap includes glReadPixels, explicit RGBA-to-ARGB conversion and Bitmap creation.",
            "P95 uses nearest rank; with 15 measured rounds P95 is the maximum sample.",
            "Driver floating-point arithmetic can differ; thresholds are fixed and never relaxed."
        )))

    data class Result(val images: List<Bitmap>, val report: JSONObject)

    fun execute(): Result {
        val images = mutableListOf<Bitmap>()
        var sample: Bitmap? = null
        try {
            writeReport()
            GpuLabChecks.cancellation()
            val source = LabRenderer.sample()
            sample = source
            val pixels = IntArray(LabRenderer.WIDTH * LabRenderer.HEIGHT)
            source.getPixels(pixels, 0, LabRenderer.WIDTH, 0, 0, LabRenderer.WIDTH, LabRenderer.HEIGHT)
            val main = GpuLabFixtures.Fixture("synthetic-text", source.width, source.height, pixels)
            val input = pack(main)
            // use invokes close directly on this worker even on cancellation; no queued cleanup.
            GpuC().use { gpu ->
                report.put("gpu", JSONObject().put("renderer", gpu.renderer).put("version", gpu.version)
                    .put("coefficientMode", "CPU Float taps sampled from RGBA32F textures; no float render targets").put("arithmeticMode", gpu.arithmeticMode).put("vendor", gpu.vendor).put("shadingLanguage", gpu.shadingLanguage)
                    .put("maxTextureSize", gpu.maxTextureSize).put("maxViewportDims", JSONArray(gpu.maxViewportDims.toList()))
                    .put("context", "EGL14 GLES3 pbuffer 1x1; offscreen RGBA8 FBOs"))
                    .put("initializationMs", gpu.initializationMs)
                if (fullSuite) {
                    validate(gpu, main, input)
                    for (fixture in GpuLabFixtures.small()) {
                        GpuLabChecks.cancellation()
                        validate(gpu, fixture, pack(fixture))
                    }
                    val numericalPassed = tests.length() == 48 && (0 until tests.length()).all { tests.getJSONObject(it).getBoolean("passed") }
                    report.put("expectedTestCount", 48).put("numericalPassed", numericalPassed)
                    for (scale in listOf(2, 3, 5)) benchmark(gpu, source, pixels, input, scale)
                    val gateRows = (0 until benchmarks.length()).map { benchmarks.getJSONObject(it) }
                        .filter { it.getInt("scale") in listOf(3, 5) }
                    val timingPassed = gateRows.size == 2 && gateRows.all { it.getBoolean("timingPassed") }
                    report.put("timingGate3and5", timingPassed).put("allPassed", numericalPassed && timingPassed)
                }
                progress("生成 3 倍 CPU C / GPU C 对照…")
                report.put("activeWork", "comparison synthetic-text 3x")
                allocate(gpu, input, 3, "comparison", main.name)
                images += LabRenderer.render(source, pixels, 3, 2)
                GpuLabChecks.cancellation()
                images += gpu.render(input, 3).bitmap
                GpuLabChecks.cancellation()
                exportComparison(images)
            }
            // A run is ready only AFTER synchronous EGL cleanup and successful artifact writes.
            GpuLabChecks.cancellation()
            report.put("state", if (fullSuite) "completed" else "comparison-ready")
                .put("activeWork", "none")
                .put("ready", true).put("finishedAtEpochMs", System.currentTimeMillis())
                .put("comparison", JSONObject().put("file", "comparison-gpu-3x.png")
                    .put("runId", runId).put("scale", 3).put("imageWidth", 960).put("imageHeight", 480))
            writeReport()
            return Result(images.toList(), report)
        } catch (failure: Throwable) {
            images.forEach { it.recycle() }
            report.put("state", if (failure is CancellationException) "cancelled" else "error")
                .put("ready", false).put("allPassed", false)
                .put("finishedAtEpochMs", System.currentTimeMillis())
                .put("error", "${failure.javaClass.simpleName}: ${failure.message}".take(6000))
                .put("cleanupErrors", JSONArray(failure.suppressed.map { "${it.javaClass.simpleName}: ${it.message}" }))
            try { writeReport() } catch (writeFailure: Throwable) { failure.addSuppressed(writeFailure) }
            throw failure
        } finally {
            sample?.recycle() // Source never enters the UI and no queued job shares it.
        }
    }

    private fun pack(fixture: GpuLabFixtures.Fixture): GpuC.Input {
        GpuLabChecks.shape(fixture.width, fixture.height, fixture.pixels.size, 1)
        GpuLabChecks.opaque(fixture.pixels)
        return GpuC.Input.pack(fixture.pixels, fixture.width, fixture.height).also {
            packing.put(JSONObject().put("fixture", fixture.name).put("width", fixture.width)
                .put("height", fixture.height).put("packingMs", it.packingMs)
                .put("scope", "Direct RGBA buffer allocation and explicit ARGB-to-RGBA packing, excluded from sample timing"))
        }
    }

    private fun allocate(gpu: GpuC, input: GpuC.Input, scale: Int, phase: String, fixture: String) {
        val shape = GpuLabChecks.shape(input.width, input.height, input.count, scale)
        GpuLabChecks.deviceLimits(input.width, input.height, shape, gpu.maxTextureSize, gpu.maxViewportDims)
        allocations.put(JSONObject().put("phase", phase).put("fixture", fixture).put("scale", scale)
            .put("allocationMs", gpu.prepare(input, scale)).put("sourceWidth", input.width)
            .put("sourceHeight", input.height).put("outputWidth", input.width * scale)
            .put("outputHeight", input.height * scale)
            .put("scope", "Texture/readback capacity allocation + completion; zero if reused"))
    }

    private fun validate(gpu: GpuC, fixture: GpuLabFixtures.Fixture, input: GpuC.Input) {
        val original = fixture.pixels.clone()
        for (scale in listOf(1, 2, 3, 5)) {
            report.put("activeWork", "validation ${fixture.name} ${scale}x")
            progress("逐通道验证：${fixture.name} · ${scale}倍")
            GpuLabChecks.cancellation()
            val shape = GpuLabChecks.shape(fixture.width, fixture.height, fixture.pixels.size, scale)
            val expected = PixelEnhancer.enlarge(fixture.pixels, fixture.width, fixture.height, scale, true)
            allocate(gpu, input, scale, "validation", fixture.name)
            val output = gpu.render(input, scale)
            try {
                val bitmap = output.bitmap
                val actual = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(actual, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val metrics = GpuLabChecks.compare(expected, actual, shape.width, shape.height,
                    bitmap.width, bitmap.height, scale == 1)
                val markers = if (fixture.name == "asymmetric-colored-corners" && metrics.dimensionsPassed)
                    GpuLabChecks.corners(original, fixture.width, fixture.height, actual, bitmap.width, bitmap.height) else emptyList()
                val orientation = if (fixture.name == "asymmetric-colored-corners") markers.size == 4 && markers.all { it.passed } else true
                val unchanged = original.contentEquals(fixture.pixels)
                val row = JSONObject().put("fixture", fixture.name).put("scale", scale)
                    .put("sourceWidth", fixture.width).put("sourceHeight", fixture.height)
                    .put("expectedWidth", shape.width).put("expectedHeight", shape.height)
                    .put("actualWidth", bitmap.width).put("actualHeight", bitmap.height)
                    .put("dimensionsPassed", metrics.dimensionsPassed).put("alpha255", metrics.alpha255)
                    .put("oneTimesExactRequired", scale == 1).put("exact", metrics.exact)
                    .put("inputUnchanged", unchanged).put("gpuDrawPasses", if (scale == 1) 1 else 2)
                    .put("densityNone", bitmap.density == Bitmap.DENSITY_NONE)
                    .put("rgb", JSONArray(metrics.channels.mapIndexed { index, channel ->
                        JSONObject().put("channel", listOf("R", "G", "B")[index]).put("maxCodeValues", channel.max)
                            .put("sumAbsoluteCodeValues", channel.sum).put("pixelCount", channel.count)
                            .put("meanCodeValues", channel.mean).put("maxNormalized", channel.max / 255.0)
                            .put("meanNormalized", channel.mean / 255.0).put("passed", channel.passed)
                    }))
                    .put("asymmetricMarkerCheckApplicable", markers.isNotEmpty())
                    .put("channelOrderAndOrientationPassed", orientation)
                    .put("cornerMarkers", JSONArray(markers.map {
                        JSONObject().put("corner", it.name).put("expectedARGB", hex(it.expected))
                            .put("actualARGB", hex(it.actual)).put("passed", it.passed)
                    }))
                    .put("passed", metrics.passed && orientation && unchanged && bitmap.density == Bitmap.DENSITY_NONE)
                tests.put(row)
                report.put("completedTestCount", tests.length())
            } finally { output.bitmap.recycle() }
        }
    }

    private fun benchmark(gpu: GpuC, source: Bitmap, pixels: IntArray, input: GpuC.Input, scale: Int) {
        report.put("activeWork", "benchmark synthetic-text ${scale}x")
        progress("交错测速：${scale}倍 · 3次预热 / 15轮")
        allocate(gpu, input, scale, "benchmark", "synthetic-text")
        val cpu = mutableListOf<Double>()
        val completion = mutableListOf<Double>()
        val readback = mutableListOf<Double>()
        val total = mutableListOf<Double>()
        val rounds = JSONArray()
        val row = JSONObject().put("scale", scale).put("sourceWidth", input.width).put("sourceHeight", input.height)
            .put("outputWidth", input.width * scale).put("outputHeight", input.height * scale)
            .put("warmupsPerPath", 3).put("requestedRounds", 15).put("completedRounds", 0)
            .put("p95Definition", "nearest-rank ceil(0.95*N); N=15 => maximum")
            .put("freshUploadEveryGpuSample", true).put("allocationsExcluded", true)
            .put("rounds", rounds).put("timingPassed", false)
        benchmarks.put(row) // Keep actual partial results even if a later sample fails/cancels.
        fun cpuSample(): Double {
            GpuLabChecks.cancellation()
            val start = System.nanoTime()
            val bitmap = LabRenderer.render(source, pixels, scale, 2)
            val ms = (System.nanoTime() - start) / 1e6
            try {
                check(bitmap.width == input.width * scale && bitmap.height == input.height * scale) { "CPU output shape mismatch" }
            } finally { bitmap.recycle() }
            return ms
        }
        fun gpuSample(): GpuC.Timing {
            val output = gpu.render(input, scale)
            try {
                check(output.bitmap.width == input.width * scale && output.bitmap.height == input.height * scale) { "GPU output shape mismatch" }
                check(output.timing.allocationMs == 0.0) { "Unexpected allocation inside benchmark sample" }
                return output.timing
            } finally { output.bitmap.recycle() }
        }
        repeat(3) { warmup ->
            if (warmup % 2 == 0) { cpuSample(); gpuSample() } else { gpuSample(); cpuSample() }
        }
        repeat(15) { index ->
            GpuLabChecks.cancellation()
            progress("交错测速：${scale}倍 · ${index + 1}/15轮")
            val round = JSONObject().put("round", index + 1)
                .put("order", if (index % 2 == 0) "CPU,GPU" else "GPU,CPU")
            rounds.put(round)
            fun measureCpu() { val ms = cpuSample(); cpu += ms; round.put("cpuRenderAndBitmapMs", ms) }
            fun measureGpu() {
                val time = gpuSample()
                completion += time.uploadAndCompletionMs
                readback += time.readbackAndBitmapMs
                total += time.totalMs
                round.put("gpuUploadAndCompletionMs", time.uploadAndCompletionMs)
                    .put("gpuReadbackAndBitmapMs", time.readbackAndBitmapMs).put("gpuEndToEndMs", time.totalMs)
            }
            if (index % 2 == 0) { measureCpu(); measureGpu() } else { measureGpu(); measureCpu() }
            row.put("completedRounds", index + 1)
        }
        val cpuStats = GpuLabChecks.stats(cpu)
        val completionStats = GpuLabChecks.stats(completion)
        val totalStats = GpuLabChecks.stats(total)
        row.put("cpuRenderAndBitmap", stats(cpuStats)).put("gpuUploadAndCompletion", stats(completionStats))
            .put("gpuReadbackAndBitmap", stats(GpuLabChecks.stats(readback))).put("gpuEndToEnd", stats(totalStats))
            .put("completionMedianLower", completionStats.median < cpuStats.median)
            .put("completionP95Lower", completionStats.p95 < cpuStats.p95)
            .put("endToEndMedianLower", totalStats.median < cpuStats.median)
            .put("endToEndP95Lower", totalStats.p95 < cpuStats.p95)
            .put("timingPassed", GpuLabChecks.faster(cpuStats, completionStats, totalStats))
    }

    private fun stats(values: GpuLabChecks.Stats) = JSONObject().put("samplesMs", JSONArray(values.samples))
        .put("medianMs", values.median).put("p95Ms", values.p95)

    private fun exportComparison(images: List<Bitmap>) {
        val width = images.first().width
        val rowHeight = images.first().height + 48
        val sheet = Bitmap.createBitmap(width, rowHeight * 2, Bitmap.Config.ARGB_8888)
        val temporary = File(folder, "comparison-$runId.tmp")
        try {
            sheet.density = Bitmap.DENSITY_NONE
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 23f }
            images.forEachIndexed { index, bitmap ->
                val y = index * rowHeight
                val name = if (index == 0) "CPU C reference" else "GPU C experiment"
                canvas.drawText("$name 3x  ${runId.substringBefore('-')}", 12f, y + 31f, paint)
                canvas.drawBitmap(bitmap, 0f, (y + 48).toFloat(), null)
            }
            temporary.outputStream().use { check(sheet.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encoding failed" } }
            GpuLabChecks.cancellation()
            check(temporary.renameTo(File(folder, "comparison-gpu-3x.png"))) { "PNG atomic rename failed" }
        } finally { sheet.recycle(); temporary.delete() }
    }

    private fun writeReport() {
        val temporary = File(folder, "gpu-report-$runId.tmp")
        try {
            temporary.writeText(report.toString(2))
            check(temporary.renameTo(File(folder, "gpu-report.json"))) { "Report atomic rename failed" }
        } finally { temporary.delete() }
    }

    private fun hex(pixel: Int) = java.lang.String.format(java.util.Locale.ROOT, "%08X", pixel)
}
