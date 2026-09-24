package com.kandong.modelprobe

/** Frozen experimental routing rule. Inputs are raw text/evidence, never expected test labels. */
internal object LanguageRoute {
    const val CONFIG = "language-v1:min-letters8;score.90;gap.20;han-latin-review;en-fr-zh"
    data class Candidate(val language: String, val score: Double)
    data class Decision(val action: String, val language: String?, val raw: String, val reason: String)
    fun choose(raw: String, candidates: List<Candidate>, ocrConflict: Boolean = false): Decision {
        fun review(reason: String) = Decision("review", null, raw, reason)
        if (raw.length > 4096 || candidates.size > 128 || candidates.any { it.language.length !in 1..32 || !it.score.isFinite() || it.score !in 0.0..1.0 } ||
            candidates.map { it.language }.toSet().size != candidates.size) return review("INVALID_EVIDENCE")
        if (ocrConflict) return review("OCR_CONFLICT")
        val letters = raw.codePoints().filter { Character.isLetter(it) }.toArray()
        if (letters.isEmpty()) return Decision("keep-literal", null, raw, "NO_LETTERS")
        val scripts = letters.map { Character.UnicodeScript.of(it) }.toSet()
        if (Character.UnicodeScript.HAN in scripts && Character.UnicodeScript.LATIN in scripts) return review("MIXED_SCRIPTS")
        if (letters.size < 8) return review("SHORT_TEXT")
        val sorted = candidates.sortedByDescending { it.score }; val first = sorted.firstOrNull() ?: return review("NO_CANDIDATES")
        if (first.score < .90 || first.score - (sorted.getOrNull(1)?.score ?: 0.0) < .20) return review("WEAK_OR_AMBIGUOUS")
        return when (first.language) {
            "en", "fr" -> Decision("translate", first.language, raw, "MODEL_EVIDENCE")
            "zh" -> Decision("keep", "zh", raw, "PRESERVE_CHINESE")
            else -> review("UNSUPPORTED_OR_UNDETERMINED")
        }
    }
}
