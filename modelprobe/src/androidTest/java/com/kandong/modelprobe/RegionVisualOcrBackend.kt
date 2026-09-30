package com.kandong.modelprobe

import android.content.Context
import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Collections
import java.util.concurrent.Executors

/** Cached test loader owns this one process-wide native lane; no Activity or surface is stored. */
internal object RegionVisualOcrRuntime {
    val slot = RegionOcrSlot()
    private val executor = Executors.newSingleThreadExecutor { command -> Thread(command, "fixed-page-ocr") }
    private val handler = Handler(Looper.getMainLooper())
    fun worker(command: () -> Unit) { executor.execute(command) }
    fun main(command: () -> Unit) { check(handler.post(command)) }
}

internal class RegionVisualOcrBackend(application: Context, fixtureAssets: AssetManager,
    afterInferenceForProbe: () -> Unit = {}) : RegionVisualSession.Backend {
    private val runner = FullPageVisualOcrRunner(application.applicationContext, fixtureAssets,afterInferenceForProbe)
    private val bridge = RegionOcrBridge<FullPageVisualOcrRunner.Result>(RegionVisualOcrRuntime.slot,
        RegionVisualOcrRuntime::worker, RegionVisualOcrRuntime::main, SystemClock::elapsedRealtime,
        { error("EXPLICIT_PAGE_REQUIRED") })
    override val pages = Collections.unmodifiableList(FullPageVisualOcrRunner.PAGES.map {
        RegionVisualSession.Page(it.id, it.width, it.height)
    })
    override fun start(pageIndex: Int): Boolean {
        val spec = FullPageVisualOcrRunner.PAGES[pageIndex]
        val workerRunner = runner // Deliberately no capture of the backend/session/surface.
        return bridge.start { ticket -> workerRunner.run(spec, ticket) }
    }
    override fun evidence() = bridge.value()?.display
    fun result() = bridge.value()
    override fun busy() = bridge.busy()
    override fun error() = bridge.error()
    override fun cancel() = bridge.cancel()
    override fun dispose() = bridge.dispose()
}
