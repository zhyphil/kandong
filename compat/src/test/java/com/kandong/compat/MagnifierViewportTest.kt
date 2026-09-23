package com.kandong.compat

import org.junit.Assert.*
import org.junit.Test

class MagnifierViewportTest {
    private fun viewport() = MagnifierViewport().apply { setViewport(1000, 400); selectSource(500, 200) }
    @Test fun sessionsStartAtTwoAndSliderSupportsContinuousOneThroughFive() {
        val v = viewport(); assertEquals(2f, v.scale, 0f)
        for (factor in listOf(1f, 1.37f, 2.66f, 4.99f, 5f)) {
            v.setScale(factor); assertEquals(factor, v.scale, 0f)
            assertEquals(500, v.sourceWidth); assertEquals(200, v.sourceHeight)
        }
        v.setScale(99f); assertEquals(5f, v.scale, 0f)
        v.setScale(-2f); assertEquals(1f, v.scale, 0f)
        assertEquals(2f, viewport().scale, 0f)
    }
    @Test fun selectingWideTallOrSmallRegionsNeverChangesChosenScale() {
        val v = viewport(); v.setScale(3.27f)
        for ((w, h) in listOf(500 to 200, 800 to 90, 160 to 360, 80 to 32)) {
            v.selectSource(w, h); assertEquals(3.27f, v.scale, 0f)
            assertEquals(w, v.sourceWidth); assertEquals(h, v.sourceHeight)
            assertEquals(0f, v.panX, 0f); assertEquals(0f, v.panY, 0f)
        }
    }
    @Test fun aSmallRegionIsCentredAtExactScaleInsteadOfFillingViewport() {
        val v = viewport(); v.selectSource(200, 100)
        assertEquals(2f, v.scale, 0f)
        assertEquals(300f, v.translateX, 0f); assertEquals(100f, v.translateY, 0f)
        assertFalse(v.canPan)
        v.dragBy(-10000f, 10000f)
        assertEquals(300f, v.translateX, 0f); assertEquals(100f, v.translateY, 0f)
    }
    @Test fun bothOverflowAxesReachTheirEdgesWithoutLeavingTheSelectedRegion() {
        val v = viewport(); v.setScale(5f)
        v.dragBy(-10000f, -10000f)
        assertEquals(1500f, v.panX, 0f); assertEquals(600f, v.panY, 0f)
        assertEquals(1000f, 500 * v.scale + v.translateX, 0f)
        assertEquals(400f, 200 * v.scale + v.translateY, 0f)
        v.dragBy(10000f, 10000f)
        assertEquals(0f, v.panX, 0f); assertEquals(0f, v.panY, 0f)
        assertEquals(5f, v.scale, 0f)
    }
    @Test fun sliderKeepsTheSameSourcePointUnderTheViewportCentre() {
        val v = viewport(); v.setScale(4f); v.dragBy(-100f, -40f)
        val x = (500 - v.translateX) / v.scale
        val y = (200 - v.translateY) / v.scale
        v.setScale(5f)
        assertEquals(x, (500 - v.translateX) / v.scale, .001f)
        assertEquals(y, (200 - v.translateY) / v.scale, .001f)
    }
    @Test fun shrinkingScaleOrChangingSelectionClampsPreviouslyScrolledOffsets() {
        val v = viewport(); v.setScale(5f); v.dragBy(-10000f, -10000f)
        v.setScale(1f); assertFalse(v.canPan)
        assertEquals(0f, v.panX, 0f); assertEquals(0f, v.panY, 0f)
        v.setScale(5f); v.dragBy(-10000f, -10000f); v.selectSource(600, 300)
        assertEquals(0f, v.panX, 0f); assertEquals(0f, v.panY, 0f)
        assertEquals(5f, v.scale, 0f)
    }
    @Test fun invalidInputDoesNotPoisonTheTransformAndViewportResizeClampsScroll() {
        val v = viewport(); v.setScale(Float.NaN); v.dragBy(Float.POSITIVE_INFINITY, 1f)
        assertEquals(2f, v.scale, 0f); assertEquals(0f, v.panX, 0f)
        v.setScale(5f); v.dragBy(-10000f, -10000f); v.setViewport(2400, 900)
        assertEquals(100f, v.panX, 0f); assertEquals(100f, v.panY, 0f)
    }
}
