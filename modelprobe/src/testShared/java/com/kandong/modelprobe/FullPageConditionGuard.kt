package com.kandong.modelprobe

import java.text.Normalizer
import java.util.Collections
import java.util.Locale

/** Opt-in, bounded lexical risk gate, NOT a semantic-completeness classifier.
 * Where modifier scope is unknown, retain the entire page as source. This deliberately includes
 * unrelated lines, avoiding a geometric distance cutoff or leaking unrecognized continuations.
 * No candidate edits, deduplication, inferred language, external calls or v1 recording reuse.
 */
internal object FullPageConditionGuard {
    const val VERSION = "full-page-condition-guard-v1"
    const val REASON = "CONDITION_SCOPE_UNVERIFIED"
    private val refund = Regex("\\b(refunds?|remboursements?)\\b")
    private val english = Regex("\\b(except|unless|only|provided|providing|subject|conditional|if|not|never|excluding|however|but|otherwise|without|no|limited|restricted|required|must)\\b")
    private val french = Regex("\\b(sauf|uniquement|seulement|si|condition|reserve|ne|pas|jamais|hors|exception|cependant|mais|sans|non|obligatoire)\\b|\\bn['’]")
    private fun normalized(text: String) = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[\\s\\p{Z}]+"), " ").trim()
    private fun <T> frozen(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

    fun derive(association: FullPageOcrAssociation.Result, language: String): FullPageSemanticLayout.Layout {
        val base = FullPageSemanticLayout.derive(association, language)
        val cue = when (language) { "en" -> english; "fr" -> french; else -> null }
        val hasRefund = association.rawCandidates.any { refund.containsMatchIn(normalized(it.rawText)) }
        val hold = cue != null && hasRefund && association.rawCandidates.any {
            // The v1 head's own negation is already part of its complete two-line structure.
            !FullPageSemanticLayout.head(it.rawText, language) && cue.containsMatchIn(normalized(it.rawText))
        }
        val keys = base.groups.associate { it.key to "g:" + FullPageSemanticLayout.digest(listOf(VERSION, base.version, it.key)) }
        val groups = base.groups.map { g -> g.copy(key = keys.getValue(g.key),
            eligible = !hold && g.eligible, ambiguous = hold || g.ambiguous,
            reasons = frozen(if (hold) g.reasons + REASON else g.reasons),
            qualification = frozen(if (hold) emptyList() else g.qualification)) }
        val targets = if (hold) emptyList() else base.targets.map { t -> t.copy(key = keys[t.key] ?: t.key) }
        return base.copy(version = VERSION, groups = frozen(groups), targets = frozen(targets),
            blockedKeys = frozen(if (hold) base.orderedKeys else base.blockedKeys))
    }
}
