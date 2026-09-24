package com.kandong.ocrlab

import org.junit.Assert.*
import org.junit.Test

class OcrComparisonTest {
    @Test fun onlyCanonicalCompositionAndUnicodeWhitespaceAreEquivalent() {
        assertTrue(OcrComparison.matches("  Accès : 1\u202f234,50 €\n", "Acce\u0300s : 1 234,50\u00a0€"))
        assertEquals("a b c", OcrComparison.normalize("\t a\u2003b\u0085c\n"))
        assertTrue(OcrComparison.matches("", "\n\u202f"))
    }

    @Test fun significantDifferencesAreNeverHidden() {
        listOf(
            "1\u202f234,50 €" to "1234.50 EUR",
            "1 234,50 €" to "1234,50 €",
            "39.90" to "39,90",
            "07/08/2026" to "08/07/2026",
            "0 1 10" to "O I IO",
            "Accès" to "Acces",
            "Breakfast is not included." to "Breakfast is included.",
            "ni échangeable ni remboursable" to "échangeable ni remboursable",
            "Pay." to "pay.",
            "Pay." to "Pay",
            "①" to "1",
            "n'est" to "n’est",
            "A B" to "A\u200bB"
        ).forEach { (source, actual) -> assertFalse("$source / $actual", OcrComparison.matches(source, actual)) }
    }
}
