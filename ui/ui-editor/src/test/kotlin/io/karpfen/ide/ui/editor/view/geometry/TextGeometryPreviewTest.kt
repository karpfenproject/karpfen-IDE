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
package io.karpfen.ide.ui.editor.view.geometry

import androidx.compose.ui.geometry.Rect
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.caret.TabStops
import io.karpfen.ide.ui.editor.model.completion.CompletionAcceptance
import io.karpfen.ide.ui.editor.model.completion.CompletionPreview
import io.karpfen.ide.ui.editor.state.ViewportSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The geometry while a completion preview shows. The text is `  TRA x`, `next` and `last`, and the preview replaces
 * `TRA` by a template of two lines. So the rows are `  TRANSITION {`, `  } x`, `next` and `last`, with cells of 10 px,
 * rows of 20 px and the text starting at x = 5. Positions stay those of the text and are drawn where the rows show them,
 * and clicks read the rows as they are drawn.
 */
class TextGeometryPreviewTest {

    private val text = "  TRA x\nnext\nlast"

    /** The preview of the template over `TRA` in [snapshot]. */
    private fun preview(snapshot: TextSnapshot): CompletionPreview {
        val range = TextRange(2, 5)
        val item = CompletionItem("TRANSITION", CompletionKind.SNIPPET, "TRANSITION {\n}", range, null, null, null, "t")
        return checkNotNull(CompletionPreview.of(CompletionAcceptance.of(item, range, snapshot), snapshot))
    }

    /** The geometry of the text with the preview. */
    private fun geometry(): TextGeometry {
        val snapshot = TextSnapshot(text, 0)
        return TextGeometry(snapshot, TabStops(4), 10f, 20f, 5f, preview(snapshot))
    }

    @Test
    fun `the lines below the preview move down by the rows it adds`() {
        val geometry = geometry()

        assertEquals(listOf("  TRANSITION {", "  } x"), geometry.preview?.rows)
        assertEquals(LineShift(0, 1), geometry.shift)
        assertEquals(4, geometry.rowCount)
        assertEquals(listOf(0f, 40f, 60f), (0..2).map(geometry::lineTop))
        assertEquals(2, geometry.rowOf(text.indexOf("next")))
        assertEquals(14, geometry.maxDisplayColumns(), "the widest row is a row of the preview")
    }

    @Test
    fun `the caret stays at its place in the text and the tail is drawn behind the proposal`() {
        val geometry = geometry()

        assertEquals(0, geometry.rowOf(5), "the end of the replaced range belongs to the typed text")
        assertEquals(55f, geometry.xOf(5))
        assertEquals(1, geometry.rowOf(6), "inside the tail")
        assertEquals(45f, geometry.xOf(6), "after the closing brace and the space")
        assertEquals(1, geometry.cellRowOf(5), "the first character of the tail")
        assertEquals(35f, geometry.cellXOf(5))
        assertEquals(0, geometry.cellRowOf(4))
        assertEquals(45f, geometry.cellXOf(4))
    }

    @Test
    fun `a click lands on the text as it is drawn`() {
        val geometry = geometry()

        assertEquals(text.indexOf("next") + 1, geometry.offsetAt(5f + 10f, 45f), "a moved line")
        assertEquals(text.indexOf("last") + 4, geometry.offsetAt(500f, 65f), "the last moved line")
        assertEquals(text.length, geometry.offsetAt(500f, 900f), "below the rows: the end")
        assertEquals(3, geometry.offsetAt(5f + 30f, 5f), "the real prefix of the first row")
        assertEquals(5, geometry.offsetAt(5f + 90f, 5f), "light text: the end of the replaced range")
        assertEquals(5, geometry.offsetAt(5f + 10f, 25f), "the light brace on the last row")
        assertEquals(6, geometry.offsetAt(5f + 40f, 25f), "the tail")
    }

    @Test
    fun `hovering finds real characters only`() {
        val geometry = geometry()

        assertEquals(3, geometry.characterAt(5f + 35f, 5f), "the R of TRA")
        assertNull(geometry.characterAt(5f + 75f, 5f), "light text")
        assertNull(geometry.characterAt(5f + 5f, 25f), "light text on the last row")
        assertEquals(6, geometry.characterAt(5f + 45f, 25f), "the x of the tail")
        assertEquals(text.indexOf("next"), geometry.characterAt(5f + 5f, 45f), "a moved line")
        assertNull(geometry.characterAt(5f, 85f), "below the rows")
    }

    @Test
    fun `ranges keep their place before the tail and follow the tail and the moved lines`() {
        val geometry = geometry()

        assertEquals(
            listOf(TextGeometry.Segment(0, 35f, 55f, 0), TextGeometry.Segment(0, 35f, 55f, 1)),
            geometry.segments(TextRange(3, 7), 0..2),
            "split at the end of the replaced range",
        )
        assertEquals(
            listOf(TextGeometry.Segment(0, 45f, 65f, 1), TextGeometry.Segment(1, 5f, 25f, 2)),
            geometry.segments(TextRange(6, 10), 0..2),
            "the line break cell follows the tail, and the next line is moved",
        )
    }

    @Test
    fun `the frame lays out the lines of the visible rows and anchors popups below the preview`() {
        val geometry = geometry()
        val frame = TextAreaFrame(geometry, ViewportSnapshot(viewportWidth = 200f, viewportHeight = 40f, lineHeight = 20f))
        val caret = frame.caretRect(5, 2f)

        assertEquals(0..2, frame.laidOut, "the four rows with the overscan, as the three lines they show")
        assertEquals(Rect(54f, 0f, 56f, 20f), caret)
        assertEquals(Rect(54f, 0f, 56f, 40f), frame.belowLineOf(5, caret), "down to the last row of the preview")
        assertEquals(Rect(35f, 20f, 45f, 40f), frame.characterRect(5), "the first character of the tail")
        assertEquals(Rect(5f, 40f, 15f, 60f), frame.characterRect(text.indexOf("next")))
    }

    @Test
    fun `a preview of another version is not shown`() {
        val geometry = TextGeometry(TextSnapshot(text, 1), TabStops(4), 10f, 20f, 5f, preview(TextSnapshot(text, 0)))

        assertNull(geometry.preview)
        assertEquals(LineShift.NONE, geometry.shift)
        assertEquals(3, geometry.rowCount)
        assertEquals(20f, geometry.lineTop(1))
    }
}
