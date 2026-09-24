package com.kandong.ocrlab.context

import java.util.Collections

/** All coordinates are normalized, unscaled SYNTHETIC screen units, never Android coordinates. */
data class ContextRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top
    fun valid() = listOf(left, top, right, bottom).all { it.isFinite() && kotlin.math.abs(it) <= 1_000_000 } && width > 0 && height > 0
    fun contains(other: ContextRect) = left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
}
enum class SourceKind { SYNTHETIC_FIXTURE }
enum class BlockState { KNOWN, UNKNOWN, ICON_ONLY, OCCLUDED, AMBIGUOUS, TRUNCATED, CONFLICT }
enum class GroupKind { CARD, PHRASE }
enum class ClearReason { PAGE_CHANGE, PAUSE, MENU, STOP, EXPIRED, INVALID_SNAPSHOT, CLOCK_INVALID }
data class ContextBlock(
    val id: String, val text: String, val language: String, val role: String,
    val original: ContextRect, val visible: ContextRect, val order: Int,
    val groupId: String? = null, val contextIds: List<String> = emptyList(),
    val state: BlockState = BlockState.KNOWN, val clipped: Boolean = false,
    val source: SourceKind = SourceKind.SYNTHETIC_FIXTURE,
    val confidence: Double? = null,
)
data class SemanticGroup(
    val id: String, val kind: GroupKind, val memberIds: List<String>,
    val complete: Boolean = true, val ambiguous: Boolean = false,
)
data class ScreenIdentity(
    val session: Long, val snapshotId: String, val pageGeneration: Long, val contentRevision: Long,
    val windowId: String = "synthetic-window", val displayId: String = "synthetic-display",
)
data class ScreenSnapshot(
    val identity: ScreenIdentity, val width: Double, val height: Double,
    val capturedAt: Long, val ttlMillis: Long, val blocks: List<ContextBlock>,
    val groups: List<SemanticGroup>, val coverageReasons: List<String> = emptyList(),
)
data class MirrorTransform(val scale: Double, val width: Double, val height: Double, val panX: Double = 0.0, val panY: Double = 0.0)
data class TargetBinding(
    val id: String, val role: String, val groupId: String?, val groupKind: GroupKind?,
    val sources: List<ContextBlock>, val context: List<ContextBlock>, val reason: String? = null,
)
data class ContextSelection(val generation: Long, val roi: ContextRect, val targets: List<TargetBinding>)
data class FixtureRequest(
    val id: Long, val page: ScreenIdentity, val targets: List<TargetBinding>,
    val targetLanguage: String = "zh-CN", val model: String = "HANDCRAFTED_NO_MODEL",
    val version: String = "prewritten-fixture-v1",
)
data class FixtureAnswer(val binding: TargetBinding, val chinese: String)
data class FixtureResponse(
    val requestId: Long, val page: ScreenIdentity, val targetLanguage: String,
    val model: String, val version: String, val answers: List<FixtureAnswer>,
)
data class SourceAnchor(val sourceId: String, val rect: ContextRect)
/** Text cards deliberately have no source-derived rectangle. They are laid out readably by the UI. */
data class TranslationCard(val targetId: String, val sourceText: String, val chinese: String?, val reason: String?)
data class ContextRender(val selectionGeneration: Long, val viewGeneration: Long, val anchors: List<SourceAnchor>, val cards: List<TranslationCard>)

internal fun <T> frozen(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
internal fun ContextBlock.freeze() = copy(contextIds = frozen(contextIds))
internal fun TargetBinding.freeze() = copy(sources = frozen(sources.map { it.freeze() }), context = frozen(context.map { it.freeze() }))
internal fun ScreenSnapshot.freeze() = copy(
    blocks = frozen(blocks.map { it.freeze() }),
    groups = frozen(groups.map { it.copy(memberIds = frozen(it.memberIds)) }),
    coverageReasons = frozen(coverageReasons),
)
