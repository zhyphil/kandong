package com.kandong.compat

/** Bounded in-memory capture diagnostics. Retains only counts, no colors or page text. */
internal data class SnapshotSamples(val total: Int, val dark: Int, val light: Int, val opaque: Int,
    val edges: Int) {
    // This only rejects uniformly black sampled input, not a general protection detector.
    val pageVisible get() = total>0 && dark<total && opaque==total
    override fun toString() = "$total/$dark/$light/$opaque/$edges"
    companion object {
        fun read(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int,
            pixel: (Int,Int)->Int): SnapshotSamples {
            require(width>0 && height>0 && left>=0 && top>=0 && right<=width && bottom<=height)
            var total=0; var dark=0; var light=0; var opaque=0; var edges=0
            for(y in top+8 until bottom step 16) {
                var previous: Int?=null
                for(x in left+8 until right step 16) {
                    val argb=pixel(x,y)
                    val r=argb ushr 16 and 255; val g=argb ushr 8 and 255; val b=argb and 255
                    val gray=(r+g+b)/3
                    total++; if(maxOf(r,g,b)<=16) dark++; if(minOf(r,g,b)>=239) light++
                    if(argb ushr 24==255) opaque++
                    if(previous?.let { kotlin.math.abs(it-gray)>24 }==true) edges++
                    previous=gray
                }
            }
            return SnapshotSamples(total,dark,light,opaque,edges)
        }
    }
}
