package com.kandong.compat

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.kandong.graphics.GpuC
import com.kandong.graphics.GpuViewport
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Takes ownership of immutable ROI bytes, never of the UI's recyclable Bitmap. */
internal class ClarityRenderer(onFrame: (Frame, Bitmap) -> Unit, onFailure: () -> Unit) {
    data class Frame(val bytes: ByteArray, val width: Int, val height: Int, val viewport: GpuViewport)
    // Separate handler: the Service's removeCallbacksAndMessages must not orphan output Bitmaps.
    private val delivery = Handler(Looper.getMainLooper())
    private val queue = LatestRenderWorker<Frame, Bitmap>(
        Executors.newSingleThreadExecutor { job -> Thread(job, "KanDong-clarity") },
        { if (!delivery.post(it)) throw RejectedExecutionException("Main-thread delivery rejected") },
        {
            object : LatestRenderWorker.Backend<Frame, Bitmap> {
                private val gpu = GpuC()
                override fun render(input: Frame): Bitmap =
                    gpu.renderViewportRgba(input.bytes, input.width, input.height, input.viewport).bitmap
                override fun close() = gpu.close()
            }
        }, { it.recycle() }, onFrame, onFailure)
    val resourcesOpen get() = queue.resourcesOpen
    val cleanupFailures get() = queue.cleanupFailures
    fun submit(frame: Frame) = queue.submit(frame)
    fun invalidate(release: Boolean = false) = queue.invalidate(release)
    fun close() = queue.close()
}
