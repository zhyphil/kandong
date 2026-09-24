package com.kandong.ocrlab.context

/** Fixed source and Chinese for button/geometry tests, never a translation provider. */
object OnDemandFixtures {
    val source = listOf("Train to Paris", "Departure 18:40", "No refund after departure")
    private val chinese = listOf("开往巴黎的列车", "18:40 出发", "出发后不可退款")
    fun roi(index: Int) = ContextRect(10.0, 35.0 + index * 75.0, 310.0, 85.0 + index * 75.0)
    fun page(identity: TranslationPage, now: Long): ScreenSnapshot {
        val blocks = source.mapIndexed { i, text ->
            val rect = ContextRect(20.0, 40.0 + i * 75, 300.0, 75.0 + i * 75)
            ContextBlock("train.$i", text, "en", "label", rect, rect, i, groupId = "train")
        }
        return ScreenSnapshot(ScreenIdentity(identity.session, "button-fixture", identity.generation, identity.revision,
            identity.window, identity.display), 320.0, 280.0, now, 60_000, blocks,
            listOf(SemanticGroup("train", GroupKind.CARD, blocks.map { it.id })))
    }
    fun response(request: FixtureRequest): FixtureResponse {
        val answers = request.targets.map { b ->
            val index = source.indices.single { b.id == "train.$it" && b.sources.single().text == source[it] }
            FixtureAnswer(b, chinese[index])
        }
        return FixtureResponse(request.id, request.page, request.targetLanguage, request.model, request.version, answers)
    }
}
