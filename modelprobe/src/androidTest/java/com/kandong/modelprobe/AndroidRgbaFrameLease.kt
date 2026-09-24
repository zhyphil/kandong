package com.kandong.modelprobe

import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.Image

/** Test-only Image ownership; caller-supplied version/time do not authenticate current pixels,
 * privacy, consent or colorspace. The source must be a verified owned fixture before using this.
 * Metadata validation is deferred until process() owns the close-on-failure path.
 */
internal class AndroidRgbaFrameLease(private val image: Image,
    private val expected: RgbaFrameMetadata) : RgbaFrameLease {
    private var closed = false
    private var planeRead = false
    override val metadata: RgbaFrameMetadata
        get() { check(!closed); return expected }

    override fun plane(): RgbaPlane {
        check(!closed && !planeRead)
        planeRead = true
        require(image.format == PixelFormat.RGBA_8888)
        require(image.width == expected.width && image.height == expected.height)
        // Image crop describes valid pixels. Never interpret an arbitrary crop as a full screen.
        require(image.cropRect == Rect(0, 0, expected.width, expected.height))
        val planes = image.planes
        require(planes.size == 1)
        val plane = planes.single()
        return RgbaPlane(plane.buffer, plane.rowStride, plane.pixelStride)
    }

    override fun close() {
        if (closed) return
        closed = true // Do not attempt another close/read, even if the platform close throws.
        image.close()
    }
}
