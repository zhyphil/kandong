package com.kandong.modelprobe

/** Synthetic experiment only. Raw candidates are evidence, never a language or correctness claim. */
internal object SharedCandidates {
    const val CONFIG = "shared-box-v3-agreement-guarded-latin"
    data class Point(val x: Double, val y: Double)
    data class Candidate(val pageId: String, val boxId: String, val readingOrder: Int, val quad: List<Point>, val raw: String)
    enum class Origin { AGREED, CH, LATIN, REVIEW }
    data class Decision(val origin: Origin, val raw: String?)
    data class Block(val pageId: String, val boxId: String, val readingOrder: Int, val quad: List<Point>,
        val ch: String, val latin: String, val decision: Decision)
    fun choose(ch: String, latin: String): Decision {
        require(ch.length <= 4096 && latin.length <= 4096)
        if (ch.isNotEmpty() && ch == latin) return Decision(Origin.AGREED, ch)
        if (ch.codePoints().anyMatch { cp ->
            val name = Character.getName(cp).orEmpty()
            name.startsWith("CJK UNIFIED IDEOGRAPH-") || name.startsWith("CJK COMPATIBILITY IDEOGRAPH-") ||
                name.startsWith("CJK UNIFIED IDEOGRAPHS ") // JDK algorithmic names use block-name + hex; Android uses Unicode names.
        }) return Decision(Origin.CH, ch)
        fun hasLatin(text: String) = text.codePoints().anyMatch { cp -> Character.isLetter(cp) && Character.getName(cp).orEmpty().contains("LATIN") }
        if (hasLatin(ch) && hasLatin(latin))
            return Decision(Origin.LATIN, latin)
        return Decision(Origin.REVIEW, null)
    }
    fun join(pageId: String, ch: List<Candidate>, latin: List<Candidate>): List<Block> {
        require(pageId.matches(Regex("[0-9a-f]{64}")))
        require(ch.size == latin.size && ch.size <= 8)
        fun validate(rows: List<Candidate>) {
            require(rows.map { it.boxId }.toSet().size == rows.size)
            require(rows.map { it.readingOrder }.toSet() == (rows.indices).toSet())
            rows.forEach { r ->
                require(r.pageId == pageId && r.boxId.matches(Regex("[a-z0-9.-]{1,128}")))
                require(r.quad.size == 4 && r.quad.all { it.x.isFinite() && it.y.isFinite() })
                require(r.raw.length <= 4096)
            }
        }
        validate(ch); validate(latin)
        val other = latin.associateBy { it.boxId }
        require(ch.map { it.boxId }.toSet() == other.keys)
        return ch.sortedBy { it.readingOrder }.map { a ->
            val b = other.getValue(a.boxId)
            require(a.quad == b.quad && a.readingOrder == b.readingOrder)
            Block(pageId, a.boxId, a.readingOrder, a.quad.toList(), a.raw, b.raw, choose(a.raw, b.raw))
        }
    }
    fun pageText(blocks: List<Block>): String? =
        if (blocks.any { it.decision.raw == null }) null else blocks.joinToString("\n") { checkNotNull(it.decision.raw) }
}
