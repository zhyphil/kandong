package com.kandong.app.domain

import java.util.Collections

data class ScreenContext(
    val packageName: String,
    val windowId: Int,
    val displayId: Int,
    val rotation: Int,
    val bounds: Box,
)

data class NodeSnapshot(
    val index: Int,
    val path: String,
    val text: String,
    val description: String,
    val className: String,
    val clickable: Boolean,
    val enabled: Boolean,
    val bounds: Box,
    val windowId: Int,
    val packageName: String,
    val viewId: String?,
    val labelTruncated: Boolean,
) {
    val label: String get() = text.ifBlank { description }
}

class Snapshot(
    val context: ScreenContext,
    nodes: List<NodeSnapshot>,
    val capturedAt: Long,
    val expiresAt: Long,
    val truncated: Boolean,
) {
    val nodes: List<NodeSnapshot> = Collections.unmodifiableList(ArrayList(nodes))
}

object CandidateSelector {
    fun candidates(nodes: List<NodeSnapshot>, panel: Box?): List<NodeSnapshot> = nodes
        .filter { it.enabled && it.label.isNotBlank() && it.bounds.valid &&
            (panel == null || !panel.intersects(it.bounds)) }
        .sortedWith(compareBy<NodeSnapshot> { !it.clickable }
            .thenBy { it.bounds.top }.thenBy { it.bounds.left }.thenBy { it.index })

    fun explanation(node: NodeSnapshot): String = if (node.clickable) {
        "原标签：“${node.label}”。应用将它标为可点击，请核对后自己点击。"
    } else {
        "页面文字：“${node.label}”。应用未将它标为可点击。"
    }
}
