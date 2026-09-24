package com.kandong.ocrlab.context

/** Hand-authored blocks, relationships AND Chinese. No recognition or translation model. */
object ContextFixtures {
    val scenes = listOf("酒店两张卡", "重复 Continue", "酒店 Book", "书店 Book", "完整双重否定", "缺失后半句", "不完整证据")
    data class Preset(val name: String, val roi: ContextRect)
    fun presets(scene: Int): List<Preset> = when (scene) {
        0 -> listOf(Preset("A 价格", ContextRect(20.0, 110.0, 125.0, 145.0)), Preset("B 价格", ContextRect(20.0, 300.0, 125.0, 335.0)))
        1 -> listOf(Preset("两个 Continue", ContextRect(20.0, 110.0, 160.0, 335.0)), Preset("A Continue", ContextRect(20.0, 110.0, 160.0, 145.0)), Preset("B Continue", ContextRect(20.0, 300.0, 160.0, 335.0)))
        2, 3 -> listOf(Preset("Book", ContextRect(20.0, 110.0, 125.0, 145.0)))
        4, 5 -> listOf(Preset("只框前半句", ContextRect(20.0, 110.0, 360.0, 145.0)))
        else -> listOf(Preset("全部可见证据", ContextRect(10.0, 65.0, 390.0, 550.0)))
    }
    fun page(scene: Int, session: Long, now: Long): ScreenSnapshot {
        require(scene in scenes.indices)
        val blocks = mutableListOf<ContextBlock>()
        val groups = mutableListOf<SemanticGroup>()
        fun b(id: String, text: String, role: String, y: Double, group: String? = null, w: Double = 350.0,
              refs: List<String> = emptyList(), language: String = "en", state: BlockState = BlockState.KNOWN) {
            val rect = ContextRect(20.0, y, 20.0 + w, y + 25.0)
            blocks += ContextBlock(id, text, language, role, rect, rect, blocks.size, group, refs, state)
        }
        b("page.heading", scenes[scene], "heading", 15.0, language = "zh-CN")
        when (scene) {
            0 -> {
                for ((prefix, y) in listOf("a" to 75.0, "b" to 265.0)) {
                    val a = prefix == "a"; val g = "hotel.$prefix"
                    b("$prefix.title", if (a) "Hotel A" else "Hotel B", "title", y, g)
                    b("$prefix.price", if (a) "120 €" else "300 €", "price", y + 40, g, 100.0, listOf("page.heading"))
                    b("$prefix.unit", if (a) "/ night" else "/ total, 2 nights", "unit", y + 75, g)
                    b("$prefix.condition", if (a) "Free cancellation until 15 Oct 18:00" else "Non-refundable", "condition", y + 110, g)
                    groups += SemanticGroup(g, GroupKind.CARD, listOf("$prefix.title", "$prefix.price", "$prefix.unit", "$prefix.condition"))
                }
            }
            1 -> {
                b("continue.a.title", "Review reservation", "title", 75.0, "continue.a")
                b("continue.a.button", "Continue", "button", 115.0, "continue.a", 130.0)
                b("continue.b.title", "Review book order", "title", 265.0, "continue.b")
                b("continue.b.button", "Continue", "button", 305.0, "continue.b", 130.0)
                // Titles sit beside the buttons so a narrow ROI can hit both repeated labels only.
                for (index in blocks.indices) {
                    if (blocks[index].id in listOf("continue.a.title", "continue.b.title")) {
                        val rect = blocks[index].visible.copy(left = 200.0, right = 390.0)
                        blocks[index] = blocks[index].copy(original = rect, visible = rect)
                    }
                }
                groups += SemanticGroup("continue.a", GroupKind.CARD, listOf("continue.a.title", "continue.a.button"))
                groups += SemanticGroup("continue.b", GroupKind.CARD, listOf("continue.b.title", "continue.b.button"))
            }
            2, 3 -> {
                val prefix = if (scene == 2) "booking" else "bookshop"
                b("$prefix.title", if (scene == 2) "Reserve a hotel room" else "Bookshop catalogue", "title", 75.0, prefix)
                b("$prefix.book", "Book", if (scene == 2) "button" else "category", 115.0, prefix, 100.0, listOf("page.heading"))
                groups += SemanticGroup(prefix, GroupKind.CARD, listOf("$prefix.title", "$prefix.book"))
            }
            4, 5 -> {
                b("neg.first", "Vous ne pouvez pas", "phrase-fragment", 115.0, "neg.phrase", refs = listOf("page.heading"), language = "fr")
                if (scene == 4) b("neg.second", "ne pas payer.", "phrase-fragment", 170.0, "neg.phrase", language = "fr")
                groups += SemanticGroup("neg.phrase", GroupKind.PHRASE, listOf("neg.first", "neg.second"), complete = scene == 4)
            }
            6 -> {
                BlockState.entries.filter { it != BlockState.KNOWN }.forEachIndexed { i, state ->
                    b("evidence.$i", if (state == BlockState.ICON_ONLY) "" else "Visible fragment $i", "unknown", 75.0 + i * 50.0, state = state)
                }
                b("missing.visible", "Visible condition", "condition", 390.0, "missing.group")
                groups += SemanticGroup("missing.group", GroupKind.PHRASE, listOf("missing.visible", "missing.unavailable"), complete = false)
                b("ambiguous.visible", "Uncertain ownership", "label", 440.0, "ambiguous.group")
                groups += SemanticGroup("ambiguous.group", GroupKind.CARD, listOf("ambiguous.visible"), ambiguous = true)
                b("known.book", "Book", "category", 500.0, "known.group", 100.0)
                b("known.title", "Bookshop catalogue", "title", 550.0, "known.group")
                groups += SemanticGroup("known.group", GroupKind.CARD, listOf("known.book", "known.title"))
            }
        }
        return ScreenSnapshot(ScreenIdentity(session, "scene-$scene-$session", 1, 1), 400.0, 600.0, now, 60_000,
            blocks, groups, if (scene == 5) listOf("后半句不可见；未知内容未保存在快照中") else emptyList())
    }
    fun response(request: FixtureRequest): FixtureResponse? {
        val values = mapOf(
            "a.price" to ("120 €" to "每晚 120 欧元；可在 10 月 15 日 18:00 前免费取消"),
            "b.price" to ("300 €" to "2 晚合计 300 欧元；不可退款"),
            "continue.a.button" to ("Continue" to "继续"), "continue.b.button" to ("Continue" to "继续"),
            "booking.book" to ("Book" to "预订"), "bookshop.book" to ("Book" to "书籍"),
            "known.book" to ("Book" to "书籍"),
            "neg.phrase" to ("Vous ne pouvez pas ne pas payer." to "您不能不付款。"),
        )
        val answers = request.targets.map { target ->
            val entry = values[target.id] ?: return null
            if (target.sources.joinToString(" ") { it.text } != entry.first) return null
            FixtureAnswer(target, entry.second)
        }
        return FixtureResponse(request.id, request.page, request.targetLanguage, request.model, request.version, answers)
    }
}
