package com.kandong.compat

import java.nio.ByteBuffer

/** Copies selected RGBA rows only; padded image rows must never leak into the crop. */
internal object CropPixels {
    fun copy(buffer: ByteBuffer, imageWidth: Int, imageHeight: Int, rowStride: Int, pixelStride: Int,
        left: Int, top: Int, width: Int, height: Int): ByteArray {
        require(pixelStride == 4 && imageWidth > 0 && imageHeight > 0)
        require(width > 0 && height > 0 && left >= 0 && top >= 0)
        require(left.toLong()+width <= imageWidth && top.toLong()+height <= imageHeight)
        require(rowStride.toLong() >= imageWidth.toLong()*pixelStride)
        val size = width.toLong()*height*pixelStride
        require(size <= Int.MAX_VALUE)
        val lastByte = (top.toLong()+height-1)*rowStride+(left.toLong()+width)*pixelStride
        require(lastByte <= buffer.limit())
        val output = ByteArray(size.toInt())
        val source = buffer.duplicate()
        val rowBytes = width*pixelStride
        repeat(height) { row ->
            source.position((top+row)*rowStride+left*pixelStride)
            source.get(output,row*rowBytes,rowBytes)
        }
        return output
    }
}
