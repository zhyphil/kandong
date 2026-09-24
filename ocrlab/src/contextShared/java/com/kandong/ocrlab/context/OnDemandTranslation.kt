package com.kandong.ocrlab.context

/** Metadata only; observing a page never authorizes reading its text. */
data class TranslationPage(val session: Long, val generation: Long, val revision: Long,
    val window: String = "synthetic-window", val display: String = "synthetic-display") {
    fun matches(id: ScreenIdentity) = session == id.session && generation == id.pageGeneration &&
        revision == id.contentRevision && window == id.windowId && display == id.displayId
}

/** Synthetic one-click/one-page experiment. Acquisition/model work is outside this class. */
class OnDemandTranslation(initialProvider: TranslationProviderChoice = TranslationProviderChoice.fixture()) {
    var provider = initialProvider
        private set
    var issue: TranslationIssue? = null
        private set
    private var networkAvailable = false
    private var onlineConsent: Pair<Long, TranslationProviderChoice>? = null

    /** A user selection revokes in-flight work and prior provider consent, never starts IO. */
    fun chooseProvider(value: TranslationProviderChoice) {
        if (value == provider) return
        invalidate(ClearReason.PROVIDER_CHANGE)
        onlineConsent = null
        provider = value
    }
    /** The UI supplies the exact disclosure choice/session it displayed, not a blanket flag. */
    fun confirmOnlineUse(shown: TranslationProviderChoice, session: Long): Boolean {
        if (shown != provider || shown.mode != TranslationMode.ONLINE || !shown.configured || page?.session != session) return false
        onlineConsent = session to shown
        issue = null
        return true
    }
    fun revokeOnlineUse() {
        onlineConsent = null
        if (provider.mode == TranslationMode.ONLINE) invalidate(ClearReason.PROVIDER_CHANGE)
    }
    fun setNetworkAvailable(available: Boolean) {
        networkAvailable = available
        if (!available && provider.mode == TranslationMode.ONLINE) {
            invalidate(ClearReason.PROVIDER_CHANGE)
            issue = TranslationIssue.NETWORK_UNAVAILABLE
        }
        // Reconnection never captures, retries, or falls back to another provider.
    }

    enum class Phase { ORIGINAL, CAPTURING, PROCESSING, DISPLAYING, NEEDS_REFRESH, FAILED }
    class Capture internal constructor(val page: TranslationPage, val clickedAt: Long, val provider: TranslationProviderChoice)
    private val engine = ContextEngine()
    private var page: TranslationPage? = null
    private var capture: Capture? = null
    private var lastNow = -1L
    var phase = Phase.ORIGINAL
        private set
    var pending: FixtureRequest? = null
        private set
    val snapshot get() = engine.snapshot
    val selection get() = engine.selection
    val transform get() = engine.transform

    fun observePage(value: TranslationPage) {
        if (page == value) return
        val previous = page
        if (previous?.session != value.session) onlineConsent = null
        invalidate(ClearReason.PAGE_CHANGE)
        page = value
        if (previous == null) phase = Phase.ORIGINAL
    }
    fun invalidate(reason: ClearReason) {
        capture = null; pending = null; engine.clear(reason); issue = null
        if (reason == ClearReason.STOP || reason == ClearReason.CLOCK_INVALID) onlineConsent = null
        phase = if (reason == ClearReason.PAGE_CHANGE || reason == ClearReason.EXPIRED) Phase.NEEDS_REFRESH else Phase.ORIGINAL
    }
    private fun clock(now: Long): Boolean {
        if (now < lastNow || now < 0 || now > Long.MAX_VALUE - ContextEngine.MAX_TTL) {
            invalidate(ClearReason.CLOCK_INVALID); phase = Phase.FAILED; return false
        }
        lastNow = now
        return true
    }
    fun tick(now: Long): Boolean {
        if (!clock(now)) return false
        val ticket = capture
        if (ticket != null && now - ticket.clickedAt >= ContextEngine.MAX_TTL) {
            invalidate(ClearReason.EXPIRED); return false
        }
        if (phase == Phase.PROCESSING || phase == Phase.DISPLAYING) {
            if (!engine.tick(now)) { invalidate(ClearReason.EXPIRED); return false }
            return true
        }
        return false
    }
    /** Only the explicit UI click calls this. Repeated clicks while busy do not enqueue work. */
    fun clickTranslate(now: Long): Capture? {
        if (!clock(now)) return null
        tick(now)
        if (phase == Phase.CAPTURING || phase == Phase.PROCESSING) return null
        val current = page ?: return null
        invalidate(ClearReason.PAGE_CHANGE)
        issue = when {
            !provider.configured -> TranslationIssue.PROVIDER_UNAVAILABLE
            provider.mode == TranslationMode.ONLINE && onlineConsent != (current.session to provider) -> TranslationIssue.ONLINE_CONSENT_REQUIRED
            provider.mode == TranslationMode.ONLINE && !networkAvailable -> TranslationIssue.NETWORK_UNAVAILABLE
            else -> null
        }
        if (issue != null) { phase = Phase.FAILED; return null }
        return Capture(current, now, provider).also { capture = it; phase = Phase.CAPTURING }
    }
    fun captured(ticket: Capture, input: ScreenSnapshot, roi: ContextRect, now: Long): Boolean {
        if (ticket !== capture) return false
        tick(now)
        if (ticket !== capture) return false
        capture = null
        if (page != ticket.page || !ticket.page.matches(input.identity) || input.capturedAt < ticket.clickedAt) {
            invalidate(ClearReason.INVALID_SNAPSHOT); phase = Phase.FAILED; return false
        }
        try {
            if (!engine.activateSnapshot(input, now)) {
                invalidate(ClearReason.INVALID_SNAPSHOT); phase = Phase.FAILED; return false
            }
            // Translate eligible targets of this captured page once; ROI only selects presentation.
            engine.select(ContextRect(0.0, 0.0, input.width, input.height), now)
            pending = engine.requestMissing(now, ticket.provider.model, ticket.provider.version)
            engine.select(roi, now)
            phase = if (pending == null) Phase.DISPLAYING else Phase.PROCESSING
            return true
        } catch (_: IllegalArgumentException) {
            invalidate(ClearReason.INVALID_SNAPSHOT); phase = Phase.FAILED; return false
        }
    }
    fun select(roi: ContextRect, now: Long): ContextSelection? = if (tick(now)) engine.select(roi, now) else null
    fun setTransform(value: MirrorTransform, now: Long): Boolean = tick(now) && engine.setTransform(value, now)
    fun accept(response: FixtureResponse, now: Long): Boolean {
        if (!tick(now) || pending?.id != response.requestId) return false
        if (!engine.acceptResponse(response, now)) return false
        pending = null; phase = Phase.DISPLAYING
        return true
    }
    /** Callbacks carry their request ID: an old failure cannot cancel newer work. */
    fun failed(requestId: Long, now: Long): Boolean {
        if (!tick(now) || pending?.id != requestId) return false
        invalidate(ClearReason.PROVIDER_CHANGE)
        issue = TranslationIssue.REQUEST_FAILED; phase = Phase.FAILED
        return true
    }
    fun render(now: Long): ContextRender {
        tick(now)
        return engine.render(now)
    }
}
