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
 * Lets following yield to the user's scrolling of [listState]: after each scroll of the user, following is on exactly
 * when the newest line is in sight, and [onFollowChange] hears of every change against [isFollowing].
 *
 * The user scrolls in two ways, and both lead here: the mouse wheel and drags pass through nested scrolling, which this
 * connection observes after the list consumed them; the scrollbar scrolls through the adapter of [scrollbar], which
 * reports after each of its scrolls. The view's own scroll to the newest line takes neither way, so a line that arrives
 * while the list grows never switches following off.
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

    /** The scrollbar's view of the list through [adapter], reporting each of its scrolls like a wheel or a drag. */
    fun scrollbar(adapter: ScrollbarAdapter): ScrollbarAdapter = object : ScrollbarAdapter by adapter {
        override suspend fun scrollTo(scrollOffset: Double) {
            adapter.scrollTo(scrollOffset)
            afterUserScroll()
        }
    }

    /** Switches following to whether the newest line is in sight, after the user scrolled. */
    private fun afterUserScroll() {
        val atEnd = !listState.canScrollForward
        if (atEnd != isFollowing()) onFollowChange(atEnd)
    }
}
