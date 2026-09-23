package com.kandong.app.capture

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.kandong.app.domain.Box
import com.kandong.app.domain.NodeFlags
import com.kandong.app.domain.NodeReader

/** Lives only on the capture call stack. API 33+ nodes no longer use the recycle pool. */
internal class AndroidNodeReader(private val node: AccessibilityNodeInfo) : NodeReader {
    override val flags get() = NodeFlags(node.isPassword, node.isEditable,
        Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)
    override val childCount get() = node.childCount
    override fun child(index: Int): NodeReader? = node.getChild(index)?.let(::AndroidNodeReader)
    override val visible get() = node.isVisibleToUser
    override val enabled get() = node.isEnabled
    override val clickable get() = node.isClickable
    override val bounds: Box get() = Rect().also(node::getBoundsInScreen).toBox()
    override val windowId get() = node.windowId
    override val packageName get() = node.packageName?.toString().orEmpty()
    override val className get() = node.className?.toString().orEmpty()
    override val viewId get() = node.viewIdResourceName
    override fun text(): CharSequence? = node.text
    override fun description(): CharSequence? = node.contentDescription
}

internal fun Rect.toBox() = Box(left, top, right, bottom)
