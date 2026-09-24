package com.kandong.ocrlab.context.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureOriginTest {
    private val version = CaptureVersion(1, 1, 1, 1, 1, 0)
    private fun page() = CaptureMetadata(version, 100, 200, 10, 1000, CaptureEvidence.YES, CaptureEvidence.NO,
        listOf(CaptureWindow(1, CaptureWindowOwner.TARGET_APP, CaptureEvidence.YES, CaptureEvidence.NO, CaptureEvidence.NO)),
        emptyList(), CaptureOrigin.OWNED_ANDROID_FIXTURE)
    @Test fun actualOwnedAndroidFixtureOriginIsNotRelabeledAsHandAuthoredMetadata() {
        val result = LocalCapturePrivacy.inspect(page(), { CaptureCheckpoint(version, true, 10) }) { error("Empty fixture") }
        assertNull(result.rejection)
        assertEquals(CaptureOrigin.OWNED_ANDROID_FIXTURE, result.origin)
        assertEquals(CaptureCoverage.UNVERIFIED, result.coverage)
    }
    @Test fun rejectedInputKeepsItsOriginWithoutImplyingSafety() {
        val result = LocalCapturePrivacy.inspect(page().copy(width = 0), { error("Invalid metadata") }) { error("Invalid metadata") }
        assertEquals(CaptureRejection.INVALID_METADATA, result.rejection)
        assertEquals(CaptureOrigin.OWNED_ANDROID_FIXTURE, result.origin)
        assertTrue(result.blocks.isEmpty())
    }
}
