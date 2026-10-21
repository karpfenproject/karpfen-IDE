package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.model.find.FindQuery
import io.karpfen.ide.ui.editor.model.navigation.GoToLineQuery
import io.karpfen.ide.ui.foundation.keys.KeyCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The caret reveal (spec §7.2, decision 30 of the work-package plan): every executed action and typed text that changes
 * the carets or the text leaves a pending scroll request for the primary caret — minimal near the viewport, centred for
 * jumps — which the view applies with a 3-line margin; pending requests stay until consumed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaretRevealTest {

    private val lines = (0 until 100).joinToString("\n") { "line $it" + if (it == 50 || it == 80) " target" else "" }

    private fun EditorStateHarness.lineStart(line: Int): Int = text.snapshot.value.lineIndex.lineStart(line)

    private fun EditorStateHarness.caretLine(): Int = text.snapshot.value.lineIndex.lineOf(state.caret.primary.position)

    private fun EditorStateHarness.isCaretVisible(): Boolean {
        val viewport = state.viewport.state.value
        val top = caretLine() * viewport.lineHeight
        return top >= viewport.scrollY && top + viewport.lineHeight <= viewport.scrollY + viewport.viewportHeight
    }

    @Test
    fun `an arrow key at the bottom edge issues a minimal reveal`() = editorTest(lines) { harness ->
        harness.layOut(visibleLines = 10)
        harness.state.caret.moveTo(harness.lineStart(9))

        assertTrue(harness.press(KeyCode.DOWN))

        val request = assertNotNull(harness.state.scrollRequest.value)
        assertEquals(harness.lineStart(10), request.offset)
        assertFalse(request.center)
        harness.applyScrollRequest()
        assertEquals((10 + 1 + EditorViewport.CARET_MARGIN_LINES) * 20f - 200f, harness.state.viewport.state.value.scrollY)
        assertTrue(harness.isCaretVisible())
        assertNull(harness.state.scrollRequest.value, "consumed")
    }

    @Test
    fun `the caret stays visible in a viewport shorter than the reveal margin`() = editorTest(lines) { harness ->
        harness.layOut(visibleLines = 3)

        repeat(10) {
            harness.press(KeyCode.DOWN)
            harness.applyScrollRequest()
            assertTrue(harness.isCaretVisible(), "caret line ${harness.caretLine()} visible after Down ${it + 1}")
        }
        assertEquals(10, harness.caretLine())
    }

    @Test
    fun `Page Down at the viewport edge issues a reveal that keeps the caret visible`() = editorTest(lines) { harness ->
        harness.layOut(visibleLines = 10)
        harness.state.caret.moveTo(harness.lineStart(9))

        assertTrue(harness.press(KeyCode.PAGE_DOWN))

        val request = assertNotNull(harness.applyScrollRequest())
        assertFalse(request.center, "a page is no jump")
        assertTrue(harness.isCaretVisible())
    }

    @Test
    fun `a jump far off-screen is centred`() = editorTest(lines) { harness ->
        harness.layOut(visibleLines = 10)

        assertTrue(harness.state.execute(EditorActionIds.CARET_DOCUMENT_END))

        assertTrue(assertNotNull(harness.applyScrollRequest()).center)
        assertTrue(harness.isCaretVisible())
    }

    @Test
    fun `find next to an off-screen match reveals it, from the action and from the find model`() = editorTest(lines) { harness ->
        harness.layOut(visibleLines = 10)
        harness.state.find.setQuery(FindQuery("target"))
        advanceUntilIdle()
        assertEquals(50, harness.caretLine(), "setQuery selects the first match at or after the caret")
        assertTrue(assertNotNull(harness.applyScrollRequest()).center)

        assertTrue(harness.state.execute(EditorActionIds.FIND_NEXT))
        assertEquals(80, harness.caretLine())
        assertNotNull(harness.applyScrollRequest())
        assertTrue(harness.isCaretVisible())

        harness.state.find.next()
        assertEquals(50, harness.caretLine(), "wraps around")
        assertNotNull(harness.applyScrollRequest())
        assertTrue(harness.isCaretVisible())
    }

    @Test
    fun `typing reveals the caret, an action without effect does not`() = editorTest(lines) { harness ->
        harness.layOut(visibleLines = 10)
        harness.state.viewport.scrollTo(0f, 1000f)

        harness.state.type("x")
        assertNotNull(harness.applyScrollRequest())
        assertTrue(harness.isCaretVisible())

        harness.state.caret.moveTo(0)
        harness.state.execute(EditorActionIds.CARET_LEFT)
        assertNull(harness.state.scrollRequest.value, "the caret did not move")
    }

    @Test
    fun `an explicit completion request reveals the caret it completes at`() = editorTest(lines) { harness ->
        harness.layOut(visibleLines = 10)
        harness.state.viewport.scrollTo(0f, 1000f)
        assertFalse(harness.isCaretVisible())

        harness.state.execute(EditorActionIds.COMPLETION)

        assertNotNull(harness.applyScrollRequest(), "the request acts at the caret, so it shows the caret first")
        assertTrue(harness.isCaretVisible())
    }

    @Test
    fun `scrollTo sets a pending request that a newer one replaces and that is consumed only by itself`() = editorTest(lines) { harness ->
        harness.state.scrollTo(30, center = true)
        val first = assertNotNull(harness.state.scrollRequest.value)
        harness.state.scrollTo(40, center = false)
        val second = assertNotNull(harness.state.scrollRequest.value)

        harness.state.consumeScrollRequest(first)
        assertEquals(second, harness.state.scrollRequest.value, "an older request does not clear a newer one")
        harness.state.consumeScrollRequest(second)
        assertNull(harness.state.scrollRequest.value)
        harness.state.scrollTo(Int.MAX_VALUE)
        assertEquals(harness.plain.length, harness.state.scrollRequest.value?.offset, "clamped to the text")
    }

    @Test
    fun `focus requests stay pending until consumed`() = editorTest(lines) { harness ->
        assertFalse(harness.state.focusRequested.value)
        harness.state.requestFocus()
        assertTrue(harness.state.focusRequested.value)
        harness.state.consumeFocusRequest()
        assertFalse(harness.state.focusRequested.value)
    }

    @Test
    fun `go to line moves the caret, centres it, closes the popup and requests focus`() = editorTest(lines) { harness ->
        harness.state.overlays.openGoToLine()

        harness.state.goToLine(GoToLineQuery(61, 3))

        assertEquals(harness.lineStart(60) + 2, harness.state.caret.primary.position)
        assertEquals(ScrollRequest(harness.lineStart(60) + 2, center = true), harness.state.scrollRequest.value)
        assertFalse(harness.state.overlays.goToLine.value)
        assertTrue(harness.state.focusRequested.value)
        harness.state.goToLine(GoToLineQuery(1000, 99))
        assertEquals(harness.plain.length, harness.state.caret.primary.position, "clamped")
    }
}
