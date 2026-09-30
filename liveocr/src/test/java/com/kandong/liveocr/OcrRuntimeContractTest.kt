package com.kandong.liveocr

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class OcrRuntimeContractTest {
    private fun rgba(width: Int, height: Int) = ByteArray(width * height * 4) { i ->
        when (i % 4) { 0 -> 17; 1 -> 83; 2 -> 201; else -> 255 }.toByte()
    }
    private fun box(x: Double, y: Double, index: Int = 0) = BoxPipelineContract.Box(index, index, index,
        listOf(GeometryProbeContract.Point(x, y), GeometryProbeContract.Point(x + 20, y),
            GeometryProbeContract.Point(x + 20, y + 8), GeometryProbeContract.Point(x, y + 8)), 0.75)
    private fun candidate(text: String = "example", x: Double = 10.0, y: Double = 10.0, index: Int = 0) =
        OcrPageContract.candidate(FullPageStripPlanner.plan(640, 480).strips.single(), box(x, y, index), text, 480)

    private fun failure(code: String, body: () -> Unit) {
        try { body(); fail("Expected $code") }
        catch (e: LiveOcrException) { assertEquals(code, e.code); assertEquals(code, e.message); assertNull(e.cause) }
    }
    private fun cancelled(body: () -> Unit) {
        try { body(); fail("Expected cancellation") }
        catch (e: CancellationException) { assertEquals("CANCELLED_OR_STALE", e.message) }
    }

    @Test fun rgbaConvertsToBgrAndWipesOwnedStripWithoutChangingCallerBuffer() {
        val input = rgba(32, 32)
        val original = input.copyOf()
        lateinit var retainedForInspection: ByteArray
        val result = SingleFrameStripInput.process(input, 32, 32, OcrCurrent { true }) { strip, bgr ->
            retainedForInspection = bgr
            assertEquals(0, strip.index)
            assertArrayEquals(byteArrayOf(201.toByte(), 83, 17), bgr.copyOfRange(0, 3))
            assertEquals(32 * 32 * 3, bgr.size)
            "complete"
        }
        assertEquals(listOf("complete"), result)
        assertTrue(retainedForInspection.all { it == 0.toByte() })
        assertArrayEquals(original, input)
    }

    @Test fun cancellationAfterConsumerDiscardsResultAndWipesStrip() {
        var active = true
        lateinit var stripBytes: ByteArray
        cancelled {
            SingleFrameStripInput.process(rgba(32, 32), 32, 32, OcrCurrent { active }) { _, bgr ->
                stripBytes = bgr
                active = false
                "must not escape"
            }
        }
        assertTrue(stripBytes.all { it == 0.toByte() })
    }

    @Test fun cancellationDuringCopyNeverInvokesConsumer() {
        var checks = 0
        var consumers = 0
        cancelled {
            SingleFrameStripInput.process(rgba(32, 32), 32, 32, OcrCurrent { ++checks < 5 }) { _, _ -> consumers++ }
        }
        assertEquals(0, consumers)
    }

    @Test fun callbackExceptionIsStickyCancellation() {
        var calls = 0
        val current = OcrCurrent { if (++calls == 1) error("private callback detail") else true }
        cancelled { current.check() }
        cancelled { current.check() }
        assertEquals(1, calls)
    }

    @Test fun consumerFailureWipesPixelsAndDoesNotReturnPartialRows() {
        lateinit var stripBytes: ByteArray
        failure("PIPELINE_FAILED") {
            SingleFrameStripInput.process(rgba(32, 32), 32, 32, OcrCurrent { true }) { _, bgr ->
                stripBytes = bgr
                throw LiveOcrException(OcrFailure.PIPELINE_FAILED)
            }
        }
        assertTrue(stripBytes.all { it == 0.toByte() })
    }

    @Test fun rejectsBadLengthDimensionsOverflowAndTransparentPixels() {
        failure("INVALID_INPUT") { SingleFrameStripInput.validate(ByteArray(4), 32, 32) }
        failure("INVALID_INPUT") { SingleFrameStripInput.validate(ByteArray(0), Int.MAX_VALUE, Int.MAX_VALUE) }
        failure("INVALID_INPUT") { SingleFrameStripInput.validate(ByteArray(0), 2048, 4096) }
        failure("INVALID_INPUT") { SingleFrameStripInput.validate(ByteArray(0), 4096, 32) }
        val input = rgba(32, 32).also { it[3] = 0 }
        failure("INVALID_INPUT") {
            SingleFrameStripInput.process(input, 32, 32, OcrCurrent { true }) { _, _ -> fail("No transparent pixels") }
        }
    }

    @Test fun overlappingStripsUseOffsetOnlyAndHaveExactlyOneCoreOwner() {
        val plan = FullPageStripPlanner.plan(1080, 2400)
        val a = plan.strips[0]
        val b = plan.strips[1]
        val centerY = b.core.top.toDouble()
        val ca = OcrPageContract.candidate(a, box(100.0, centerY - 4 - a.read.top), "line", 2400)
        val cb = OcrPageContract.candidate(b, box(100.0, centerY - 4 - b.read.top), "line", 2400)
        assertEquals(ca.quad, cb.quad)
        assertFalse(ca.ownsCoreCenter)
        assertTrue(cb.ownsCoreCenter)
        val page = OcrPageContract.publish(listOf(ca, cb), 1080, 2400, OcrCurrent { true })
        assertEquals(2, page.rawCandidateCount)
        assertEquals(1, page.blocks.size)
        assertEquals(100, page.blocks.single().left)
        assertEquals(centerY.toInt() - 4, page.blocks.single().top)
        assertEquals(121, page.blocks.single().right)
        assertEquals(centerY.toInt() + 5, page.blocks.single().bottom)
        for (y in 0 until 2400) assertEquals(1, plan.strips.count { it.ownsSourceCenter(100.0, y + 0.5) })
    }

    @Test fun publicationUsesYThenXAndPreservesRepeatedOwnedText() {
        val page = OcrPageContract.publish(listOf(candidate("same", 20.0, 40.0, 0),
            candidate("same", 90.0, 20.0, 1), candidate("same", 10.0, 20.0, 2)), 640, 480, OcrCurrent { true })
        assertEquals(listOf(10, 90, 20), page.blocks.map { it.left })
        assertEquals(listOf("same", "same", "same"), page.blocks.map { it.text })
        assertEquals(3, page.rawCandidateCount)
    }

    @Test fun clippedInternalBoundaryRejectsEntirePageEvenForUnownedFragment() {
        val strip = FullPageStripPlanner.plan(1080, 2400).strips[1]
        val fragment = OcrPageContract.candidate(strip, box(30.0, 0.0), "fragment", 2400)
        assertTrue(fragment.clippedAtStripBoundary)
        assertFalse(fragment.ownsCoreCenter)
        failure("STRIP_BOUNDARY_AMBIGUITY") {
            OcrPageContract.publish(listOf(fragment), 1080, 2400, OcrCurrent { true })
        }
        assertFalse(candidate(y = 0.0).clippedAtStripBoundary) // A physical page edge is not a strip seam.
    }

    @Test fun unreadableOwnedCandidateFailsButUnownedEmptyRemainsInRawCount() {
        failure("UNREADABLE_BOX") {
            OcrPageContract.publish(listOf(candidate("")), 640, 480, OcrCurrent { true })
        }
        failure("UNREADABLE_BOX") {
            OcrPageContract.publish(listOf(candidate("  ")), 640, 480, OcrCurrent { true })
        }
        val unowned = candidate("").copy(ownsCoreCenter = false)
        val result = OcrPageContract.publish(listOf(candidate(), unowned), 640, 480, OcrCurrent { true })
        assertEquals(2, result.rawCandidateCount)
        assertEquals(1, result.blocks.size)
        failure("UNOWNED_CANDIDATES") {
            OcrPageContract.publish(listOf(unowned), 640, 480, OcrCurrent { true })
        }
    }

    @Test fun budgetsRejectOverflowInsteadOfTruncating() {
        OcrPageContract.boxBudget(64, 64)
        failure("PAGE_BOX_BUDGET") { OcrPageContract.boxBudget(65, 0) }
        failure("PAGE_BOX_BUDGET") { OcrPageContract.boxBudget(1, 128) }
        failure("PAGE_TEXT_BUDGET") { OcrPageContract.characterBudget(8192, 1) }
        failure("PAGE_TEXT_BUDGET") { OcrPageContract.characterBudget(1, Int.MAX_VALUE) }
        val full = List(128) { candidate("x".repeat(64), index = it) }
        assertEquals(128, OcrPageContract.publish(full, 640, 480, OcrCurrent { true }).rawCandidateCount)
        failure("PAGE_BOX_BUDGET") {
            OcrPageContract.publish(full + candidate(), 640, 480, OcrCurrent { true })
        }
        failure("PAGE_TEXT_BUDGET") {
            OcrPageContract.publish(full.dropLast(1) + candidate("x".repeat(65)), 640, 480, OcrCurrent { true })
        }
    }

    @Test fun publicationRechecksFreshnessAfterMappingAndAllowsSuccessfulBlankPage() {
        var checks = 0
        cancelled { OcrPageContract.publish(listOf(candidate()), 640, 480, OcrCurrent { ++checks == 1 }) }
        assertEquals(OcrPage(emptyList(), 0), OcrPageContract.publish(emptyList(), 640, 480, OcrCurrent { true }))
    }

    @Test fun languageSelectionAndPinnedWidthUseOnlySupportedModels() {
        assertEquals("latin", OcrAssets.forLanguage("EN").id)
        assertEquals("latin", OcrAssets.forLanguage("FR").id)
        assertEquals("ch", OcrAssets.forLanguage("ZH-HANS").id)
        assertEquals("ch", OcrAssets.forLanguage("ZH-HANT").id)
        failure("UNSUPPORTED_LANGUAGE") { OcrAssets.forLanguage("auto") }
        assertArrayEquals(longArrayOf(1, 56, 504), OcrPageContract.outputShape(OcrAssets.forLanguage("FR"), 452))
        failure("RECOGNITION_WIDTH_BUDGET") { OcrPageContract.outputShape(OcrAssets.forLanguage("EN"), 2049) }
    }

    @Test fun fullWidthScreenTextFitsRecognitionPackingAndOutputBudgetTogether() {
        for(lang in listOf("EN","FR","ZH-HANS","ZH-HANT")) {
            val model=OcrAssets.forLanguage(lang)
            for(width in listOf(1025,1600,2048)) {
                val plan=RecognitionPacking.plan(listOf(RecognitionPacking.Size(width,48)))
                assertEquals(width,plan.width)
                val shape=OcrPageContract.outputShape(model,plan.width)
                assertEquals((width+3L)/8,shape[1])
                assertTrue(CtcDecoder.validateShape(shape,shape,model.vocabulary)>0)
            }
        }
    }

    @Test fun wideRecognitionInputKeepsBothEndsInsteadOfCroppingToOldLimit() {
        val width=1600
        val pixels=IntArray(width*48) { -1 }
        pixels[0]=0xff0000ff.toInt(); pixels[width-1]=0xffff0000.toInt()
        val packed=RecognitionPacking.pack(listOf(RecognitionPacking.Size(width,48)),
            listOf(RecognitionPacking.Resized(width,48,pixels)))
        assertEquals(3*48*width,packed.size)
        assertEquals(1f,packed[0],0f)
        assertEquals(1f,packed[2*48*width+width-1],0f)
    }

    private fun seamRow(id: String, top: Double, bottom: Double, owned: Boolean, clipped: Boolean) =
        OcrPageContract.Candidate(id,"same captured line",listOf(
            GeometryProbeContract.Point(10.0,top), GeometryProbeContract.Point(300.0,top),
            GeometryProbeContract.Point(300.0,bottom), GeometryProbeContract.Point(10.0,bottom)),
            0.9,owned,clipped)

    @Test fun clippedOverlapFragmentDoesNotDiscardItsCompleteOwnedLine() {
        val complete=seamRow("s0/c0-r0-f0",720.0,770.0,true,false)
        val fragment=seamRow("s1/c0-r0-f0",736.0,770.0,false,true).copy(text="partial")
        val page=OcrPageContract.publish(listOf(fragment,complete),1080,2400,OcrCurrent { true })
        assertEquals(2,page.rawCandidateCount)
        assertEquals(1,page.blocks.size)
        assertEquals(complete.id,page.blocks.single().id)
        assertEquals(complete.text,page.blocks.single().text)
        assertEquals(720,page.blocks.single().top)
        assertEquals(771,page.blocks.single().bottom)
    }

    @Test fun unresolvedOrAmbiguousBoundaryStillCannotPublishPartialContext() {
        val complete=seamRow("s0/c0-r0-f0",720.0,770.0,true,false)
        val fragment=seamRow("s1/c0-r0-f0",736.0,770.0,false,true)
        listOf(listOf(fragment),listOf(fragment.copy(ownsCoreCenter=true),complete),
            listOf(fragment,complete.copy(clippedAtStripBoundary=true)),
            listOf(fragment,complete,complete.copy(id="s2/c0-r0-f0")),
            listOf(fragment,complete.copy(id="s1/c1-r1-f1")),
            listOf(fragment.copy(quad=fragment.quad.map { it.copy(y=it.y+200) }),complete)).forEach { rows ->
            failure("STRIP_BOUNDARY_AMBIGUITY") { OcrPageContract.publish(rows,1080,2400,OcrCurrent { true }) }
        }
    }

    @Test fun seamCoverageUsesTheSlantedPolygonNotItsBoundingBox() {
        val complete=seamRow("s0/c0-r0-f0",720.0,770.0,true,false).copy(quad=listOf(
            GeometryProbeContract.Point(10.0,720.0), GeometryProbeContract.Point(300.0,750.0),
            GeometryProbeContract.Point(300.0,770.0), GeometryProbeContract.Point(10.0,740.0)))
        val outside=seamRow("s1/c0-r0-f0",721.0,726.0,false,true).copy(quad=listOf(
            GeometryProbeContract.Point(270.0,721.0), GeometryProbeContract.Point(280.0,721.0),
            GeometryProbeContract.Point(280.0,726.0), GeometryProbeContract.Point(270.0,726.0)))
        failure("STRIP_BOUNDARY_AMBIGUITY") {
            OcrPageContract.publish(listOf(complete,outside),1080,2400,OcrCurrent { true })
        }
        val contained=outside.copy(quad=listOf(
            GeometryProbeContract.Point(100.0,732.0), GeometryProbeContract.Point(200.0,744.0),
            GeometryProbeContract.Point(200.0,754.0), GeometryProbeContract.Point(100.0,742.0)))
        listOf(complete,complete.copy(quad=complete.quad.reversed())).forEach { owner ->
            val page=OcrPageContract.publish(listOf(owner,contained),1080,2400,OcrCurrent { true })
            assertEquals(listOf(complete.id),page.blocks.map { it.id })
        }
    }
}
