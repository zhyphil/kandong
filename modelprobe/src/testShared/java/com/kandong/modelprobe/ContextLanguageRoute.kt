package com.kandong.modelprobe

/** Experimental test-only evidence. Manual groups are not a claim of automatic semantic grouping. */
internal object ContextLanguageRoute {
    const val CONFIG = "context-v2:anchor.90-gap.20;support.10;veto.80-gap.20;windows1-3-minletters4"
    data class Block(val id: String, val groupId: String, val raw: String, val ocrConflict: Boolean = false)
    data class Page(val id: String, val blocks: List<Block>)
    data class Query(val key: String, val raw: String, val blockId: String? = null, val start: Int? = null, val end: Int? = null)
    data class Observation(val pageId: String, val query: Query, val candidates: List<LanguageRoute.Candidate>)
    private val idPattern = Regex("[a-zA-Z0-9_-]{1,64}")
    private val words = Regex("[\\p{L}\\p{M}]+(?:['’][\\p{L}\\p{M}]+)*")

    fun queries(page: Page): List<Query> {
        require(page.id.matches(Regex("[a-f0-9]{64}")) && page.blocks.size in 1..32)
        require(page.blocks.map { it.id }.toSet().size == page.blocks.size)
        require(page.blocks.all { idPattern.matches(it.id) && idPattern.matches(it.groupId) && it.raw.length <= 512 })
        require(page.blocks.sumOf { it.raw.length } <= 8192)
        val queries = mutableListOf(Query("page", page.blocks.joinToString("\n") { it.raw }))
        page.blocks.groupBy { it.groupId }.forEach { (group, blocks) ->
            queries += Query("group:$group", blocks.joinToString("\n") { it.raw })
        }
        for (b in page.blocks) {
            queries += Query("block:${b.id}", b.raw, b.id)
            val tokens = words.findAll(b.raw).toList()
            val spans = mutableListOf<Query>()
            for (i in tokens.indices) for (count in 1..3) {
                if (i + count > tokens.size) continue
                val start = tokens[i].range.first
                val end = tokens[i + count - 1].range.last + 1
                if (start == 0 && end == b.raw.length) continue // full block already queried
                val text = b.raw.substring(start, end)
                val letters = text.codePoints().filter { Character.isLetter(it) }.toArray()
                if (letters.size < 4 || letters.none { Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN }) continue
                spans += Query("span:${b.id}:$start:$end", text, b.id, start, end)
            }
            require(spans.size <= 96) { "SPAN_BUDGET" }
            queries += spans
        }
        return queries
    }

    private fun valid(candidates: List<LanguageRoute.Candidate>) = candidates.size in 1..128 &&
        candidates.all { it.language.length in 1..32 && it.score.isFinite() && it.score in 0.0..1.0 } &&
        candidates.map { it.language }.toSet().size == candidates.size

    private fun strong(candidates: List<LanguageRoute.Candidate>, threshold: Double): String? {
        val sorted = candidates.sortedByDescending { it.score }
        val first = sorted.firstOrNull() ?: return null
        return first.language.takeIf { it != "und" && first.score >= threshold && first.score - (sorted.getOrNull(1)?.score ?: 0.0) >= .20 }
    }

    fun choose(page: Page, targetId: String, evidence: List<Observation>, spanVeto: Boolean): LanguageRoute.Decision {
        val target = page.blocks.singleOrNull { it.id == targetId }
        fun review(reason: String) = LanguageRoute.Decision("review", null, target?.raw ?: "", reason)
        val queries = try { queries(page) } catch (_: IllegalArgumentException) { return review("INVALID_PAGE") }
        if (target == null) return review("MISSING_TARGET")
        if (evidence.size != queries.size || evidence.map { it.query.key }.toSet().size != evidence.size) return review("INVALID_BINDING")
        val byKey = evidence.associateBy { it.query.key }
        if (queries.any { q -> byKey[q.key]?.let { it.pageId != page.id || it.query != q || !valid(it.candidates) } != false }) return review("INVALID_BINDING")
        val group = page.blocks.filter { it.groupId == target.groupId }
        if (group.any { it.ocrConflict }) return review("OCR_CONFLICT")
        fun candidates(key: String) = byKey.getValue(key).candidates
        fun base(b: Block) = LanguageRoute.choose(b.raw, candidates("block:${b.id}"), b.ocrConflict)
        // The existing full-block observation also checks a short target's competing strong language.
        fun contradicts(b: Block, language: String) = queries.asSequence()
            .filter { it.blockId == b.id }
            .any { q -> strong(candidates(q.key), .80)?.let { it != language } == true }
        val base = base(target)
        if (base.action != "review") {
            if (base.language != null && spanVeto && contradicts(target, base.language)) return review("SPAN_LANGUAGE_CONFLICT")
            return base
        }
        if (base.reason !in setOf("SHORT_TEXT", "WEAK_OR_AMBIGUOUS")) return base
        val anchors = group.filter { it.id != targetId }.map { it to base(it) }
            .filter { (_, decision) -> decision.action in setOf("translate", "keep") }
        val languages = anchors.mapNotNull { it.second.language }.toSet()
        if (languages.size != 1) return review(if (languages.isEmpty()) "NO_GROUP_ANCHOR" else "GROUP_LANGUAGE_CONFLICT")
        val language = languages.single()
        if (spanVeto && (contradicts(target, language) || anchors.any { contradicts(it.first, language) })) return review("SPAN_LANGUAGE_CONFLICT")
        if (strong(candidates("group:${target.groupId}"), .90) != language) return review("GROUP_EVIDENCE_WEAK")
        if ((candidates("block:${target.id}").firstOrNull { it.language == language }?.score ?: 0.0) < .10) return review("TARGET_SUPPORT_WEAK")
        return LanguageRoute.Decision(if (language == "zh") "keep" else "translate", language, target.raw, "GROUP_SUPPORTED_CANDIDATE")
    }
}
