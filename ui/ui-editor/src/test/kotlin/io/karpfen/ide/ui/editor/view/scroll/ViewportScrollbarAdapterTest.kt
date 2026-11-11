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
package io.karpfen.ide.ui.editor.view.scroll

import androidx.compose.runtime.mutableStateOf
import io.karpfen.ide.ui.editor.state.EditorViewport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The scrollbar adapters read one axis of the viewport and scroll it. */
class ViewportScrollbarAdapterTest {

    /** A viewport 300 px wide and 200 px high with lines of 20 px, over content 1000 px wide and 2000 px high. */
    private val viewport = EditorViewport().apply { updateGeometry(300f, 200f, 20f, 1000f, 2000f) }

    /** The viewport state that the adapters read. Tests copy the current state into it after scrolling. */
    private val snapshot = mutableStateOf(viewport.state.value)

    @Test
    fun `the vertical adapter reads and scrolls the y axis`() = runTest {
        val adapter = ViewportScrollbarAdapter(snapshot, viewport, ScrollAxis.VERTICAL)
        viewport.scrollTo(30f, 0f)

        adapter.scrollTo(500.0)
        snapshot.value = viewport.state.value

        assertEquals(500.0, adapter.scrollOffset)
        assertEquals(2000.0, adapter.contentSize)
        assertEquals(200.0, adapter.viewportSize)
        assertEquals(30f, viewport.state.value.scrollX, "the other axis stays")
    }

    @Test
    fun `the horizontal adapter reads and scrolls the x axis, clamped by the viewport`() = runTest {
        val adapter = ViewportScrollbarAdapter(snapshot, viewport, ScrollAxis.HORIZONTAL)

        adapter.scrollTo(5000.0)
        snapshot.value = viewport.state.value

        assertEquals(700.0, adapter.scrollOffset)
        assertEquals(1000.0, adapter.contentSize)
        assertEquals(300.0, adapter.viewportSize)
    }
}
