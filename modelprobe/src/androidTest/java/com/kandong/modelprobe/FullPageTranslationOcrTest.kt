package com.kandong.modelprobe

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kandong.ocrlab.context.*
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual fixed PNG -> Image -> OCR -> request -> binding-marker projection, never translation quality. */
@RunWith(AndroidJUnit4::class)
class FullPageTranslationOcrTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun time() = SystemClock.elapsedRealtime()

    @Test fun fourActualPagesKeepWholeContextWhileRegionOnlyChoosesDisplay() {
        val runner = FullPageVisualOcrRunner(instrumentation.targetContext.applicationContext, instrumentation.context.assets)
        for (spec in FullPageVisualOcrRunner.PAGES) {
            val prepared = runner.prepare(spec) // Metadata reservation before the explicit click.
            lateinit var probe: FullPageTranslationProbe
            lateinit var click: FullPageTranslationProbe.Ticket
            lateinit var bridge: RegionOcrBridge<RegionOcrBridge.Evidence<FullPageVisualOcrRunner.Result>>
            main {
                probe = FullPageTranslationProbe()
                probe.observe(prepared.version)
                assertNull(probe.pending(time()))
                click = checkNotNull(probe.click(time()))
                bridge = RegionOcrBridge(RegionVisualOcrRuntime.slot, RegionVisualOcrRuntime::worker,
                    RegionVisualOcrRuntime::main, ::time) { token ->
                    val original = runner.run(prepared,token)
                    RegionOcrBridge.Evidence(original,original.acquiredAtMillis,original.ttlMillis,original.validatedAtMillis)
                }
                assertTrue(bridge.start())
            }
            try {
                var receipt: RegionOcrBridge.Evidence<FullPageVisualOcrRunner.Result>? = null
                val deadline = time()+120_000
                while (receipt == null && time() < deadline) {
                    main { assertNull(bridge.error()); receipt=bridge.value() }
                    if (receipt == null) SystemClock.sleep(50)
                }
                val delivered=checkNotNull(receipt) { "Actual OCR delivery timed out" }
                val result=delivered.value
                main {
                    assertSame(delivered,bridge.value()) // The original owner is still live.
                    assertEquals(prepared.version,result.metadata.version)
                    assertTrue(result.metadata.acquiredAtMillis>=click.capture.clickedAt)
                    assertTrue(result.diagnostics.cleanupBalanced)
                    val tiny=ContextRect(0.0,0.0,1.0,1.0)
                    assertTrue("Actual evidence rejected: ${spec.id}",probe.captured(click,result.metadata,result.page,
                        spec.language,tiny,delivered.validatedAtMillis,time()))
                    val retained=checkNotNull(probe.evidence(time()))
                    assertEquals(result.metadata,retained.metadata)
                    val raw=result.page.rawCandidates.associateBy { it.provenance.id }
                    assertEquals(raw.size,retained.association.rawCandidates.size)
                    retained.association.rawCandidates.forEach { c ->
                        assertTrue(FullPageCandidateEvidence.same(raw.getValue(c.provenance.id),c))
                    }
                    assertEquals(result.page.edges,retained.association.edges)
                    assertEquals(result.page.groups,retained.association.groups)
                    assertTrue(probe.render(time()).cards.isEmpty())
                    val request=probe.pending(time())
                    if (spec.language in setOf("en","fr")) {
                        val req=checkNotNull(request)
                        assertEquals(raw.size,req.contract.screenContext.blocks.size)
                        assertEquals(raw.keys,req.sourceMap.values.map { it.provenance.id }.toSet())
                        assertTrue(req.contract.screenContext.groups.isEmpty())
                        assertTrue(req.contract.targets.isNotEmpty()) // All targets lie outside the initial tiny ROI.
                        assertTrue(req.contract.targets.all { it.groupId==null && it.context.isEmpty() })
                        val target=req.contract.targets.last()
                        assertNotNull(probe.select(target.sources.single().visible,time()))
                        assertTrue(probe.transform(MirrorTransform(3.0,600.0,400.0),time()))
                        assertSame(req,probe.pending(time()))
                        val contract=req.contract
                        val reply=FixtureResponse(contract.id,contract.page,contract.targetLanguage,contract.model,contract.version,
                            contract.targets.map { FixtureAnswer(it,"BINDING_TEST_ONLY:${it.id}") })
                        assertTrue(probe.accept(req,reply,time()))
                        assertEquals("BINDING_TEST_ONLY:${target.id}",probe.render(time()).cards.single { it.targetId==target.id }.chinese)
                        assertNull(probe.pending(time()))
                    } else {
                        assertNull(request) // Both Chinese variants stay local and retain the source.
                    }
                    val whole=ContextRect(0.0,0.0,result.metadata.width.toDouble(),result.metadata.height.toDouble())
                    probe.select(whole,time())
                    val cards=probe.render(time()).cards
                    assertEquals(raw.size,cards.size)
                    if (spec.language.startsWith("zh")) {
                        assertTrue(cards.all { it.chinese==null })
                        assertTrue(cards.any { it.kind==AnswerKind.KEEP_ORIGINAL && it.reason=="ALREADY_CHINESE" })
                    }
                    assertSame(delivered,bridge.value())
                    assertEquals(result.page.rawCandidates.size,result.diagnostics.recognitions)
                    probe.clear(ClearReason.MENU); bridge.cancel()
                    assertNull(probe.evidence(time())); assertTrue(probe.render(time()).cards.isEmpty())
                    if (request!=null) assertFalse(probe.failed(request,0)) // Old callback cannot rewind the clock.
                    assertFalse(probe.captured(click,result.metadata,result.page,spec.language,tiny,delivered.validatedAtMillis,0))
                }
            } finally { main { bridge.dispose(); probe.clear(ClearReason.STOP) } }
        }
    }

    @Test fun preparedIdentityIsSingleUseRunnerBoundAndCancellationNeedsNoNativeLoad() {
        val context=instrumentation.targetContext.applicationContext
        val assets=instrumentation.context.assets
        val a=FullPageVisualOcrRunner(context,assets); val b=FullPageVisualOcrRunner(context,assets)
        val prepared=a.prepare(FullPageVisualOcrRunner.PAGES.first())
        val cancelled=RegionOcrBridge.Ticket(time()).also { it.cancel() }
        assertThrows(IllegalArgumentException::class.java) { b.run(prepared,cancelled) }
        assertThrows(CancellationException::class.java) { a.run(prepared,cancelled) }
        assertThrows(IllegalStateException::class.java) { a.run(prepared,cancelled) }
    }
}
