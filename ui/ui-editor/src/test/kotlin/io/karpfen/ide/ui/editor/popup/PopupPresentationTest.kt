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

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.completion.CompletionUiState
import io.karpfen.ide.ui.foundation.icons.KarpfenIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure presentation rules of the popups: kind badges, label emphasis, list measures, row reveal, hints, icons and focus. */
class PopupPresentationTest {

    /** The emphasis of matched label characters. */
    private val bold = SpanStyle(fontWeight = FontWeight.Bold)

    /** A keyword item for [label] with an optional [detail]. */
    private fun item(label: String, detail: String? = null) =
        CompletionItem(label, CompletionKind.KEYWORD, label, TextRange(0, 0), null, detail, null, label)

    @Test
    fun `every completion kind has its own badge`() {
        val badges = CompletionKind.entries.map(KindBadge::of)

        assertEquals(CompletionKind.entries.size, badges.map { it.description }.toSet().size, "descriptions are distinct")
        assertEquals(CompletionKind.entries.size, badges.map { it.text }.toSet().size, "texts are distinct")
        assertTrue(badges.all { it.text.length in 1..2 })
        assertEquals(KindBadge("kw", "keyword", io.karpfen.ide.core.lang.HighlightStyle.KEYWORD), KindBadge.of(CompletionKind.KEYWORD))
    }

    @Test
    fun `a label is emphasized on its prefix match, case-insensitively`() {
        val label = CompletionText.label("printLine", "PRI", bold)

        assertEquals("printLine", label.text)
        assertEquals(listOf(0 until 3), label.spanStyles.map { it.start until it.end })
        assertEquals(bold, label.spanStyles.single().item)
    }

    @Test
    fun `labels matching otherwise, or with an empty filter, are not emphasized`() {
        assertTrue(CompletionText.label("simpleProperty", "sP", bold).spanStyles.isEmpty(), "word-boundary match")
        assertTrue(CompletionText.label("print", "", bold).spanStyles.isEmpty())
        assertTrue(CompletionText.label("pr", "print", bold).spanStyles.isEmpty(), "filter longer than the label")
    }

    @Test
    fun `the list is at most 12 rows high and as wide as its longest row within limits`() {
        val metrics = CompletionListMetrics(rowHeight = 20, charWidth = 8f, minWidth = 200, maxWidth = 600, rowChrome = 40)

        assertEquals(0, metrics.height(0))
        assertEquals(60, metrics.height(3))
        assertEquals(240, metrics.height(12))
        assertEquals(240, metrics.height(500))
        assertEquals(20 * CompletionUiState.MAX_VISIBLE_ROWS, metrics.height(500), "as many rows as Page Up/Down move")
        assertEquals(200, metrics.width(listOf(item("a"))), "minimum width")
        assertEquals(40 + (20 + 3 + 10) * 8, metrics.width(listOf(item("a"), item("x".repeat(20), detail = "y".repeat(10)))))
        assertEquals(600, metrics.width(listOf(item("x".repeat(500)))), "maximum width")
        assertEquals(200, metrics.width(emptyList()))
    }

    @Test
    fun `revealing a row scrolls by as few rows as possible`() {
        assertNull(RowReveal.firstRow(5, 3..14), "visible")
        assertEquals(2, RowReveal.firstRow(2, 3..14), "above: becomes the first row")
        assertEquals(9, RowReveal.firstRow(20, 3..14), "below: becomes the last row")
        assertEquals(7, RowReveal.firstRow(7, IntRange.EMPTY), "nothing known: to the top")
        assertNull(RowReveal.firstRow(0, 0..0))
    }

    @Test
    fun `the go-to-line hint shows the caret's line and the line count`() {
        val snapshot = TextSnapshot("a\nb\nc", 0)

        assertEquals("Current line 2 of 3", GoToLineHint.text(GoToLineHint.currentLine(snapshot, 2), snapshot.lineCount))
        assertEquals(3, GoToLineHint.currentLine(snapshot, 99), "a lagging caret is clamped into the text")
        assertEquals(1, GoToLineHint.currentLine(snapshot, -1))
    }

    @Test
    fun `diagnostics use the shared severity icons`() {
        assertEquals(SeverityIcon(KarpfenIcons.Error, "Error"), SeverityIcon.of(Severity.ERROR))
        assertEquals(SeverityIcon(KarpfenIcons.Warning, "Warning"), SeverityIcon.of(Severity.WARNING))
        assertEquals(KarpfenIcons.Info, SeverityIcon.of(Severity.INFO).image)
        assertEquals(KarpfenIcons.Info, SeverityIcon.of(Severity.HINT).image)
    }

    @Test
    fun `focus is lost only after it was held`() {
        val tracker = FocusLossTracker()

        assertEquals(false, tracker.update(false), "never focused")
        assertEquals(false, tracker.update(true))
        assertEquals(false, tracker.update(true))
        assertEquals(true, tracker.update(false))
        assertEquals(false, tracker.update(false), "reported once")
    }
}
