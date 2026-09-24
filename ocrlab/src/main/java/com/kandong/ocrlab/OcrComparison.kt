package com.kandong.ocrlab

import java.text.Normalizer

internal object OcrComparison {
    fun normalize(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        val result = StringBuilder()
        var pendingSpace = false
        normalized.codePoints().forEach { codePoint ->
            // Unicode White_Space, including NEL, NBSP and NARROW NO-BREAK SPACE.
            val whitespace = codePoint in 0x09..0x0D || codePoint == 0x20 ||
                codePoint == 0x85 || codePoint == 0xA0 || codePoint == 0x1680 ||
                codePoint in 0x2000..0x200A || codePoint == 0x2028 ||
                codePoint == 0x2029 || codePoint == 0x202F || codePoint == 0x205F ||
                codePoint == 0x3000
            if (whitespace) {
                pendingSpace = result.isNotEmpty()
            } else {
                if (pendingSpace) result.append(' ')
                result.appendCodePoint(codePoint)
                pendingSpace = false
            }
        }
        return result.toString()
    }
    fun matches(source: String, recognized: String) = normalize(source) == normalize(recognized)
}
