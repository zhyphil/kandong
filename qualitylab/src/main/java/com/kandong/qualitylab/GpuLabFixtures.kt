package com.kandong.qualitylab

/** Small deterministic opaque fixtures. Main text comes from the unchanged LabRenderer.sample(). */
internal object GpuLabFixtures {
    data class Fixture(val name: String, val width: Int, val height: Int, val pixels: IntArray)

    fun small(): List<Fixture> {
        fun rgb(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b
        fun fixture(name: String, w: Int, h: Int, value: (Int, Int) -> Int) =
            Fixture(name, w, h, IntArray(w * h) { value(it % w, it / w) })
        var seed = 0x13245768
        return listOf(
            fixture("single-pixel", 1, 1) { _, _ -> rgb(19, 101, 233) },
            fixture("vertical-1x17", 1, 17) { _, y -> rgb(y * 15, 255 - y * 15, (y * 37) % 256) },
            fixture("horizontal-19x1", 19, 1) { x, _ -> rgb(x * 14, (x * 47) % 256, 255 - x * 14) },
            fixture("asymmetric-colored-corners", 17, 13) { x, y ->
                when {
                    x < 4 && y < 4 -> rgb(231, 31, 67)
                    x >= 13 && y < 4 -> rgb(17, 207, 89)
                    x < 4 && y >= 9 -> rgb(43, 71, 239)
                    x >= 13 && y >= 9 -> rgb(219, 179, 23)
                    else -> rgb(36 + x * 5, 41 + y * 7, 113)
                }
            },
            fixture("impulses", 11, 9) { x, y ->
                when {
                    x == 0 && y == 0 -> rgb(255, 0, 23)
                    x == 5 && y == 4 -> rgb(255, 255, 255)
                    x == 9 && y == 7 -> rgb(0, 255, 197)
                    else -> rgb(0, 0, 0)
                }
            },
            fixture("checkerboard", 17, 11) { x, y -> if ((x + y) % 2 == 0) rgb(0, 0, 0) else rgb(255, 255, 255) },
            fixture("thin-lines", 19, 13) { x, y ->
                if (x == 3 || y == 8 || x == y) rgb(13, 73, 211) else rgb(241, 227, 201)
            },
            fixture("solid", 7, 5) { _, _ -> rgb(67, 128, 193) },
            fixture("grayscale-ramp", 31, 7) { x, _ -> val v = x * 255 / 30; rgb(v, v, v) },
            fixture("color-ramp", 23, 17) { x, y -> rgb(x * 255 / 22, y * 255 / 16, (x + y) * 255 / 38) },
            fixture("seeded-noise", 17, 13) { _, _ ->
                // A fixed integer generator avoids dependence on platform Random implementations.
                seed = seed * 1664525 + 1013904223
                (255 shl 24) or (seed and 0x00ffffff)
            }
        )
    }
}
