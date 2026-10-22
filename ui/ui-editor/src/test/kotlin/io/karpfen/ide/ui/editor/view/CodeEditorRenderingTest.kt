package io.karpfen.ide.ui.editor.view

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.v2.runComposeUiTest
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource.IDE
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.lang.BracketMatch
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.find.FindQuery
import io.karpfen.ide.ui.editor.state.FakeLanguageServices
import io.karpfen.ide.ui.editor.state.FindBarMode
import io.karpfen.ide.ui.editor.theme.lightEditorTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the text area paints, checked on the pixels of a headless capture: the selection, find matches only while the
 * find bar is open, diagnostic underlines that follow their text, the current line and the matching-bracket box.
 * (Mapping ranges of older versions is covered by `VersionedRangesTest` and the state's pipeline tests.)
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorRenderingTest {

    private val theme = lightEditorTheme()
    private val text = """
        STATEMACHINE ATTACHED TO "Robot" {
            STATES {
                INITIAL STATE "ready" { }
                STATE "observe" { }
            }
        }
    """.trimIndent()

    private fun EditorUi.capture(): PixelMap = textArea.captureToImage().toPixelMap()

    /** The colour just inside the top-left corner of the cell at [offset] (above the glyphs). */
    private fun EditorUi.cellCorner(pixels: PixelMap, offset: Int): Color {
        val bounds = host.boundsOf(offset)!!
        return pixels[(bounds.left + 2f).roundToInt(), (bounds.top + 1f).roundToInt()]
    }

    private fun assertColor(expected: Color, actual: Color, message: String) {
        val close = abs(expected.red - actual.red) < 0.02f && abs(expected.green - actual.green) < 0.02f &&
            abs(expected.blue - actual.blue) < 0.02f
        assertTrue(close, "$message: expected $expected, was $actual")
    }

    /** True if some pixel in the bottom rows of [cell] is clearly reddish (an error underline). */
    private fun PixelMap.hasRedUnderline(cell: Rect): Boolean {
        val rows = (cell.bottom - 5f).roundToInt() until cell.bottom.roundToInt()
        val columns = cell.left.roundToInt() until cell.right.roundToInt()
        return rows.any { y -> columns.any { x -> this[x, y].let { it.red - it.green > 0.3f } } }
    }

    @Test
    fun `the selection and the current line are painted`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose(text)
        val robot = text.indexOf("Robot")

        ui.state.caret.select(robot, robot + 5)
        waitForIdle()
        val pixels = ui.capture()

        assertColor(theme.selection, ui.cellCorner(pixels, robot + 1), "selected")
        assertColor(theme.currentLine, ui.cellCorner(pixels, 2), "the caret's line, not selected")
        assertColor(theme.background, ui.cellCorner(pixels, text.indexOf("ready") + 1), "another line")
    }

    @Test
    fun `find matches are painted only while the find bar is open`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose(text)
        val delta = text.indexOf("observe")
        ui.state.find.setQuery(FindQuery("observe"))
        waitForIdle()
        ui.state.caret.moveTo(text.indexOf("ready"))
        waitForIdle()

        assertColor(theme.background, ui.cellCorner(ui.capture(), delta + 1), "find bar closed")

        ui.state.overlays.openFind(FindBarMode.FIND)
        waitForIdle()
        assertColor(theme.findMatchCurrent, ui.cellCorner(ui.capture(), delta + 1), "the current match (the first after the caret)")

        ui.state.overlays.closeFind()
        waitForIdle()
        assertColor(theme.background, ui.cellCorner(ui.capture(), delta + 1), "closed again")
    }

    /** A line inserted above moves the diagnostics of the old version with their text. */
    @Test
    fun `diagnostics are underlined and follow their text`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose(text)
        val alpha = text.indexOf("ready")
        val epsilon = text.indexOf("observe")
        val snapshot = ui.text.snapshot.value
        ui.state.session.setExternalDiagnostics(
            "test",
            snapshot,
            listOf(Diagnostic(TextRange(epsilon, epsilon + 7), Severity.ERROR, "bad", DiagnosticCodes.INTERNAL_ERROR, IDE)),
        )
        waitForIdle()
        assertTrue(ui.capture().hasRedUnderline(ui.host.boundsOf(epsilon + 2)!!))
        assertTrue(!ui.capture().hasRedUnderline(ui.host.boundsOf(alpha + 2)!!), "no underline elsewhere")

        ui.text.apply(listOf(TextEdit.insert(0, "new line\n")), EditOrigin.Typing)
        waitForIdle()
        val moved = epsilon + "new line\n".length
        assertTrue(ui.capture().hasRedUnderline(ui.host.boundsOf(moved + 2)!!))
    }

    @Test
    fun `the matching bracket pair is boxed`() = runComposeUiTest {
        val open = text.indexOf('{')
        val close = text.lastIndexOf('}')
        val pair = BracketMatch(TextRange(open, open + 1), TextRange(close, close + 1))
        val ui = EditorUi(this, FakeLanguageServices(brackets = { _, offset -> pair.takeIf { offset == open } }))
        ui.compose(text)

        ui.state.caret.moveTo(open)
        waitForIdle()
        val pixels = ui.capture()

        for (offset in listOf(open, close)) {
            val box = ui.host.boundsOf(offset)!!
            val y = box.center.y.roundToInt()
            val edge = (box.left.roundToInt() - 1..box.left.roundToInt() + 1).map { pixels[it, y] }
            assertTrue(edge.any { it.blue - it.red > 0.1f }, "the left edge of the box at $offset is blue-grey: $edge")
        }
    }
}
