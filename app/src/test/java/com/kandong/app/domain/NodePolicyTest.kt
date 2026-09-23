package com.kandong.app.domain

import org.junit.Assert.*
import org.junit.Test

class NodePolicyTest {
    private val context = ScreenContext("fixture", 7, 0, 0, Box(0, 0, 1000, 1000))
    private class FakeNode(
        override val flags: NodeFlags = NodeFlags(false, false, false),
        val label: String = "标签",
        val children: List<FakeNode> = emptyList(),
        override val visible: Boolean = true,
        override val enabled: Boolean = true,
        val prohibitText: Boolean = false,
    ) : NodeReader {
        override val childCount get() = children.size
        override fun child(index: Int) = children[index]
        override val clickable = true
        override val bounds = Box(0, 0, 100, 100)
        override val windowId = 7
        override val packageName = "fixture"
        override val className = "Button"
        override val viewId = "fixture:id/button"
        override fun text(): CharSequence { check(!prohibitText) { "Sensitive getter called" }; return label }
        override fun description(): CharSequence { check(!prohibitText); return "说明" }
    }
    @Test fun sensitiveGettersAndAncestorLabelsAreNeverRead() {
        for (flags in listOf(NodeFlags(true, false, false), NodeFlags(false, true, false), NodeFlags(false, false, true))) {
            val root = FakeNode(prohibitText = true, children = listOf(
                FakeNode(flags, prohibitText = true, children = listOf(FakeNode(prohibitText = true))),
                FakeNode(label = "安全兄弟")))
            val result = BoundedNodeReader({ 0 }).read(root, context)
            assertFalse(result.rejected)
            assertEquals(listOf("安全兄弟"), result.nodes.map { it.text })
        }
    }
    @Test fun invisibleParentsDoNotHideVisibleChildren() {
        val result = BoundedNodeReader({ 0 }).read(FakeNode(visible = false, prohibitText = true,
            children = listOf(FakeNode(label = "可见孩子"))), context)
        assertEquals(listOf("可见孩子"), result.nodes.map { it.text })
    }
    @Test fun disabledLabelsAreNotRead() {
        assertTrue(BoundedNodeReader({ 0 }).read(FakeNode(enabled = false, prohibitText = true), context).nodes.isEmpty())
    }
    @Test fun traversalLimitsRejectEntirePartialSnapshot() {
        val root = FakeNode(children = listOf(FakeNode(), FakeNode()))
        val count = BoundedNodeReader({ 0 }, maxNodes = 2).read(root, context)
        assertTrue(count.rejected); assertTrue(count.truncated); assertTrue(count.nodes.isEmpty())
        val depth = BoundedNodeReader({ 0 }, maxDepth = 0).read(root, context)
        assertTrue(depth.rejected); assertTrue(depth.nodes.isEmpty())
    }
    @Test fun cancellationAndDeadlineRejectPublication() {
        assertTrue(BoundedNodeReader({ 0 }, cancelled = { true }).read(FakeNode(prohibitText = true), context).rejected)
        var now = 0L
        assertTrue(BoundedNodeReader({ now.also { now += 300 } }).read(FakeNode(prohibitText = true), context).rejected)
    }
    @Test fun longLabelsAreBoundedAndFlagged() {
        val result = BoundedNodeReader({ 0 }, maxText = 4).read(FakeNode(label = "12345678"), context)
        assertEquals("1234…", result.nodes.single().text)
        assertTrue(result.truncated); assertTrue(result.nodes.single().labelTruncated)
    }
}
