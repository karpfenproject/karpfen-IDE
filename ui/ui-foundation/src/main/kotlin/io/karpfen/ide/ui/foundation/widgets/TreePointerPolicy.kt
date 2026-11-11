/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.karpfen.ide.ui.foundation.widgets

/**
 * The mouse policy of the rows of a [TreeView]. It is pure and takes the time from the events. A primary press on the
 * chevron of an expandable node toggles the node. Any other primary press selects the row and counts as a click on it.
 * The second press on the same row within the double-click timeout also opens the node. A third quick press does not
 * open it again. Selection happens on the first press, without waiting for a possible second one.
 */
internal class TreePointerPolicy(private val doubleClickTimeoutMillis: Long) {
    private var lastKey: Any? = null
    private var lastPressMillis = 0L
    private var pressCount = 0

    /**
     * The intents of a primary press at [x] px on [row] at [uptimeMillis]. The row's chevron area spans [chevronStart]
     * to [chevronEnd] px, with the end exclusive.
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
        val click = listOf(TreeIntent.Select(key), TreeIntent.Click(key))
        return if (pressCount == 2) click + TreeIntent.Open(key) else click
    }
}
