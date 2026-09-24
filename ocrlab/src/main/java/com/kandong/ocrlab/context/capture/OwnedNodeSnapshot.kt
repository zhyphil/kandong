package com.kandong.ocrlab.context.capture

import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import java.io.Closeable

/** Observations from our fixed View registry, NOT discovery of another app's accessibility tree. */
internal data class OwnedNodeObservation(
    val id: Int, val className: String, val clickable: Boolean, val frameworkWindowId: Int,
    val screenBounds: CaptureRect, val viewScreenBounds: CaptureRect,
    val node: CaptureNode,
)

internal class OwnedNodeSnapshot private constructor(
    val metadata: CaptureMetadata,
    val observations: List<OwnedNodeObservation>,
    private val infos: MutableMap<Int, AccessibilityNodeInfo>,
) : Closeable {
    val reads = mutableListOf<Int>()
    var closed = false; private set
    val obtainedCount = infos.size
    var releasedCount = 0; private set

    fun readLabel(id: Int): CaptureLabel {
        check(!closed && Looper.myLooper() == Looper.getMainLooper())
        val info = infos.getValue(id)
        reads += id
        // This bounds our String copies. Android already populated the NodeInfo (including labels).
        fun bounded(value: CharSequence?): String? {
            require(value == null || value.length <= LocalCapturePrivacy.MAX_LABEL_CHARS)
            return value?.toString()
        }
        return CaptureLabel(bounded(info.text), bounded(info.contentDescription))
    }

    override fun close() {
        if (closed) return
        for (info in infos.values) { @Suppress("DEPRECATION") info.recycle(); releasedCount++ }
        infos.clear(); closed = true
    }

    companion object {
        /** Never call a new API on old Android, and never represent its absence as false. */
        internal fun sensitiveEvidence(api: Int, read: () -> Boolean): CaptureEvidence =
            if (api < 34) CaptureEvidence.UNAVAILABLE else evidence(read())

        private fun evidence(value: Boolean) = if (value) CaptureEvidence.YES else CaptureEvidence.NO
        private fun Rect.capture() = CaptureRect(left, top, right, bottom)
        internal fun screenRect(view: View): Rect {
            val location = IntArray(2); view.getLocationOnScreen(location)
            return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }

        fun obtain(activity: OwnedNodeLabActivity, version: CaptureVersion): OwnedNodeSnapshot {
            check(Looper.myLooper() == Looper.getMainLooper())
            val decor = activity.window.decorView
            check(decor.isAttachedToWindow && decor.width > 0 && decor.height > 0)
            val origin = screenRect(decor)
            val infos = linkedMapOf<Int, AccessibilityNodeInfo>()
            val observations = ArrayList<OwnedNodeObservation>()
            try {
                for ((id, view) in activity.fixtureViews) {
                    check(view.rootView === decor && view.context === activity)
                    val info = checkNotNull(view.createAccessibilityNodeInfo())
                    infos[id] = info
                    val bounds = Rect().also(info::getBoundsInScreen)
                    val original = screenRect(view)
                    // GlobalVisibleRect uses root coordinates. Normalize explicitly through screen coordinates.
                    val visible = Rect()
                    val hasVisible = view.getGlobalVisibleRect(visible)
                    visible.offset(origin.left, origin.top)
                    val intersection = if (hasVisible && visible.intersect(original) && visible.intersect(origin)) visible else null
                    fun normalized(rect: Rect) = Rect(rect).apply { offset(-origin.left, -origin.top) }.capture()
                    val role = when (view) {
                        is Button -> CaptureRole.BUTTON
                        is TextView -> CaptureRole.TEXT
                        is ImageView -> CaptureRole.IMAGE
                        is ViewGroup -> CaptureRole.CONTAINER
                        else -> CaptureRole.UNKNOWN
                    }
                    val sensitivity = if (Build.VERSION.SDK_INT >= 34) {
                        sensitiveEvidence(Build.VERSION.SDK_INT) { info.isAccessibilityDataSensitive }
                    } else CaptureEvidence.UNAVAILABLE
                    val layout = (view as? TextView)?.layout
                    val ellipsized = layout?.let { textLayout ->
                        (0 until textLayout.lineCount).any { textLayout.getEllipsisCount(it) > 0 }
                    }
                    val clipping = when {
                        view is TextView && layout == null -> CaptureEvidence.UNKNOWN
                        else -> evidence(ellipsized == true)
                    }
                    val covered = activity.cover.visibility == View.VISIBLE && Rect.intersects(original, screenRect(activity.cover))
                    val node = CaptureNode(
                        id, if (id == 10) 9 else null, version.window, id,
                        normalized(original), intersection?.let(::normalized), role,
                        evidence(info.isVisibleToUser), evidence(info.isPassword), sensitivity, evidence(info.isEditable),
                        // Only our known, same-window covering panel is evaluated. No general occlusion claim.
                        if (bounds != original) CaptureEvidence.UNKNOWN else evidence(covered),
                        clipping, CaptureEvidence.YES,
                        if (view is ViewGroup) CaptureLabelScope.DESCENDANTS else CaptureLabelScope.SELF_ONLY,
                    )
                    observations += OwnedNodeObservation(id, info.className?.toString().orEmpty(), info.isClickable,
                        info.windowId, bounds.capture(), original.capture(), node)
                }
                // Registry/parent order and the single public fixture window are declared test policy.
                // They do NOT establish global Android window completeness or third-party privacy.
                val window = CaptureWindow(version.window, CaptureWindowOwner.TARGET_APP,
                    evidence(activity.hasWindowFocus()), CaptureEvidence.NO,
                    evidence(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0))
                val metadata = CaptureMetadata(version, decor.width, decor.height, SystemClock.elapsedRealtime(), 15_000,
                    CaptureEvidence.YES, CaptureEvidence.NO, listOf(window), observations.map { it.node },
                    CaptureOrigin.OWNED_ANDROID_FIXTURE)
                return OwnedNodeSnapshot(metadata, observations.toList(), infos)
            } catch (failure: RuntimeException) {
                for (info in infos.values) { @Suppress("DEPRECATION") info.recycle() }
                infos.clear()
                throw failure
            }
        }
    }
}
