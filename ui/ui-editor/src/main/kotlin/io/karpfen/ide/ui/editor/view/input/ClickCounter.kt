package io.karpfen.ide.ui.editor.view.input

import kotlin.math.abs

/**
 * Counts consecutive clicks (1, 2, 3, …) from press times and positions: a press within [timeoutMillis] of the previous
 * one and within [slop] pixels of it continues the series, anything else starts a new one. Counted from the pointer
 * events themselves (their uptime), so synthesized test events and every platform behave alike. Used from the UI
 * thread.
 */
internal class ClickCounter(private val timeoutMillis: Long, private val slop: Float) {

    private var lastTime = Long.MIN_VALUE
    private var lastX = 0f
    private var lastY = 0f
    private var count = 0

    /** Registers a press at [timeMillis] at ([x], [y]); returns its click count. */
    fun press(timeMillis: Long, x: Float, y: Float): Int {
        val continues = lastTime != Long.MIN_VALUE && timeMillis - lastTime <= timeoutMillis &&
            abs(x - lastX) <= slop && abs(y - lastY) <= slop
        count = if (continues) count + 1 else 1
        lastTime = timeMillis
        lastX = x
        lastY = y
        return count
    }
}
