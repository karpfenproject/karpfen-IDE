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
package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.model.find.FindQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The hover policy on virtual time. The tooltip appears after the pointer rested for [CodeEditorState.HOVER_DELAY]. It
 * shows the diagnostics at the position, or else the language hover. It stays while the pointer is on its range or on
 * the tooltip. It hides [HoverController.HIDE_GRACE] after the pointer left both, and at once when the user types.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HoverPolicyTest {

    /** A small state machine with the states `idle` and `busy`. */
    private val text = """
        STATEMACHINE ATTACHED TO "Robot" {
            STATES {
                INITIAL STATE "idle" { }
                STATE "busy" { }
            }
        }
    """.trimIndent()

    /** An IDE error diagnostic on [range]. */
    private fun error(range: TextRange) =
        Diagnostic(range, Severity.ERROR, "broken", DiagnosticCodes.INTERNAL_ERROR, DiagnosticSource.IDE)

    /** Rests the pointer at [offset], or off the text when it is null, and runs what is due now. */
    private fun TestScope.rest(harness: EditorStateHarness, offset: Int?) {
        harness.state.pointerHover(offset)
        runCurrent()
    }

    /** Advances virtual time by the hover delay and runs what is due. */
    private fun TestScope.waitHoverDelay() {
        advanceTimeBy(CodeEditorState.HOVER_DELAY)
        runCurrent()
    }

    /** Advances virtual time by the hide grace and runs what is due. */
    private fun TestScope.waitHideGrace() {
        advanceTimeBy(HoverController.HIDE_GRACE)
        runCurrent()
    }

    /** Advances virtual time by [duration] and runs what is due. */
    private fun TestScope.wait(duration: Duration) {
        advanceTimeBy(duration)
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
        assertNotNull(harness.state.overlays.hover.value, "not at once: the pointer may be on its way to the tooltip")
        waitHideGrace()
        assertNull(harness.state.overlays.hover.value, "moving elsewhere hides it after the grace period")
        wait(CodeEditorState.HOVER_DELAY - HoverController.HIDE_GRACE)
        assertNotNull(harness.state.overlays.hover.value, "and restarts the delay")

        rest(harness, null)
        waitHideGrace()
        assertNull(harness.state.overlays.hover.value, "leaving the text hides it")
        rest(harness, at)
        waitHoverDelay()
        harness.state.type("x")
        assertNull(harness.state.overlays.hover.value, "typing hides it")
    }

    @Test
    fun `the tooltip stays while the pointer is on it and hides after the pointer left it`() = editorTest(
        text,
        services = FakeLanguageServices(hovers = { _, offset -> Hover(TextRange(offset, offset + 2), "h") }),
    ) { harness ->
        advanceUntilIdle()
        val at = harness.offsetOf("idle")
        rest(harness, at)
        waitHoverDelay()
        assertNotNull(harness.state.overlays.hover.value)

        rest(harness, null)
        harness.state.pointerOverTooltip(true)
        wait(CodeEditorState.HOVER_DELAY * 3)
        assertNotNull(harness.state.overlays.hover.value, "the way from the text onto the tooltip keeps it")

        harness.state.pointerOverTooltip(false)
        rest(harness, at + 1)
        wait(CodeEditorState.HOVER_DELAY * 3)
        assertNotNull(harness.state.overlays.hover.value, "back on the hovered range in time")

        rest(harness, null)
        waitHideGrace()
        assertNull(harness.state.overlays.hover.value, "hidden once the pointer left the range and the tooltip")
    }

    @Test
    fun `entering the tooltip before the text reports the pointer leaving keeps it too`() = editorTest(
        text,
        services = FakeLanguageServices(hovers = { _, offset -> Hover(TextRange(offset, offset + 2), "h") }),
    ) { harness ->
        advanceUntilIdle()
        rest(harness, harness.offsetOf("idle"))
        waitHoverDelay()

        harness.state.pointerOverTooltip(true)
        rest(harness, null)
        wait(CodeEditorState.HOVER_DELAY * 3)
        assertNotNull(harness.state.overlays.hover.value)

        harness.state.pointerOverTooltip(false)
        waitHideGrace()
        assertNull(harness.state.overlays.hover.value, "leaving the tooltip hides it after the grace period")
        harness.state.pointerOverTooltip(true)
        harness.state.type("x")
        assertNull(harness.state.overlays.hover.value, "typing hides it at once")
    }

    @Test
    fun `a change of the text not made by typing hides the tooltip of the older text`() = editorTest(text) { harness ->
        val range = harness.rangeOf("idle")
        harness.session.setExternalDiagnostics("test", harness.text.snapshot.value, listOf(error(range)))
        advanceUntilIdle()
        val changes: List<Pair<String, () -> Unit>> = listOf(
            "an external reload" to { harness.text.replaceAll("// reloaded from disk\n" + harness.plain, EditOrigin.External) },
            "a menu command" to {
                harness.state.caret.moveTo(0)
                harness.state.execute(EditorActionIds.TOGGLE_COMMENT)
            },
            "Replace All in the find bar" to {
                harness.state.find.setQuery(FindQuery("idle"))
                harness.state.find.replaceAll("ready")
            },
        )

        for ((change, run) in changes) {
            val idle = harness.plain.indexOf("idle").takeIf { it >= 0 } ?: harness.offsetOf("ready")
            harness.session.setExternalDiagnostics("test", harness.text.snapshot.value, listOf(error(TextRange(idle, idle + 4))))
            advanceUntilIdle()
            rest(harness, idle + 1)
            waitHoverDelay()
            assertNotNull(harness.state.overlays.hover.value, "shown before $change")

            run()
            advanceUntilIdle()

            assertNull(harness.state.overlays.hover.value, "$change hides the tooltip: its range and diagnostics belong to the older text")
            rest(harness, null)
        }
    }

    @Test
    fun `a lookup started before a change of the text shows nothing`() = editorTest(
        text,
        services = FakeLanguageServices(hovers = { _, offset -> Hover(TextRange(offset, offset + 1), "at $offset") }),
    ) { harness ->
        advanceUntilIdle()
        rest(harness, harness.offsetOf("busy"))
        advanceTimeBy(CodeEditorState.HOVER_DELAY / 2)
        harness.text.replaceAll("// reloaded from disk\n" + harness.plain, EditOrigin.External)
        runCurrent()
        waitHoverDelay()
        advanceUntilIdle()

        assertNull(harness.state.overlays.hover.value, "the pointer has to rest again over the new text")
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
