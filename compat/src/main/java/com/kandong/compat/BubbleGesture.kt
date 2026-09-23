package com.kandong.compat

import kotlin.math.roundToInt

/** Safe area is in screen pixels and already includes all four system insets. */
internal class BubbleGesture(private val safe: Box, private val size: Int, private val margin: Int, private val slop: Float) {
    private var pointer = -1
    private var downX = 0f; private var downY = 0f
    private var origin = Box(0, 0, size, size)
    var position = clamp(origin); private set
    var dragged = false; private set
    private var invalid = true
    private var longPressed = false
    fun clamp(box: Box): Box = Box(box.left.coerceIn(safe.left + margin, maxOf(safe.left + margin, safe.right - size - margin)),
        box.top.coerceIn(safe.top + margin, maxOf(safe.top + margin, safe.bottom - size - margin)), size, size)
    fun place(box: Box) { position = clamp(box) }
    fun dock(): Box {
        position = clamp(position.copy(left = if (position.left + size / 2 < safe.left + safe.width / 2)
            safe.left + margin else safe.right - size - margin))
        return position
    }
    fun down(id: Int, count: Int, x: Float, y: Float) {
        pointer = id; downX = x; downY = y; origin = position
        dragged = false; longPressed = false; invalid = count != 1 || !x.isFinite() || !y.isFinite()
    }
    fun move(id: Int, count: Int, x: Float, y: Float): Box {
        if (invalid) return position
        if (id != pointer || count != 1 || !x.isFinite() || !y.isFinite()) { cancel(); return position }
        val dx = x - downX; val dy = y - downY
        if (dx * dx + dy * dy > slop * slop) dragged = true
        if (dragged && !longPressed) position = clamp(origin.moved(dx.roundToInt(), dy.roundToInt()))
        return position
    }
    fun longPress(): Boolean {
        if (invalid || dragged || longPressed) return false
        longPressed = true; return true
    }
    fun up(id: Int, count: Int, x: Float, y: Float): Boolean {
        move(id, count, x, y)
        val tap = !invalid && !dragged && !longPressed
        cancel(); dock(); return tap
    }
    fun cancel() { invalid = true; pointer = -1 }
}
