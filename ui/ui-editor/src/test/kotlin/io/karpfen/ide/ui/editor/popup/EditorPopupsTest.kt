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

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.model.find.FindQuery
import io.karpfen.ide.ui.editor.state.ContextMenuRequest
import io.karpfen.ide.ui.editor.state.FindBarMode
import io.karpfen.ide.ui.editor.state.HoverContent
import org.junit.jupiter.api.TestReporter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTime

/**
 * The overlay contract of [EditorPopups] as a whole: the slots emit nothing while closed, every popup follows its state
 * (also all at once), and random interleavings of overlay changes, edits and model operations never break the popups.
 */
@OptIn(ExperimentalTestApi::class)
class EditorPopupsTest {

    private val text = "state Idle {\n    ON EVENT(\"d\", \"go\") -> Busy\n}\nstate Busy {\n    EVAL { x = 1 }\n}\n"

    /** Which popups are shown, by tag. */
    private fun ComposeUiTest.shown(): Set<String> =
        listOf(EditorPopupTags.FIND_BAR, EditorPopupTags.COMPLETION, EditorPopupTags.GO_TO_LINE, EditorPopupTags.HOVER, EditorPopupTags.CONTEXT_MENU)
            .filter { tag -> onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
            .toSet()

    /** Which popups the state says are shown (with the host's geometry deciding the anchored ones). */
    private fun expected(harness: PopupHarness): Set<String> {
        val model = harness.model
        return buildSet {
            if (model.overlays.findBar.value != null) add(EditorPopupTags.FIND_BAR)
            if (model.completion.state.value != null && harness.host.caretBounds != null) add(EditorPopupTags.COMPLETION)
            if (model.overlays.goToLine.value) add(EditorPopupTags.GO_TO_LINE)
            model.overlays.hover.value?.let { if (harness.host.boundsOf(it.range.start) != null) add(EditorPopupTags.HOVER) }
            if (model.overlays.contextMenu.value != null) add(EditorPopupTags.CONTEXT_MENU)
        }
    }

    @Test
    fun `the slots emit nothing while every popup is closed`() = popupTest(text) {
        onNodeWithTag(TEXT_AREA).onChildren().assertCountEquals(0)
        assertEquals(emptySet(), shown())
    }

    @Test
    fun `every popup follows its state, also several at once`() = popupTest(text, caretAt = 6) { harness ->
        val model = harness.model
        model.completionItems = completionItems(listOf("Idle", "Busy"))
        val idle = TextRange(6, 10)

        runOnIdle {
            model.overlays.openFind(FindBarMode.REPLACE)
            model.completion.open()
            model.overlays.showHover(HoverContent(idle, emptyList(), Hover(idle, "state Idle")))
        }
        waitForIdle()
        assertEquals(setOf(EditorPopupTags.FIND_BAR, EditorPopupTags.COMPLETION, EditorPopupTags.HOVER), shown())

        runOnIdle { model.overlays.openContextMenu(ContextMenuRequest(6, 40f, 40f)) }
        waitForIdle()
        assertEquals(setOf(EditorPopupTags.FIND_BAR, EditorPopupTags.COMPLETION, EditorPopupTags.CONTEXT_MENU), shown(), "the menu hides the hover")

        runOnIdle { model.overlays.closeAll() }
        waitForIdle()
        assertEquals(setOf(EditorPopupTags.COMPLETION), shown(), "completion is the model's, not an overlay")

        runOnIdle { model.completion.close() }
        waitForIdle()
        assertEquals(emptySet(), shown())
    }

    @Test
    fun `random interleavings of popups, edits and model operations never break the popups`(reporter: TestReporter) =
        popupTest(text) { harness ->
            val model = harness.model
            val random = Random(424242)
            model.completionItems = completionItems(listOf("Idle", "Busy", "state", "EVAL", "ON", "EVENT"))
            val steps = 150
            val elapsed = measureTime {
                repeat(steps) { step ->
                    runOnIdle { randomStep(harness, random) }
                    waitForIdle()
                    assertEquals(expected(harness), shown(), "step $step")
                }
            }
            reporter.publishEntry("random popup interleavings: $steps steps (ms)", elapsed.inWholeMilliseconds.toString())
        }

    private fun randomStep(harness: PopupHarness, random: Random) {
        val model = harness.model
        val length = model.plain.length
        fun offset() = random.nextInt(0, length + 1)
        when (random.nextInt(16)) {
            0 -> {
                val at = offset()
                val end = (at + random.nextInt(0, 4)).coerceAtMost(length)
                val insert = listOf("", "x", "state", "\n", "{", "}", "\"").random(random)
                model.text.apply(listOf(TextEdit(TextRange(at, end), insert)), EditOrigin.Typing)
            }
            1 -> model.caret.moveTo(offset())
            2 -> model.overlays.openFind(FindBarMode.entries.random(random))
            3 -> model.overlays.closeFind()
            4 -> model.find.setQuery(FindQuery(listOf("", "state", "Busy", "(", "[a-z]+", "x").random(random), random.nextBoolean(), random.nextBoolean(), random.nextBoolean()))
            5 -> if (random.nextBoolean()) model.find.next() else model.find.previous()
            6 -> model.completion.open()
            7 -> if (random.nextBoolean()) model.completion.close() else model.completion.moveSelection(random.nextInt(-3, 4))
            8 -> model.overlays.openGoToLine()
            9 -> model.overlays.closeGoToLine()
            10 -> {
                val start = offset()
                val range = TextRange(start, (start + random.nextInt(0, 6)).coerceAtMost(length))
                val diagnostics = if (random.nextBoolean()) {
                    listOf(Diagnostic(range, Severity.entries.random(random), "problem", DiagnosticCodes.SYNTAX_ERROR, DiagnosticSource.PARSER))
                } else {
                    emptyList()
                }
                model.overlays.showHover(HoverContent(range, diagnostics, Hover(range, "hover text")))
            }
            11 -> model.overlays.hideHover()
            12 -> model.overlays.openContextMenu(ContextMenuRequest(offset(), random.nextInt(0, 700).toFloat(), random.nextInt(0, 400).toFloat()))
            13 -> model.overlays.closeContextMenu()
            14 -> harness.host.caretBounds = if (random.nextInt(4) == 0) null else {
                val x = random.nextInt(0, 800).toFloat()
                val y = random.nextInt(0, 480).toFloat()
                Rect(x, y, x + 1f, y + TestGeometry.LINE)
            }
            else -> model.enabledActions.value = setOf(EditorActionIds.CUT, EditorActionIds.COPY, EditorActionIds.PASTE).filter { random.nextBoolean() }.toSet()
        }
    }
}
