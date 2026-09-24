package com.kandong.ocrlab.context

/** Single-threaded, bounded, one-page synthetic experiment. No IO, text logging or semantic certification. */
class ContextEngine {
    var snapshot: ScreenSnapshot? = null
        private set
    var selection: ContextSelection? = null
        private set
    var transform: MirrorTransform? = null
        private set
    var lastClear: ClearReason? = null
        private set
    private var selectionGeneration = 0L
    private var viewGeneration = 0L
    private var nextRequest = 0L
    private var lastNow = -1L
    private val pending = linkedMapOf<Long, FixtureRequest>()
    private val cache = linkedMapOf<String, FixtureAnswer>()
    val pendingCount get() = pending.size
    val cacheCount get() = cache.size

    fun clear(reason: ClearReason) {
        snapshot = null; selection = null; transform = null
        pending.clear(); cache.clear(); lastClear = reason
        selectionGeneration++; viewGeneration++
    }
    private fun clock(now: Long): Boolean {
        if (now < 0 || now > Long.MAX_VALUE - MAX_TTL || now < lastNow) {
            clear(ClearReason.CLOCK_INVALID); return false
        }
        lastNow = now
        return true
    }
    fun tick(now: Long): Boolean {
        if (!clock(now)) return false
        val page = snapshot ?: return false
        if (now >= page.capturedAt + page.ttlMillis) {
            clear(ClearReason.EXPIRED); return false
        }
        return true
    }
    fun activateSnapshot(input: ScreenSnapshot, now: Long): Boolean {
        // Even a rejected replacement revokes the old page and every publication capability.
        clear(ClearReason.INVALID_SNAPSHOT)
        if (!clock(now) || !valid(input, now)) return false
        snapshot = input.freeze(); lastClear = null
        return true
    }
    private fun valid(p: ScreenSnapshot, now: Long): Boolean {
        fun token(s: String) = s.isNotBlank() && s.length <= 100
        val key = p.identity
        if (key.session < 0 || key.pageGeneration < 0 || key.contentRevision < 0 ||
            !listOf(key.snapshotId, key.windowId, key.displayId).all(::token)) return false
        if (!p.width.isFinite() || !p.height.isFinite() || p.width <= 0 || p.height <= 0 || p.width > 10_000 || p.height > 10_000) return false
        if (p.capturedAt < 0 || p.capturedAt > now || p.ttlMillis !in 1..MAX_TTL || p.capturedAt > Long.MAX_VALUE - p.ttlMillis || now >= p.capturedAt + p.ttlMillis) return false
        if (p.blocks.size !in 1..128 || p.groups.size > 64 || p.coverageReasons.size > 16 || p.coverageReasons.any { it.length > 200 }) return false
        if (p.blocks.map { it.id }.distinct().size != p.blocks.size || p.groups.map { it.id }.distinct().size != p.groups.size) return false
        if (p.blocks.map { it.id }.intersect(p.groups.map { it.id }.toSet()).isNotEmpty()) return false
        val blocks = p.blocks.associateBy { it.id }; val groups = p.groups.associateBy { it.id }
        val screen = ContextRect(0.0, 0.0, p.width, p.height)
        for (b in p.blocks) {
            if (!b.validOcr(key.snapshotId)) return false
            if (!listOf(b.id, b.language, b.role).all(::token) || b.text.length > 2000 || b.order < 0 ||
                (b.state == BlockState.KNOWN && b.text.isBlank())) return false
            if (!b.original.valid() || !b.visible.valid() || !screen.contains(b.visible) || !b.original.contains(b.visible)) return false
            if ((!screen.contains(b.original) || b.original != b.visible) && !b.clipped) return false
            if (b.confidence != null && (!b.confidence.isFinite() || b.confidence !in 0.0..1.0)) return false
            if (b.contextIds.size > 32 || b.contextIds.distinct().size != b.contextIds.size || b.contextIds.any { it !in blocks || it == b.id }) return false
            if (b.groupId != null && (groups[b.groupId]?.memberIds?.contains(b.id) != true)) return false
            // A group-owned element may refer to its own group or an ungrouped page heading,
            // never to another card/phrase. Global links are still not traversed transitively.
            if (b.groupId != null && b.contextIds.any { id ->
                    val owner = blocks.getValue(id).groupId
                    owner != null && owner != b.groupId
                }) return false
        }
        for (g in p.groups) {
            if (!token(g.id) || g.memberIds.size !in 1..32 || g.memberIds.any { !token(it) } || g.memberIds.distinct().size != g.memberIds.size) return false
            // Missing declared group members remain missing evidence; dangling relations are invalid above.
            if (g.memberIds.any { blocks[it]?.let { b -> b.groupId != g.id } == true }) return false
        }
        return true
    }
    fun select(roi: ContextRect, now: Long): ContextSelection? {
        if (!tick(now)) return null
        val p = snapshot!!
        require(roi.valid() && ContextRect(0.0, 0.0, p.width, p.height).contains(roi))
        val blocks = p.blocks.associateBy { it.id }; val groups = p.groups.associateBy { it.id }
        val targets = linkedMapOf<String, TargetBinding>()
        for (b in p.blocks.sortedBy { it.order }) {
            if (ContextGeometry.intersect(b.visible, roi) == null) continue
            val group = groups[b.groupId]
            val phrase = group?.kind == GroupKind.PHRASE
            val sourceIds = if (phrase) group!!.memberIds else listOf(b.id)
            val sources = sourceIds.mapNotNull { blocks[it] }.sortedBy { it.order }
            val contextIds = (group?.memberIds.orEmpty() + sources.flatMap { it.contextIds }).distinct().filter { it !in sourceIds }
            val context = contextIds.mapNotNull { blocks[it] }.sortedBy { it.order }
            val id = if (phrase) group!!.id else b.id
            val reason = when {
                group?.ambiguous == true -> "AMBIGUOUS_GROUP"
                group?.complete == false || group?.memberIds?.any { it !in blocks } == true -> "MISSING_GROUP_MEMBER"
                (sources + context).any { it.state != BlockState.KNOWN } -> "INCOMPLETE_${(sources + context).first { it.state != BlockState.KNOWN }.state}"
                (sources + context).any { it.clipped } -> "TRUNCATED_GEOMETRY"
                else -> null
            }
            targets[id] = TargetBinding(id, if (phrase) "phrase" else b.role, b.groupId, group?.kind, sources, context, reason).freeze()
        }
        selection = ContextSelection(++selectionGeneration, roi, frozen(targets.values))
        transform = ContextGeometry.transform(roi, transform ?: MirrorTransform(2.0, 240.0, 120.0))
        viewGeneration++
        return selection
    }
    fun setTransform(value: MirrorTransform, now: Long): Boolean {
        if (!tick(now)) return false
        val current = selection ?: return false
        transform = ContextGeometry.transform(current.roi, value); viewGeneration++
        return true
    }
    fun requestMissing(now: Long): FixtureRequest? {
        if (!tick(now) || pending.size >= 8) return null
        val alreadyPending = pending.values.flatMap { it.targets }.map { it.id }.toSet()
        val targets = selection?.targets?.filter { it.reason == null && it.id !in cache && it.id !in alreadyPending }.orEmpty()
        if (targets.isEmpty()) return null
        val request = FixtureRequest(++nextRequest, snapshot!!.identity, frozen(targets.map { it.freeze() }))
        pending[request.id] = request
        return request
    }
    fun cancelRequest(id: Long): Boolean = pending.remove(id) != null
    fun acceptResponse(response: FixtureResponse, now: Long): Boolean {
        if (!tick(now)) return false
        val req = pending[response.requestId] ?: return false
        if (response.page != req.page || response.targetLanguage != req.targetLanguage || response.model != req.model || response.version != req.version) return false
        if (response.answers.size != req.targets.size || response.answers.map { it.binding.id }.distinct().size != response.answers.size) return false
        val expected = req.targets.associateBy { it.id }
        if (response.answers.any { expected[it.binding.id] != it.binding || it.chinese.isBlank() || it.chinese.length > 2000 }) return false
        // All-or-nothing structural validation only. It cannot establish translation meaning/quality.
        response.answers.forEach { cache[it.binding.id] = it.copy(binding = it.binding.freeze()) }
        pending.remove(req.id)
        return true
    }
    fun render(now: Long): ContextRender {
        val live = tick(now)
        val current = selection; val t = transform
        if (!live || current == null || t == null) return ContextRender(selectionGeneration, viewGeneration, emptyList(), emptyList())
        val anchors = current.targets.flatMap { target -> target.sources.mapNotNull { b ->
            ContextGeometry.map(b.visible, current.roi, t)?.let { SourceAnchor(b.id, it) }
        } }
        val cards = current.targets.map { target -> TranslationCard(
            target.id, target.sources.joinToString(" ") { it.text }, cache[target.id]?.chinese,
            target.reason ?: if (target.id !in cache) "AWAITING_HANDCRAFTED_FIXTURE" else null,
        ) }
        return ContextRender(current.generation, viewGeneration, frozen(anchors), frozen(cards))
    }
    companion object { const val MAX_TTL = 60_000L }
}
