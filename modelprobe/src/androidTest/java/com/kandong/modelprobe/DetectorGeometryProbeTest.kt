package com.kandong.modelprobe

import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Synthetic primitive comparisons only; no ORT session, detection boxes, unclip or recognition. */
@RunWith(AndroidJUnit4::class)
internal class DetectorGeometryProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val assets = instrumentation.context.assets
    private val cleanup = GeometryCleanup()
    private val guardRows = JSONArray()
    private fun reader(open: (String) -> java.io.InputStream = { assets.open(it) },
        list: (String) -> List<String> = { assets.list(it)?.toList().orEmpty() }) = GeometryFixtureInputs(open, list, cleanup)

    private fun bytes(actual: ByteArray, reference: ByteArray): JSONObject {
        val compared = minOf(actual.size, reference.size)
        var changes = 0; var maximum = 0; var sum = 0L
        for (i in 0 until compared) {
            val error = abs((actual[i].toInt() and 255) - (reference[i].toInt() and 255))
            if (error != 0) changes++
            maximum = maxOf(maximum, error); sum += error
        }
        return JSONObject().put("comparedValues", compared).put("actualValues", actual.size).put("referenceValues", reference.size)
            .put("changedValues", changes).put("lengthDifference", actual.size - reference.size).put("maxAbs", maximum)
            .put("meanAbs", if (compared == 0) 0.0 else sum.toDouble() / compared)
            .put("passed", actual.size == reference.size && changes == 0)
    }
    private class Numbers(private val atol: Double, private val rtol: Double) {
        var count = 0; var violations = 0; var maxAbs = 0.0
        fun add(actual: Double, reference: Double) {
            count++
            val error = abs(actual - reference)
            if (!actual.isFinite() || !reference.isFinite() || error > atol + rtol * abs(reference)) violations++
            if (error.isFinite()) maxAbs = maxOf(maxAbs, error)
        }
        fun point(actual: GeometryProbeContract.Point, reference: GeometryProbeContract.Point) {
            add(actual.x, reference.x); add(actual.y, reference.y)
        }
        fun json() = JSONObject().put("comparedScalars", count).put("violations", violations).put("maxFiniteAbs", maxAbs)
            .put("absoluteTolerance", atol).put("relativeTolerance", rtol).put("passed", violations == 0 && count > 0)
    }
    private fun matrix(actual: DoubleArray, reference: DoubleArray): JSONObject {
        val a = GeometryProbeContract.normalize(actual); val r = GeometryProbeContract.normalize(reference)
        val result = Numbers(GeometryProbeContract.MATRIX_ATOL, GeometryProbeContract.MATRIX_RTOL)
        for (i in 0..8) result.add(a[i], r[i])
        return result.json()
    }
    private fun coordinates(actual: GeometryOpenCvProbe.CropResult): JSONObject {
        val forward = Numbers(1e-6, 1e-9); val inverse = Numbers(1e-6, 1e-9); val sourceRoundtrip = Numbers(1e-6, 1e-9)
        val cropRoundtrip = Numbers(1e-6, 1e-9); val rotatedForward = Numbers(1e-6, 1e-9); val rotatedInverse = Numbers(1e-6, 1e-9)
        val plan = actual.row.plan; val m = actual.sourceToPreRotation; val inv = actual.preRotationToSource
        val targets = GeometryProbeContract.target(plan)
        actual.row.quad.zip(targets).forEach { (source, target) ->
            val projected = GeometryProbeContract.project(m, source)
            forward.point(projected, target); inverse.point(GeometryProbeContract.project(inv, target), source)
            sourceRoundtrip.point(GeometryProbeContract.project(inv, projected), source)
            val rotated = GeometryProbeContract.rotatePixel(projected, plan)
            val expected = if (plan.rotate) GeometryProbeContract.Point(target.y, plan.width - 1.0 - target.x) else target
            rotatedForward.point(rotated, expected)
            rotatedInverse.point(GeometryProbeContract.project(inv, GeometryProbeContract.unrotatePixel(rotated, plan)), source)
        }
        // Pixel corners use W-1/H-1. Homography target edge corners above intentionally use W/H.
        for (x in listOf(0.0, plan.width - 1.0)) for (y in listOf(0.0, plan.height - 1.0)) {
            val pixel = GeometryProbeContract.Point(x, y)
            val source = GeometryProbeContract.project(inv, pixel)
            val restored = GeometryProbeContract.project(m, source)
            cropRoundtrip.point(restored, pixel)
            val rotated = GeometryProbeContract.rotatePixel(restored, plan)
            val expected = if (plan.rotate) GeometryProbeContract.Point(y, plan.width - 1.0 - x) else pixel
            rotatedForward.point(rotated, expected)
            rotatedInverse.point(GeometryProbeContract.project(inv, GeometryProbeContract.unrotatePixel(rotated, plan)), source)
        }
        val checks = linkedMapOf("sourceToTargetCorners" to forward.json(), "targetToSourceCorners" to inverse.json(),
            "sourceCornerRoundtrip" to sourceRoundtrip.json(), "cropPixelCornerRoundtrip" to cropRoundtrip.json(),
            "afterPixelRotation" to rotatedForward.json(), "afterPixelRotationInverse" to rotatedInverse.json())
        return JSONObject(checks.toMap()).put("passed", checks.values.all { it.getBoolean("passed") })
    }
    private fun write(atomic: AtomicFile, report: JSONObject) {
        val output = atomic.startWrite()
        try { output.write(report.toString(2).toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (e: Throwable) { atomic.failWrite(output); throw e }
    }
    private fun guard(id: String, block: () -> JSONObject) {
        val row = JSONObject().put("id", id).put("status", "started"); guardRows.put(row)
        try { row.put("metrics", block()).put("status", "completed") }
        catch (e: Throwable) { row.put("status", "failed").put("errorClass", e.javaClass.name) }
        row.put("cleanup", cleanup.json())
    }
    private fun rejected(block: () -> Unit): Boolean = try { block(); false } catch (_: RuntimeException) { true }

    private fun runGuards(fixtures: GeometryFixtureInputs, cases: List<GeometryCase>, engine: GeometryOpenCvProbe) {
        guard("invalid-shape-and-NaN-before-Mat") {
            val before = cleanup.matOpened
            val checks = listOf(
                rejected { engine.masks(longArrayOf(1, 1, 4096, 4096), floatArrayOf()) },
                rejected { engine.masks(longArrayOf(2, 1, 1, 1), floatArrayOf(1f)) },
                rejected { engine.masks(longArrayOf(1, 1, 1, 2), floatArrayOf(1f)) },
                rejected { engine.masks(longArrayOf(1, 1, 1, 1), floatArrayOf(Float.NaN)) })
            JSONObject().put("rejections", checks.count { it }).put("matAllocations", cleanup.matOpened - before)
                .put("passed", checks.all { it } && cleanup.matOpened == before)
        }
        val w = 128; val shape = longArrayOf(1, 1, w.toLong(), w.toLong())
        val separated = FloatArray(w * w) { if (it / w % 4 == 1 && it % w % 4 == 1) 1f else 0f }
        guard("generated-1024-separate-blocks") {
            val acquired = cleanup.acquired["contour"] ?: 0; val released = cleanup.released["contour"] ?: 0
            val r = engine.masks(shape, separated)
            val openedContours = (cleanup.acquired["contour"] ?: 0) - acquired
            val releasedContours = (cleanup.released["contour"] ?: 0) - released
            JSONObject().put("status", r.status.name).put("actualCount", r.contourCount).put("limit", r.candidateLimit)
                .put("rowRunBound", r.rowRunBound).put("contoursAcquired", openedContours).put("contoursReleased", releasedContours)
                .put("passed", r.status == GeometryProbeContract.Status.INCOMPLETE_CANDIDATE_LIMIT && r.contourCount == 1024 &&
                    r.candidateLimit == 1000 && openedContours == 1024 && releasedContours == 1024 && cleanup.balanced)
        }
        guard("dense-checkerboard-before-contour-allocation") {
            val before = cleanup.matOpened; val calls = cleanup.findContoursCalls
            val r = engine.masks(shape, FloatArray(w * w) { ((it / w + it % w) % 2).toFloat() })
            JSONObject().put("status", r.status.name).put("rowRunBound", r.rowRunBound).put("limit", GeometryProbeContract.CONTOUR_BUDGET)
                .put("matAllocations", cleanup.matOpened - before).put("findContoursCalls", cleanup.findContoursCalls - calls)
                .put("passed", r.status == GeometryProbeContract.Status.INCOMPLETE_CONTOUR_BUDGET && r.contourCount == null &&
                    cleanup.matOpened == before && cleanup.findContoursCalls == calls)
        }
        val vertical = cases.single { it.id == "vertical" }; val row = vertical.rows.single()
        val source = fixtures.image("vertical-source.png")
        for (stage in GeometryOpenCvProbe.Stage.values()) guard("deliberate-failure-after-${stage.name.lowercase()}") {
            var reached = false; var caught = false
            val before = cleanup.matOpened
            val inject: (GeometryOpenCvProbe.Stage) -> Unit = {
                if (it == stage) { reached = true; throw IllegalStateException("geometry-deliberate-failure") }
            }
            try {
                if (stage == GeometryOpenCvProbe.Stage.CONTOURS) engine.masks(shape, separated, inject)
                else engine.crop(source, row, inject)
            } catch (e: IllegalStateException) { caught = e.message == "geometry-deliberate-failure" }
            JSONObject().put("injectionReached", reached).put("expectedFailureCaught", caught).put("matAllocations", cleanup.matOpened - before)
                .put("passed", reached && caught && cleanup.matOpened > before && cleanup.balanced)
        }
        guard("singular-matrix-and-invalid-quad-before-Mat") {
            val before = cleanup.matOpened
            val singular = rejected { engine.crop(source, row.copy(referenceMatrix = List(9) { 0.0 })) }
            val repeated = rejected { engine.crop(source, row.copy(quad = List(4) { row.quad[0] })) }
            val badRaw = rejected { engine.crop(source.copy(bgr = source.bgr.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }), row) }
            JSONObject().put("rejections", listOf(singular, repeated, badRaw).count { it }).put("matAllocations", cleanup.matOpened - before)
                .put("passed", singular && repeated && badRaw && cleanup.matOpened == before)
        }
        for (variant in listOf("corrupt-source", "truncated-source", "compressed-wrong-hash", "truncated-compressed")) guard("asset-$variant") {
            var mutate = false
            val sourceAsset = variant.endsWith("source")
            val name = if (sourceAsset) "vertical-source.png" else "vertical-probability.f32z"
            val altered = reader(open = { path ->
                if (mutate && path == "${GeometryFixtureInputs.NAMESPACE}/$name") {
                    val raw = assets.open(path).use { ProbeInputs.bounded(it, GeometryFixtureInputs.FILE_CAP) }
                    ByteArrayInputStream(if (variant.startsWith("truncated")) raw.copyOf(raw.size - 1)
                        else raw.also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
                } else assets.open(path)
            })
            val loaded = altered.manifest(); mutate = true // Proves consumption revalidates, not just manifest preflight.
            val before = cleanup.matOpened; val bitmaps = cleanup.bitmapOpened
            val failed = rejected { if (sourceAsset) altered.image(name) else altered.probability(loaded.last()) }
            JSONObject().put("rejected", failed).put("matAllocations", cleanup.matOpened - before).put("bitmapAllocations", cleanup.bitmapOpened - bitmaps)
                .put("passed", failed && cleanup.matOpened == before && cleanup.bitmapOpened == bitmaps)
        }
        guard("compressed-decoded-hash-and-length") {
            val name = "vertical-probability.f32z"; val meta = fixtures.metadata(name); val raw = fixtures.asset(name)
            val before = cleanup.matOpened
            val badHash = rejected { DetectorProbeInputs.inflate(raw, meta.bytes, meta.sha, checkNotNull(meta.decodedBytes), "0".repeat(64)) }
            val badLength = rejected { DetectorProbeInputs.inflate(raw, meta.bytes, meta.sha, checkNotNull(meta.decodedBytes) - 1, checkNotNull(meta.decodedSha)) }
            JSONObject().put("rejections", listOf(badHash, badLength).count { it })
                .put("passed", badHash && badLength && cleanup.matOpened == before)
        }
        guard("PNG-dimension-cap-before-Bitmap-and-raw-hash-before-Mat") {
            val raw = fixtures.asset("vertical-source.png"); val meta = fixtures.metadata("vertical-source.png")
            val before = cleanup.matOpened; val bitmaps = cleanup.bitmapOpened
            val badSize = rejected { GeometryFixtureInputs.decodePng(raw, meta.copy(width = 4097), cleanup) }
            val noBitmapForBounds = cleanup.bitmapOpened == bitmaps
            val badHash = rejected { GeometryFixtureInputs.decodePng(raw, meta.copy(bgrSha = "0".repeat(64)), cleanup) }
            JSONObject().put("boundsRejected", badSize).put("rawHashRejected", badHash).put("boundsBeforeBitmap", noBitmapForBounds)
                .put("bitmapAllocationsForRawHashCheck", cleanup.bitmapOpened - bitmaps).put("matAllocations", cleanup.matOpened - before)
                .put("passed", badSize && noBitmapForBounds && badHash && cleanup.bitmapOpened == bitmaps + 1 && cleanup.matOpened == before && cleanup.balanced)
        }
        for (extra in listOf(false, true)) guard(if (extra) "asset-set-extra" else "asset-set-missing") {
            val failed = rejected { reader(list = { path ->
                val names = assets.list(path)!!.toList()
                if (extra) names + "unexpected.png" else names.filterNot { it == "vertical-source.png" }
            }).manifest() }
            JSONObject().put("passed", failed)
        }
    }

    @Test fun compareFrozenProbabilityMasksAndReferenceQuadCropsAndGuards() {
        val atomic = AtomicFile(File(instrumentation.targetContext.filesDir, "detector-geometry-probe-report.json"))
        atomic.delete() // Must precede OpenCV loading, including a native load failure.
        val route = GeometryOpenCvProbe.Route.valueOf(InstrumentationRegistry.getArguments().getString("geometryCropRoute") ?: "DEFAULT")
        val started = SystemClock.elapsedRealtime(); val caseRows = JSONArray()
        val report = JSONObject().put("schema", 1).put("runId", UUID.randomUUID().toString())
            .put("scope", "synthetic_probability_masks_and_reference_quad_crops_NOT_detected_boxes_OR_OCR")
            .put("fixtureManifestSha256", GeometryFixtureInputs.MANIFEST_SHA).put("syntheticCaseIDs", JSONArray(GeometryFixtureInputs.IDS))
            .put("api", Build.VERSION.SDK_INT).put("manufacturer", Build.MANUFACTURER).put("deviceModel", Build.MODEL)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("openCvArtifact", "org.opencv:opencv:5.0.0.1")
            .put("openCvAarSha256", "edb1406a223d2820460b8366a790238b400f5d5c9ea2e98d44b889f0f3c66849")
            .put("sourceDecode", "BitmapFactory RGB8 to ARGB_8888 sRGB, inScaled=false; dimensions before Bitmap allocation; raw BGR hash after Bitmap allocation and before Mat")
            .put("coordinateConvention", "homography target edges (0,0),(W,0),(W,H),(0,H); CCW pixel indices (x,y)->(y,W-1-x); inverse (u,v)->(W-1-v,u)")
            .put("acceptance", JSONObject().put("maskChangedValues", 0).put("dilatedChangedValues", 0).put("contourCountExact", true)
                .put("cropChangedBgrChannels", 0).put("associationAndDimensionsExact", true).put("matrixAtol", 1e-9).put("matrixRtol", 1e-9)
                .put("pointAtol", 1e-6).put("pointRtol", 1e-9).put("contourBudget", 8192).put("candidateLimit", 1000))
            .put("cropRoute", route.name).put("cases", caseRows).put("guards", guardRows).put("status", "started")
        var maskComparisons = 0; var dilationComparisons = 0; var contourComparisons = 0; var cropComparisons = 0
        var matrixComparisons = 0; var inverseComparisons = 0; var associationComparisons = 0
        var dimensionComparisons = 0; var coordinateComparisons = 0; var pointScalars = 0L
        var maskValues = 0L; var dilationValues = 0L; var cropChannels = 0L
        var passed = true; var failure: Throwable? = null
        write(atomic, report) // Fresh partial evidence survives a process-level native failure.
        try {
            check(OpenCVLoader.initLocal()); check(Core.getVersionString() == "5.0.0")
            report.put("openCvRuntime", Core.getVersionString()).put("openCvBuildInfoSha256", ProbeInputs.sha(Core.getBuildInformation().toByteArray()))
                .put("openCvOptimized", Core.useOptimized())
            val previousThreads = Core.getNumThreads(); val previousOptimized = Core.useOptimized()
            try {
                if (route == GeometryOpenCvProbe.Route.UNOPTIMIZED) Core.setUseOptimized(false)
                report.put("effectiveOpenCvOptimized", Core.useOptimized())
                Core.setNumThreads(1); check(Core.getNumThreads() == 1); report.put("openCvThreads", Core.getNumThreads())
                val fixtures = reader(); val cases = fixtures.manifest(); val engine = GeometryOpenCvProbe(cleanup, route)
                for (case in cases) {
                    val row = JSONObject().put("id", case.id).put("status", "started"); val crops = JSONArray(); row.put("crops", crops); caseRows.put(row)
                    try {
                        val actual = engine.masks(case.shape.toLongArray(), fixtures.probability(case))
                        val mask = bytes(actual.mask, fixtures.mask(case, false)); maskComparisons++; maskValues += mask.getInt("comparedValues")
                        row.put("mask", mask).put("status", actual.status.name).put("rowRunBound", actual.rowRunBound)
                        val dilation = actual.dilated?.let { bytes(it, fixtures.mask(case, true)) }
                        if (dilation != null) { dilationComparisons++; dilationValues += dilation.getInt("comparedValues"); row.put("dilation", dilation) }
                        contourComparisons++
                        row.put("contourCount", actual.contourCount ?: JSONObject.NULL).put("referenceContourCount", case.contours)
                            .put("contourCountExact", actual.contourCount == case.contours).put("candidateLimit", actual.candidateLimit)
                        var casePassed = actual.status == GeometryProbeContract.Status.COMPLETE && mask.getBoolean("passed") &&
                            dilation?.getBoolean("passed") == true && actual.contourCount == case.contours
                        val source = fixtures.image("${case.id}-source.png") // Also authenticates all negative-case PNG pixels.
                        for (frozen in case.rows) {
                            val crop = JSONObject().put("id", frozen.id).put("originalIndex", frozen.originalIndex).put("readingOrder", frozen.readingOrder)
                                .put("detectorScore", frozen.detectorScore).put("cropAsset", frozen.crop).put("status", "started")
                            crops.put(crop)
                            try {
                                val reference = fixtures.image(frozen.crop); val result = engine.crop(source, frozen)
                                val pixels = bytes(result.bgr, reference.bgr); cropComparisons++; cropChannels += pixels.getInt("comparedValues")
                                pixels.put("changedBgrChannels", pixels.getInt("changedValues"))
                                    .put("actualBgrSha256", ProbeInputs.sha(result.bgr)).put("referenceBgrSha256", reference.sha)
                                val m = matrix(result.sourceToPreRotation, frozen.referenceMatrix.toDoubleArray()); matrixComparisons++
                                val inverse = matrix(result.preRotationToSource, GeometryProbeContract.inverse(frozen.referenceMatrix.toDoubleArray())); inverseComparisons++
                                val points = coordinates(result)
                                coordinateComparisons++
                                for (key in listOf("sourceToTargetCorners", "targetToSourceCorners", "sourceCornerRoundtrip",
                                    "cropPixelCornerRoundtrip", "afterPixelRotation", "afterPixelRotationInverse")) {
                                    pointScalars += points.getJSONObject(key).getInt("comparedScalars")
                                }
                                val associated = result.row == frozen; associationComparisons++
                                val dimensions = result.width == reference.width && result.height == reference.height && result.rotated == frozen.plan.rotate
                                dimensionComparisons++
                                val cropPassed = pixels.getBoolean("passed") && m.getBoolean("passed") && inverse.getBoolean("passed") &&
                                    points.getBoolean("passed") && associated && dimensions
                                crop.put("bgr", pixels).put("matrixComparison", m).put("inverseMatrixComparison", inverse).put("coordinates", points)
                                    .put("sourceToPreRotation", JSONArray(result.sourceToPreRotation.toList()))
                                    .put("preRotationToSource", JSONArray(result.preRotationToSource.toList())).put("associationExact", associated)
                                    .put("width", result.width).put("height", result.height).put("preRotationWidth", frozen.plan.width)
                                    .put("preRotationHeight", frozen.plan.height).put("rotateCCW90", result.rotated).put("dimensionsAndRotationExact", dimensions)
                                    .put("passed", cropPassed).put("status", "completed")
                                casePassed = casePassed && cropPassed
                            } catch (e: Throwable) { crop.put("status", "failed").put("errorClass", e.javaClass.name); casePassed = false }
                        }
                        row.put("passed", casePassed); passed = passed && casePassed
                    } catch (e: Throwable) { row.put("status", "failed").put("errorClass", e.javaClass.name); passed = false }
                    row.put("cleanup", cleanup.json())
                    write(atomic, report) // Persist measured differences before any final JUnit assertion.
                }
                runGuards(fixtures, cases, engine)
            } finally { Core.setUseOptimized(previousOptimized); Core.setNumThreads(previousThreads) }
        } catch (e: Throwable) { failure = e; passed = false; report.put("errorClass", e.javaClass.name) }
        finally {
            val guardsPassed = guardRows.length() == 16 && (0 until guardRows.length()).all {
                val g = guardRows.getJSONObject(it); g.optJSONObject("metrics")?.optBoolean("passed", false) == true
            }
            val fullCounts = maskComparisons == 16 && dilationComparisons == 16 && contourComparisons == 16 && cropComparisons == 13 &&
                matrixComparisons == 13 && inverseComparisons == 13 && associationComparisons == 13 && dimensionComparisons == 13 &&
                coordinateComparisons == 13 && pointScalars == 832L
            passed = passed && fullCounts && guardsPassed && cleanup.balanced
            report.put("comparisonCounts", JSONObject().put("maskCases", maskComparisons).put("maskValues", maskValues)
                .put("dilationCases", dilationComparisons).put("dilationValues", dilationValues).put("contourCounts", contourComparisons)
                .put("crops", cropComparisons).put("cropBgrChannels", cropChannels).put("normalizedMatrices", matrixComparisons)
                .put("inverseMatrices", inverseComparisons).put("frozenAssociations", associationComparisons)
                .put("dimensionsAndRotation", dimensionComparisons).put("coordinateCases", coordinateComparisons)
                .put("coordinateScalars", pointScalars).put("guards", guardRows.length()))
                .put("fullComparisonCounts", fullCounts).put("guardsPassed", guardsPassed).put("cleanup", cleanup.json())
                .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("passed", passed).put("status", if (passed) "passed" else "failed")
            write(atomic, report)
        }
        failure?.let { throw AssertionError("Geometry primitive probe failed; see detector-geometry-probe-report.json", it) }
        assertTrue("Fixed geometry gates failed; metrics saved in detector-geometry-probe-report.json", passed)
    }
}
