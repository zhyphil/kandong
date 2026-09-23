package com.kandong.app.domain

data class NodeFlags(val password: Boolean, val editable: Boolean, val sensitive: Boolean)

object NodePolicy {
    fun skipSubtree(flags: NodeFlags): Boolean = flags.password || flags.editable || flags.sensitive
    fun eligible(visible: Boolean, enabled: Boolean, bounds: Box): Boolean = visible && enabled && bounds.valid
}

/** A short-lived adapter, never stored in a snapshot. Flags are read before any label. */
interface NodeReader {
    val flags: NodeFlags
    val childCount: Int
    fun child(index: Int): NodeReader?
    val visible: Boolean
    val enabled: Boolean
    val clickable: Boolean
    val bounds: Box
    val windowId: Int
    val packageName: String
    val className: String
    val viewId: String?
    fun text(): CharSequence?
    fun description(): CharSequence?
}

data class ReadResult(val nodes: List<NodeSnapshot>, val truncated: Boolean, val rejected: Boolean)

class BoundedNodeReader(
    private val clock: () -> Long,
    private val maxNodes: Int = 300,
    private val maxDepth: Int = 24,
    private val maxText: Int = 160,
    private val budgetMs: Long = 250,
    private val cancelled: () -> Boolean = { false },
) {
    fun read(root: NodeReader, context: ScreenContext): ReadResult {
        val started = clock()
        val nodes = ArrayList<NodeSnapshot>()
        var visited = 0
        var rejected = false
        var truncated = false

        fun visit(node: NodeReader, path: String, depth: Int): Boolean {
            if (rejected) return true
            if (cancelled() || depth > maxDepth || visited >= maxNodes || clock() - started >= budgetMs) {
                rejected = true
                return true
            }
            val index = visited++
            // Also suppress labels on ancestors of a sensitive or unreadable subtree.
            if (NodePolicy.skipSubtree(node.flags)) return true
            if (node.windowId != context.windowId || node.packageName != context.packageName) {
                rejected = true
                return true
            }
            var sensitiveDescendant = false
            val children = node.childCount
            if (children < 0 || children > maxNodes) {
                rejected = true
                return true
            }
            for (i in 0 until children) {
                val child = node.child(i)
                if (child == null) {
                    rejected = true
                    return true
                }
                // A hidden container may have visible descendants. Do not prune by visibility.
                if (visit(child, "$path/$i", depth + 1)) sensitiveDescendant = true
                if (rejected) return true
            }
            if (sensitiveDescendant) return true
            val bounds = node.bounds.clip(context.bounds) ?: return false
            if (!NodePolicy.eligible(node.visible, node.enabled, bounds)) return false
            var labelTruncated = false
            fun bounded(value: CharSequence?): String {
                if (value == null) return ""
                if (value.length > maxText) {
                    truncated = true
                    labelTruncated = true
                }
                // Copy only a bounded subsequence; strip spans and never retain node-owned text.
                return value.subSequence(0, minOf(value.length, maxText)).toString().trim() +
                    if (value.length > maxText) "…" else ""
            }
            val text = bounded(node.text())
            val description = bounded(node.description())
            if (clock() - started >= budgetMs) {
                rejected = true
                return true
            }
            if (text.isNotBlank() || description.isNotBlank()) {
                nodes += NodeSnapshot(index, path, text, description,
                    node.className.take(maxText), node.clickable, node.enabled, bounds,
                    context.windowId, context.packageName, node.viewId?.take(maxText), labelTruncated)
            }
            return false
        }
        visit(root, "0", 0)
        return ReadResult(if (rejected) emptyList() else nodes.toList(), truncated || rejected, rejected)
    }
}
