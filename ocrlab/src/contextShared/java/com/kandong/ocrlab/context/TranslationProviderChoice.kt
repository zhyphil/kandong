package com.kandong.ocrlab.context

/** Routing metadata only. Configuration is not a claim that a model passed quality review. */
enum class TranslationMode { LOCAL, ONLINE }
enum class TranslationIssue { PROVIDER_UNAVAILABLE, ONLINE_CONSENT_REQUIRED, NETWORK_UNAVAILABLE, REQUEST_FAILED }
data class TranslationProviderChoice(
    val mode: TranslationMode,
    val model: String,
    val version: String,
    val configured: Boolean = false,
    val disclosureVersion: String = "",
) {
    init {
        require(listOf(model, version).all { it.isNotBlank() && it.length <= 100 })
        require(disclosureVersion.length <= 100)
        require(mode != TranslationMode.ONLINE || disclosureVersion.isNotBlank())
    }
    companion object {
        /** Only the packaged synthetic demo uses this; no production translator is enabled. */
        fun fixture() = TranslationProviderChoice(TranslationMode.LOCAL,
            "HANDCRAFTED_NO_MODEL", "prewritten-fixture-v1", configured = true)
    }
}
