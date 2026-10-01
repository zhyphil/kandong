package com.kandong.liveocr

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class OcrRowProcessorTest {
    private val strip=FullPageStripPlanner.plan(1080,480).strips.single()
    private fun rect(index: Int,x: Double,y: Double,w: Double,h: Double)=BoxPipelineContract.Box(index,index,index,
        listOf(GeometryProbeContract.Point(x,y),GeometryProbeContract.Point(x+w,y),
            GeometryProbeContract.Point(x+w,y+h),GeometryProbeContract.Point(x,y+h)),0.9)

    @Test fun badGeometryAndExtremeAspectRatioDoNotCancelTheGoodRows() {
        val normal=rect(0,20.0,20.0,200.0,30.0)
        val flat=rect(1,20.0,80.0,200.0,0.0)
        val tooWide=rect(2,20.0,110.0,1000.0,4.0)
        val last=rect(3,20.0,180.0,150.0,30.0)
        val calls=ArrayList<Int>()
        val rows=listOf(normal,flat,tooWide,last).mapIndexed { i,box ->
            OcrRowProcessor.recognize(strip,box,i,480,OcrCurrent { true }) { row,plan ->
                calls += row.originalIndex
                assertTrue(plan.width in 320..2048)
                "clear-${row.originalIndex}"
            }
        }
        val page=OcrPageContract.publish(rows,1080,480,OcrCurrent { true })
        assertEquals(listOf(0,3),calls)
        assertEquals(listOf("clear-0","clear-3"),page.blocks.map { it.text })
        assertEquals(listOf(80,110),page.unreadable.map { it.top })
        assertTrue(page.unreadable.all { it.text.isEmpty() })
        assertEquals(4,page.rawCandidateCount)
    }

    @Test fun touchingPhysicalEdgesSkipsRecognitionButRetainsPositions() {
        val boxes=listOf(rect(0,0.0,20.0,60.0,20.0),rect(1,1019.0,60.0,60.0,20.0),
            rect(2,100.0,0.0,60.0,20.0),rect(3,100.0,459.0,60.0,20.0))
        val rows=boxes.mapIndexed { i,b -> OcrRowProcessor.recognize(strip,b,i,480,OcrCurrent { true }) { _,_ ->
            fail("Do not infer cut text"); "guessed"
        } }
        assertTrue(rows.all { it.clippedAtPageBoundary && it.text.isEmpty() })
        assertEquals(4,OcrPageContract.publish(rows,1080,480,OcrCurrent { true }).unreadable.size)
    }

    @Test fun ownedInternalCutAndHaloBoxesNeverEnterNativeRecognition() {
        val first=FullPageStripPlanner.plan(1080,2340).strips.first()
        val cut=rect(0,40.0,first.core.bottom-140.0,200.0,203.0)
        val owned=OcrRowProcessor.recognize(first,cut,0,2340,OcrCurrent { true }) { _,_ ->
            fail("Cut owned row must be skipped"); "guessed"
        }
        assertTrue(owned.ownsCoreCenter); assertTrue(owned.clippedAtStripBoundary)
        val second=FullPageStripPlanner.plan(1080,2340).strips[1]
        val halo=OcrRowProcessor.recognize(second,rect(1,40.0,2.0,800.0,4.0),1,2340,OcrCurrent { true }) { _,_ ->
            fail("Halo must not fail on an extreme crop ratio"); "guessed"
        }
        assertFalse(halo.ownsCoreCenter)
    }

    @Test fun nativeFailuresAreNotSilentlyConvertedToUnclearText() {
        val failure=LiveOcrException(OcrFailure.CLEANUP_UNCERTAIN)
        try {
            OcrRowProcessor.recognize(strip,rect(0,20.0,20.0,200.0,30.0),0,480,OcrCurrent { true }) { _,_ -> throw failure }
            fail("Must propagate runtime failure")
        } catch(e: LiveOcrException) { assertSame(failure,e) }
        val badInput=IllegalArgumentException("native-contract")
        try {
            OcrRowProcessor.recognize(strip,rect(0,20.0,20.0,200.0,30.0),0,480,OcrCurrent { true }) { _,_ -> throw badInput }
            fail("Only preflight geometry may be skipped")
        } catch(e: IllegalArgumentException) { assertSame(badInput,e) }
    }

    @Test fun cancellationAfterRecognitionCannotLeakTheRow() {
        var active=true
        try {
            OcrRowProcessor.recognize(strip,rect(0,20.0,20.0,200.0,30.0),0,480,OcrCurrent { active }) { _,_ ->
                active=false; "late text"
            }
            fail("Must discard cancelled work")
        } catch(e: CancellationException) { assertEquals("CANCELLED_OR_STALE",e.message) }
    }
}
