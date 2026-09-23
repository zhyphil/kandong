package com.kandong.compat

import org.junit.Assert.*
import org.junit.Test

class ClarityTargetTest {
    @Test fun disabledEnhancementDoesNotApplyItsAllocationLimitsToBaseline() {
        assertNull(ClarityTarget.create(false, Int.MAX_VALUE, Int.MAX_VALUE, 2f, 0f, 0f))
    }
    @Test fun unsupportedEnhancementSizeReturnsFallbackInsteadOfThrowingOnUiThread() {
        assertNull(ClarityTarget.create(true, 2001, 1000, 2f, 0f, 0f))
        assertNull(ClarityTarget.create(true, Int.MAX_VALUE, Int.MAX_VALUE, 2f, 0f, 0f))
    }
    @Test fun supportedOutputKeepsFractionalZoomAndTranslation() {
        val target=checkNotNull(ClarityTarget.create(true, 1096, 384, 2.64f, -120.5f, 10.25f))
        assertEquals(1096, target.width); assertEquals(384, target.height)
        assertEquals(2.64f, target.scale, 0f); assertEquals(-120.5f, target.translateX, 0f)
        assertEquals(10.25f, target.translateY, 0f)
    }
}
