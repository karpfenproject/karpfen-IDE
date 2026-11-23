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
package io.karpfen.ide.ui.editor.model.completion

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The preview of a proposal: which rows it shows, which of their columns are real text and which are light, and which
 * offset a place on a row stands for. Every preview comes from the acceptance of the item, so the tests build both.
 */
class CompletionPreviewTest {

    /** The preview of inserting [insert] over [range] of [text]. */
    private fun preview(text: String, range: TextRange, insert: String): CompletionPreview? {
        val item = CompletionItem(insert, CompletionKind.KEYWORD, insert, range, null, null, null, insert)
        val snapshot = TextSnapshot(text, 7)
        return CompletionPreview.of(CompletionAcceptance.of(item, range, snapshot), snapshot)
    }

    /** The text that accepting [insert] over [range] of [text] gives. */
    private fun accepted(text: String, range: TextRange, insert: String): String {
        val item = CompletionItem(insert, CompletionKind.KEYWORD, insert, range, null, null, null, insert)
        val edit = CompletionAcceptance.of(item, range, TextSnapshot(text, 7)).edit
        return text.replaceRange(edit.range.start, edit.range.end, edit.newText)
    }

    /** The rows of [preview] put in place of its line in [text]: what the text area shows. */
    private fun shown(text: String, preview: CompletionPreview): String {
        val lines = text.split('\n')
        return (lines.take(preview.line) + preview.rows + lines.drop(preview.line + 1)).joinToString("\n")
    }

    @Test
    fun `a proposal that continues the typed text keeps it as real text and shows the rest light`() {
        val text = "x fo"
        val preview = assertNotNull(preview(text, TextRange(2, 4), "foobar"))

        assertEquals(listOf("x foobar"), preview.rows)
        assertEquals(7, preview.version)
        assertEquals(0, preview.line)
        assertEquals(0, preview.addedLines)
        assertEquals(4, preview.realColumns, "x, the space and fo are real")
        assertEquals(TextRange(4, 8), preview.ghost(0))
        assertEquals(listOf(CompletionPreview.RealRun(0, 0, 4)), preview.realRuns(0))
        assertEquals(accepted(text, TextRange(2, 4), "foobar"), shown(text, preview))
    }

    @Test
    fun `a proposal in another case hides the typed text and shows the whole proposal light in its place`() {
        val preview = assertNotNull(preview("x FO", TextRange(2, 4), "foo"))

        assertEquals(listOf("x foo"), preview.rows)
        assertEquals(2, preview.realColumns, "only the text before the replaced range is real")
        assertEquals(TextRange(2, 5), preview.ghost(0))
    }

    @Test
    fun `a proposal that matches inside the word shows light in place of the typed text`() {
        val preview = assertNotNull(preview("S = rob", TextRange(4, 7), "MyRobot"))

        assertEquals(listOf("S = MyRobot"), preview.rows)
        assertEquals(4, preview.realColumns)
        assertEquals(TextRange(4, 11), preview.ghost(0))
    }

    @Test
    fun `a replaced range that goes on behind the caret is replaced as a whole and the tail follows the proposal`() {
        val text = "STAT x"
        val preview = assertNotNull(preview(text, TextRange(0, 4), "STATE"))

        assertEquals(listOf("STATE x"), preview.rows)
        assertEquals(4, preview.realColumns)
        assertEquals(TextRange(4, 5), preview.ghost(0))
        assertEquals(4, preview.tailOffset)
        assertEquals(5, preview.tailColumn)
        assertEquals(listOf(CompletionPreview.RealRun(0, 0, 4), CompletionPreview.RealRun(5, 4, 2)), preview.realRuns(0))
        assertEquals(accepted(text, TextRange(0, 4), "STATE"), shown(text, preview))
    }

    @Test
    fun `typed text stays real when the replaced range goes on behind the caret with text the proposal does not begin with`() {
        val text = "{ \"mo\" }"
        val range = TextRange(3, 6)
        val preview = assertNotNull(preview(text, range, "model\": \"\""))

        assertEquals(listOf("{ \"model\": \"\" }"), preview.rows)
        assertEquals(5, preview.realColumns, "the brace, the space, the opening quote and mo are real")
        assertEquals(TextRange(5, 13), preview.ghost(0), "the closing quote of the replaced range is not shown")
        assertEquals(listOf(CompletionPreview.RealRun(0, 0, 5), CompletionPreview.RealRun(13, 6, 2)), preview.realRuns(0))
        assertEquals(accepted(text, range, "model\": \"\""), shown(text, preview))
    }

    @Test
    fun `a proposal that shares only the start of a word behind the caret keeps that start real`() {
        val preview = assertNotNull(preview("fobar", TextRange(0, 5), "foo"))

        assertEquals(listOf("foo"), preview.rows)
        assertEquals(2, preview.realColumns)
        assertEquals(TextRange(2, 3), preview.ghost(0))
    }

    @Test
    fun `text behind the replaced range on the same line stands behind the proposal`() {
        val text = "a pr(b)\nnext"
        val preview = assertNotNull(preview(text, TextRange(2, 4), "print"))

        assertEquals(listOf("a print(b)"), preview.rows)
        assertEquals(7, preview.tailColumn)
        assertEquals(listOf(CompletionPreview.RealRun(0, 0, 4), CompletionPreview.RealRun(7, 4, 3)), preview.realRuns(0))
        assertEquals(accepted(text, TextRange(2, 4), "print"), shown(text, preview))
    }

    @Test
    fun `a template of several lines at depth zero shows every line as a row`() {
        val text = "TRA\nEND"
        val template = "TRANSITION {\n    DO\n}"
        val preview = assertNotNull(preview(text, TextRange(0, 3), template))

        assertEquals(listOf("TRANSITION {", "    DO", "}"), preview.rows)
        assertEquals(2, preview.addedLines)
        assertEquals(listOf(TextRange(3, 12), TextRange(0, 6), TextRange(0, 1)), (0..2).map(preview::ghost))
        assertEquals(listOf(listOf(CompletionPreview.RealRun(0, 0, 3)), listOf(), listOf()), (0..2).map(preview::realRuns))
        assertEquals(accepted(text, TextRange(0, 3), template), shown(text, preview))
    }

    @Test
    fun `a nested template has the indentation of the acceptance and the tail on its last row`() {
        val text = "STATES {\n  TRA x\n}"
        val template = "TRANSITION {\n    DO\n}"
        val start = text.indexOf("TRA")
        val preview = assertNotNull(preview(text, TextRange(start, start + 3), template))

        assertEquals(listOf("  TRANSITION {", "      DO", "  } x"), preview.rows)
        assertEquals(1, preview.line)
        assertEquals(text.indexOf('\n') + 1, preview.lineStart)
        assertEquals(5, preview.realColumns)
        assertEquals(3, preview.tailColumn)
        assertEquals(listOf(CompletionPreview.RealRun(3, 5, 2)), preview.realRuns(2))
        assertEquals(accepted(text, TextRange(start, start + 3), template), shown(text, preview))
    }

    @Test
    fun `an empty document shows the whole proposal light`() {
        val preview = assertNotNull(preview("", TextRange(0, 0), "STATE"))

        assertEquals(listOf("STATE"), preview.rows)
        assertEquals(0, preview.realColumns)
        assertEquals(TextRange(0, 5), preview.ghost(0))
        assertEquals(listOf(), preview.realRuns(0))
    }

    @Test
    fun `on the last line the preview starts at that line`() {
        val text = "A\nB\nST"
        val preview = assertNotNull(preview(text, TextRange(4, 6), "STATE {\n}"))

        assertEquals(2, preview.line)
        assertEquals(4, preview.lineStart)
        assertEquals(listOf("STATE {", "}"), preview.rows)
        assertEquals(accepted(text, TextRange(4, 6), "STATE {\n}"), shown(text, preview))
    }

    @Test
    fun `a place on a row stands for the real text it shows and the light text for the end of the replaced range`() {
        val text = "  TRA x"
        val preview = assertNotNull(preview(text, TextRange(2, 5), "TRANSITION {\n}"))

        assertEquals(0, preview.offsetAt(0, 0))
        assertEquals(4, preview.offsetAt(0, 4), "inside the real prefix")
        assertEquals(5, preview.offsetAt(0, 5), "at the end of the real prefix")
        assertEquals(5, preview.offsetAt(0, 9), "light text")
        assertEquals(5, preview.offsetAt(1, 0), "start of the last row: still light")
        assertEquals(5, preview.offsetAt(1, 3), "start of the tail")
        assertEquals(7, preview.offsetAt(1, 5), "end of the tail, the end of the line")
        assertEquals(7, preview.offsetAt(1, 99), "beyond the row: the end of the line")
        assertEquals(4, preview.tailColumnOf(6))
    }

    @Test
    fun `a replaced range across lines has no preview`() {
        assertNull(preview("ab\ncd", TextRange(1, 4), "x"))
    }
}
