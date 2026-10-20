package io.karpfen.ide.ui.editor.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.editor.overlay.EditorOverlayHost
import io.karpfen.ide.ui.editor.overlay.EditorOverlays
import io.karpfen.ide.ui.editor.state.FakeLanguageServices
import io.karpfen.ide.ui.editor.theme.lightEditorTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The overlay slots and the host `CodeEditor` gives them: the header above the text area, the popups over it, geometry
 * in text-area coordinates that follows the caret and the scroll position; and the drag auto-scroll at the edges.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorOverlayHostTest {

    private val lines = (0 until 200).joinToString("\n") { "line $it" }

    @Test
    fun `the header sits above the text area, the popups over it`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        val overlays = object : EditorOverlays {
            var host: EditorOverlayHost? = null

            @Composable
            override fun Header(host: EditorOverlayHost) {
                Box(Modifier.fillMaxWidth().height(30.dp).testTag("header"))
            }

            @Composable
            override fun Popups(host: EditorOverlayHost) = ui.overlays.Popups(host).also { this.host = host }
        }
        ui.compose("abc", slots = overlays)

        val header = onNodeWithTag("header").getUnclippedBoundsInRoot()
        val area = ui.textArea.getUnclippedBoundsInRoot()
        assertEquals(header.bottom, area.top, "the text area starts below the header")
        assertTrue(area.left > header.left, "the gutter is left of the text area")
        assertSame(ui.state, assertNotNull(overlays.host).state)
        assertEquals(lightEditorTheme(), overlays.host?.theme)
    }

    @Test
    fun `caret bounds follow the caret and vanish when it scrolls out of view`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose(lines)
        val first = assertNotNull(ui.host.caretBounds)
        assertEquals(0f, first.top)

        ui.state.caret.moveTo(ui.text.snapshot.value.lineIndex.lineStart(3) + 2)
        waitForIdle()
        val moved = assertNotNull(ui.host.caretBounds)
        assertEquals(3 * first.height, moved.top, 0.5f)
        assertTrue(moved.left > first.left)
        assertEquals(ui.host.boundsOf(ui.state.caret.primary.position)?.left ?: -1f, moved.center.x, 1f)

        ui.state.viewport.scrollTo(0f, 3000f)
        waitForIdle()
        assertNull(ui.host.caretBounds, "scrolled out of view")
        assertNull(ui.host.boundsOf(0), "line 0 is not laid out")
        val viewport = ui.state.viewport.state.value
        val visibleLine = (viewport.scrollY / viewport.lineHeight).toInt() + 2
        assertNotNull(ui.host.boundsOf(ui.text.snapshot.value.lineIndex.lineStart(visibleLine)))
        assertEquals(ui.host.state.snapshot.value, ui.text.snapshot.value)
    }

    @Test
    fun `dragging below the text area scrolls and extends the selection`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose(lines)
        val start = ui.host.boundsOf(1)!!.let { Offset(it.left + 1f, it.center.y) }
        val height = ui.state.viewport.state.value.viewportHeight

        ui.textArea.performMouseInput {
            moveTo(start)
            press()
            moveTo(Offset(start.x, height + 40f))
        }
        mainClock.advanceTimeBy(600)
        ui.textArea.performMouseInput { release() }
        waitForIdle()

        val scrolled = ui.state.viewport.state.value.scrollY
        assertTrue(scrolled > 0f, "auto-scrolled")
        val selection = ui.state.caret.primary.selection
        assertEquals(1, selection.start)
        val lastLine = ui.text.snapshot.value.lineIndex.lineOf(selection.end)
        assertTrue(lastLine * ui.state.viewport.state.value.lineHeight >= scrolled, "the selection follows the scrolled text")
    }
}
