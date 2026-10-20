package io.karpfen.ide.ui.editor.model.caret

import io.karpfen.ide.ui.editor.model.action.TestEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/** Up/Down over lines with tabs keep the *display* column of [TabStops] with the editor's tab width (decision 40). */
class CaretTabColumnTest {

    private fun down(marked: String, tabWidth: Int, times: Int = 1): TestEditor {
        val editor = TestEditor(marked, tabWidth = tabWidth)
        repeat(times) { editor.caret.move(CaretMovement.DOWN, extendSelection = false) }
        return editor
    }

    @Test
    fun `down from a tab-indented line keeps the display column with tab width 4`() {
        // "\tx": x is at display column 4; on the space line, column 4 is after four spaces.
        val editor = down("\t|x\n        y", tabWidth = 4)
        assertEquals("\tx\n    |    y", editor.marked())
        assertEquals(4, editor.caret.primary.preferredColumn)
    }

    @Test
    fun `down from a tab-indented line keeps the display column with tab width 2`() {
        val editor = down("\t|x\n        y", tabWidth = 2)
        assertEquals("\tx\n  |      y", editor.marked())
        assertEquals(2, editor.caret.primary.preferredColumn)
    }

    @Test
    fun `down onto a tab line lands on the nearer bound of the tab`() {
        // Display column 5 on "a\tb" with width 4: the tab spans columns 1..4, b is at 4, so column 5 is after b.
        assertEquals("abcde\na\tb|", down("abcde|\na\tb", tabWidth = 4).marked())
        // Column 2 with width 4: inside the tab (1..4), nearer to its start.
        assertEquals("ab\na|\tb", down("ab|\na\tb", tabWidth = 4).marked())
        // Column 3 with width 4: nearer to the tab's end.
        assertEquals("abc\na\t|b", down("abc|\na\tb", tabWidth = 4).marked())
        // The same column 3 with width 2: the tab spans 1..2, b at 2, so column 3 is after b.
        assertEquals("abc\na\tb|", down("abc|\na\tb", tabWidth = 2).marked())
    }

    @Test
    fun `the preferred column survives a tab line with either width`() {
        for (width in listOf(2, 4)) {
            val editor = down("        |x\n\t\t\t\ty\n        z", tabWidth = width, times = 2)
            assertEquals("        x\n\t\t\t\ty\n        |z", editor.marked(), "tab width $width")
            assertEquals(8, editor.caret.primary.preferredColumn)
        }
    }

    @Test
    fun `up over tab lines mirrors down`() {
        val editor = TestEditor("\t\tx\n\t|y", tabWidth = 4)
        editor.caret.move(CaretMovement.UP, extendSelection = false)
        assertEquals("\t|\tx\n\ty", editor.marked())
        val narrow = TestEditor("\t\tx\n    |y", tabWidth = 2)
        narrow.caret.move(CaretMovement.UP, extendSelection = false)
        assertEquals("\t\t|x\n    y", narrow.marked(), "display column 4 is after two tabs of width 2")
    }

    @Test
    fun `the tab width is read on every vertical move`() {
        var width = 4
        val editor = TestEditor("\t|x\n        y\n        z")
        val caret = CaretModel(editor.text, CoroutineScope(Dispatchers.Unconfined)) { width }
        caret.moveTo(1)
        caret.move(CaretMovement.DOWN, extendSelection = false)
        assertEquals(4, caret.primary.preferredColumn)
        width = 2
        caret.moveTo(1)
        caret.move(CaretMovement.DOWN, extendSelection = false)
        assertEquals(2, caret.primary.preferredColumn)
    }
}
