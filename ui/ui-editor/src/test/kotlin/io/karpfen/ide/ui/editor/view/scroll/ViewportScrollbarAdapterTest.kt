package io.karpfen.ide.ui.editor.view.scroll

import androidx.compose.runtime.mutableStateOf
import io.karpfen.ide.ui.editor.state.EditorViewport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The scrollbar adapters read one axis of the viewport and scroll it. */
class ViewportScrollbarAdapterTest {

    private val viewport = EditorViewport().apply { updateGeometry(300f, 200f, 20f, 1000f, 2000f) }
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
