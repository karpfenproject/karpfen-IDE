package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.text.TextRange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * The hover policy on virtual time (spec §7.2): the tooltip appears after [CodeEditorState.HOVER_DELAY] of rest, shows
 * the diagnostics at the position or else the language hover, and is hidden when the pointer moves away, leaves the
 * text, or the user types.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HoverPolicyTest {

    private val text = """
        STATEMACHINE ATTACHED TO "Robot" {
            STATES {
                INITIAL STATE "idle" { }
                STATE "busy" { }
            }
        }
    """.trimIndent()

    private fun error(range: TextRange) =
        Diagnostic(range, Severity.ERROR, "broken", DiagnosticCodes.INTERNAL_ERROR, DiagnosticSource.IDE)

    private fun TestScope.rest(harness: EditorStateHarness, offset: Int?) {
        harness.state.pointerHover(offset)
        runCurrent()
    }

    private fun TestScope.waitHoverDelay() {
        advanceTimeBy(CodeEditorState.HOVER_DELAY)
        runCurrent()
    }

    @Test
    fun `the diagnostics at the position appear after the hover delay`() = editorTest(text) { harness ->
        val range = harness.rangeOf("idle")
        harness.session.setExternalDiagnostics("test", harness.text.snapshot.value, listOf(error(range)))
        advanceUntilIdle()

        rest(harness, range.start + 1)
        advanceTimeBy(CodeEditorState.HOVER_DELAY - 1.milliseconds)
        runCurrent()
        assertNull(harness.state.overlays.hover.value, "not before the delay")
        advanceTimeBy(1.milliseconds)
        runCurrent()

        val shown = assertNotNull(harness.state.overlays.hover.value)
        assertEquals(listOf("broken"), shown.diagnostics.map { it.message })
        assertNull(shown.hover, "diagnostics win over the language hover")
        assertEquals(range, shown.range)
    }

    @Test
    fun `without diagnostics the language hover is shown, computed for a current parse`() = editorTest(
        text,
        services = FakeLanguageServices(hovers = { doc, offset -> Hover(TextRange(offset, offset + 1), "state v${doc.parse?.version}") }),
    ) { harness ->
        advanceUntilIdle()
        rest(harness, harness.offsetOf("busy"))
        waitHoverDelay()

        val shown = assertNotNull(harness.state.overlays.hover.value)
        assertEquals("state v0", shown.hover?.text)
        assertEquals(emptyList(), shown.diagnostics)
    }

    @Test
    fun `moving away, leaving the text and typing hide the tooltip`() = editorTest(
        text,
        services = FakeLanguageServices(hovers = { _, offset -> Hover(TextRange(offset, offset + 2), "h") }),
    ) { harness ->
        advanceUntilIdle()
        val at = harness.offsetOf("idle")
        rest(harness, at)
        waitHoverDelay()
        assertNotNull(harness.state.overlays.hover.value)

        rest(harness, at + 1)
        assertNotNull(harness.state.overlays.hover.value, "moving inside the shown range keeps the tooltip")
        rest(harness, harness.offsetOf("busy"))
        assertNull(harness.state.overlays.hover.value, "moving elsewhere hides it")
        waitHoverDelay()
        assertNotNull(harness.state.overlays.hover.value, "and restarts the delay")

        rest(harness, null)
        assertNull(harness.state.overlays.hover.value, "leaving the text hides it")
        rest(harness, at)
        waitHoverDelay()
        harness.state.type("x")
        assertNull(harness.state.overlays.hover.value, "typing hides it")
    }

    @Test
    fun `a pointer that moves on before the delay shows nothing for the old position`() = editorTest(
        text,
        services = FakeLanguageServices(hovers = { _, offset -> Hover(TextRange(offset, offset + 1), "at $offset") }),
    ) { harness ->
        advanceUntilIdle()
        rest(harness, harness.offsetOf("idle"))
        advanceTimeBy(CodeEditorState.HOVER_DELAY / 2)
        rest(harness, harness.offsetOf("busy"))
        advanceTimeBy(CodeEditorState.HOVER_DELAY / 2)
        runCurrent()
        assertNull(harness.state.overlays.hover.value)
        waitHoverDelay()

        assertEquals("at ${harness.offsetOf("busy")}", harness.state.overlays.hover.value?.hover?.text)
    }

    @Test
    fun `nothing is shown at a position without diagnostics and hover`() = editorTest(text) { harness ->
        advanceUntilIdle()
        rest(harness, 2)
        waitHoverDelay()
        advanceUntilIdle()

        assertNull(harness.state.overlays.hover.value)
    }
}
