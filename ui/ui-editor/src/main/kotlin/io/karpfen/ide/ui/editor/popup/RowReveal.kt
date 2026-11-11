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
package io.karpfen.ide.ui.editor.popup

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/**
 * Keeps a highlighted row of a list visible. The object holds pure functions. It computes the smallest scroll, in rows,
 * that makes a row fully visible. A row above the visible ones becomes the first row, and a row below them becomes the
 * last one.
 */
internal object RowReveal {

    /**
     * Returns the row to scroll to the top so that row [index] is fully visible. [visible] holds the rows that are fully
     * visible now, and it is empty when they are not known. Returns null if [index] is visible already.
     */
    fun firstRow(index: Int, visible: IntRange): Int? = when {
        visible.isEmpty() -> index
        index < visible.first -> index
        index > visible.last -> index - (visible.last - visible.first)
        else -> null
    }

    /** Returns the indices of the items of [info] that are fully inside the viewport. The range is empty if there are none. */
    fun fullyVisible(info: LazyListLayoutInfo): IntRange {
        val rows = info.visibleItemsInfo.filter { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }
        return if (rows.isEmpty()) IntRange.EMPTY else rows.first().index..rows.last().index
    }
}

/** Scrolls this list just enough to show item [index] fully, using [RowReveal]. It waits for the first layout of the list. */
internal suspend fun LazyListState.reveal(index: Int) {
    val info = snapshotFlow { layoutInfo }.first { it.viewportSize.height > 0 }
    RowReveal.firstRow(index, RowReveal.fullyVisible(info))?.let { scrollToItem(it) }
}
