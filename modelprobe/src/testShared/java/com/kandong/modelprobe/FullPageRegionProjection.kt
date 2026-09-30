package com.kandong.modelprobe

import com.kandong.ocrlab.context.ContextGeometry
import com.kandong.ocrlab.context.ContextRect
import com.kandong.ocrlab.context.MirrorTransform
import java.util.Collections

/** Bounded display geometry only. No text filtering, deduplication, semantics or inference. */
internal object FullPageRegionProjection {
    private fun <T> frozen(items: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(items))
    private fun hasArea(q: List<GeometryProbeContract.Point>): Boolean {
        val a = q.first()
        val b = q.firstOrNull { it != a } ?: return false
        // Test non-collinearity, not signed polygon area: unordered corners can cross while
        // retaining a useful conservative envelope. A diagonal line must not become a box.
        return q.any { (b.x-a.x)*(it.y-a.y) != (b.y-a.y)*(it.x-a.x) }
    }
    private fun axisAligned(q: List<GeometryProbeContract.Point>, b: ContextRect): Boolean {
        val corners = listOf(GeometryProbeContract.Point(b.left, b.top), GeometryProbeContract.Point(b.right, b.top),
            GeometryProbeContract.Point(b.right, b.bottom), GeometryProbeContract.Point(b.left, b.bottom))
        // Exact axis alignment only. Both windings and any starting corner are supported.
        return (0..3).any { start -> listOf(1, -1).any { direction ->
            q.indices.all { q[it] == corners[(start + direction * it + 4) % 4] }
        } }
    }
    fun project(metadata: RgbaFrameMetadata, page: FullPageOcrAssociation.Result, roi: ContextRect,
        transform: MirrorTransform, checkpoint: () -> Unit): FullPageRegionController.Frame? {
        require(roi.valid())
        val clipped = ContextGeometry.intersect(roi,
            ContextRect(0.0, 0.0, metadata.width.toDouble(), metadata.height.toDouble())) ?: return null
        val t = ContextGeometry.transform(clipped, transform)
        val groups = hashMapOf<String, FullPageOcrAssociation.Group>()
        for (g in page.groups) {
            checkpoint()
            for (id in g.memberIds) { checkpoint(); groups[id] = g }
        }
        val selected = arrayListOf<String>()
        val visible = arrayListOf<FullPageRegionController.Projection>()
        val context = linkedSetOf<String>()
        val includedGroups = hashSetOf<String>()
        val unsupported = arrayListOf<String>()
        for (c in page.rawCandidates) {
            checkpoint()
            val id = c.provenance.id
            val q = c.provenance.pageQuad
            val bounds = ContextRect(q.minOf { it.x }, q.minOf { it.y }, q.maxOf { it.x }, q.maxOf { it.y })
            if (!bounds.valid() || !hasArea(q)) { unsupported += id; continue }
            val intersection = ContextGeometry.intersect(bounds, clipped) ?: continue
            selected += id
            val group = checkNotNull(groups[id]) // Guaranteed by the validated immutable association.
            if (includedGroups.add(group.id)) {
                for (member in group.memberIds) { checkpoint(); context += member }
            }
            val mirror = ContextGeometry.map(intersection, clipped, t) ?: continue
            visible += FullPageRegionController.Projection(id, group.id, bounds, intersection, mirror, !axisAligned(q, bounds))
        }
        checkpoint()
        return FullPageRegionController.Frame(metadata, page, clipped, t, frozen(selected), frozen(visible),
            frozen(context), frozen(unsupported))
    }
}
