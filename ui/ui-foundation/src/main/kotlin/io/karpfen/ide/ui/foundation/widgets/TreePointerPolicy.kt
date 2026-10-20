package io.karpfen.ide.ui.foundation.widgets

/**
 * The mouse policy of the rows of a [TreeView] (pure, time from the events): a primary press on an expandable node's chevron
 * toggles it; any other primary press selects the row, and the second press on the same row within the double-click
 * timeout opens it as well (selection happens on the first press, without waiting for a possible second one).
 */
internal class TreePointerPolicy(private val doubleClickTimeoutMillis: Long) {
    private var lastKey: Any? = null
    private var lastPressMillis = 0L
    private var pressCount = 0

    /**
     * The intents of a primary press at [x] px on [row] at [uptimeMillis]; [chevronStart] until [chevronEnd] is the
     * row's chevron area in px.
     */
    fun <K> primaryPress(row: TreeRow<K>, x: Float, uptimeMillis: Long, chevronStart: Float, chevronEnd: Float): List<TreeIntent<K>> {
        val key = row.node.key
        if (row.isExpandable && x >= chevronStart && x < chevronEnd) {
            pressCount = 0
            return listOf(TreeIntent.Toggle(key))
        }
        val isRepeat = pressCount > 0 && key == lastKey && uptimeMillis - lastPressMillis <= doubleClickTimeoutMillis
        pressCount = if (isRepeat) pressCount + 1 else 1
        lastKey = key
        lastPressMillis = uptimeMillis
        return if (pressCount == 2) listOf(TreeIntent.Select(key), TreeIntent.Open(key)) else listOf(TreeIntent.Select(key))
    }
}
