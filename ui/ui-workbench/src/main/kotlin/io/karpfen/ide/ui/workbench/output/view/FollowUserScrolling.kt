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
package io.karpfen.ide.ui.workbench.output.view

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/**
 * Lets following yield to the user's scrolling of [listState]. After each scroll by the user, following is on exactly
 * when the newest line is in sight. [onFollowChange] hears of every change compared to [isFollowing].
 *
 * The user scrolls in two ways, and both lead here. The mouse wheel and drags pass through nested scrolling, which
 * this connection observes after the list consumed them. The scrollbar scrolls through the adapter from [scrollbar],
 * which reports after each of its scrolls. The view's own scroll to the newest line takes neither path. A line that
 * arrives while the list grows therefore never switches following off.
 */
internal class FollowUserScrolling(
    private val listState: LazyListState,
    private val isFollowing: () -> Boolean,
    private val onFollowChange: (Boolean) -> Unit,
) : NestedScrollConnection {

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        afterUserScroll()
        return Offset.Zero
    }

    /**
     * Wraps the scrollbar [adapter] of the list so that each of its scrolls is reported like a wheel scroll or a drag.
     */
    fun scrollbar(adapter: ScrollbarAdapter): ScrollbarAdapter = object : ScrollbarAdapter by adapter {
        override suspend fun scrollTo(scrollOffset: Double) {
            adapter.scrollTo(scrollOffset)
            afterUserScroll()
        }
    }

    /** Switches following to whether the newest line is in sight, after the user scrolled. */
    private fun afterUserScroll() {
        val inSight = isNewestLineInSight()
        if (inSight != isFollowing()) onFollowChange(inSight)
    }

    /**
     * True if some part of the newest line lies inside the list's viewport. This is not the same as the end of the list
     * being in sight. Following shows the newest line from its start. A line taller than the viewport, such as an error
     * with its stack trace, keeps its end out of sight while the user scrolls down through it.
     */
    private fun isNewestLineInSight(): Boolean {
        val layout = listState.layoutInfo
        val newest = layout.visibleItemsInfo.lastOrNull { it.index == layout.totalItemsCount - 1 } ?: return false
        return newest.offset < layout.viewportEndOffset && newest.offset + newest.size > layout.viewportStartOffset
    }
}
