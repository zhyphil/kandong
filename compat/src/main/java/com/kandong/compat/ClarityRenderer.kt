package com.kandong.compat

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.kandong.graphics.GpuC
import com.kandong.graphics.GpuViewport
import java.util.concurrent.Executors

/** Takes ownership of immutable ROI bytes, never of the UI's recyclable Bitmap. */
internal class ClarityRenderer(onFrame: (Frame, Bitmap) -> Unit, onFailure: () -> Unit) {
    data class Frame(val bytes: ByteArray, val width: Int, val height: Int, val viewport: GpuViewport)
    // Separate handler: the Service's removeCallbacksAndMessages must not orphan output Bitmaps.
    private val delivery = Handler(Looper.getMainLooper())
    private val queue = LatestRenderWorker<Frame, Bitmap>(
        Executors.newSingleThreadExecutor { job -> Thread(job, "KanDong-clarity") },
        { delivery.post(it) },
        {
            object : LatestRenderWorker.Backend<Frame, Bitmap> {
                private val gpu = GpuC()
                private var lastBytes: ByteArray? = null
                private var input: GpuC.Input? = null
                override fun render(input: Frame): Bitmap {
                    if (input.bytes !== lastBytes) {
                        this.input = GpuC.Input.fromRgba(input.bytes, input.width, input.height)
                        lastBytes = input.bytes
                    }
                    return gpu.renderViewport(checkNotNull(this.input), input.viewport).bitmap
                }
                override fun close() {
                    lastBytes = null; input = null; gpu.close()
                }
            }
        }, { it.recycle() }, onFrame, onFailure)
    val resourcesOpen get() = queue.resourcesOpen
    val cleanupFailures get() = queue.cleanupFailures
    fun submit(frame: Frame) = queue.submit(frame)
    fun invalidate(release: Boolean = false) = queue.invalidate(release)
    fun close() = queue.close()
}
