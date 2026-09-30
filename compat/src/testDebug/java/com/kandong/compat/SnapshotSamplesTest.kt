package com.kandong.compat

import org.junit.Assert.*
import org.junit.Test

class SnapshotSamplesTest {
    @Test fun distinguishesBlankCaptureFromTextLikeContrastWithoutKeepingPixels() {
        fun measure(pixel:(Int,Int)->Int)=SnapshotSamples.read(64,64,0,0,64,64,pixel)
        assertEquals(SnapshotSamples(16,16,0,16,0),measure { _,_->0xff000000.toInt() })
        assertEquals(SnapshotSamples(16,0,16,16,0),measure { _,_->-1 })
        val contrast=measure { x,_->if((x/16)%2==0) -1 else 0xff000000.toInt() }
        assertEquals(8,contrast.dark); assertEquals(8,contrast.light); assertEquals(12,contrast.edges)
        assertTrue(contrast.pageVisible)
        assertTrue(measure { _,_->-1 }.pageVisible)
        assertFalse(measure { _,_->0xff000000.toInt() }.pageVisible)
    }
    @Test fun excludesSystemBarsAndDoesNotMistakeTransparencyForOpaquePixels() {
        val result=SnapshotSamples.read(64,64,0,16,64,48) { _,y ->
            assertTrue(y in 16 until 48); 0x00ffffff
        }
        assertEquals(SnapshotSamples(8,0,8,0,0),result)
        assertFalse(result.pageVisible)
    }
}
