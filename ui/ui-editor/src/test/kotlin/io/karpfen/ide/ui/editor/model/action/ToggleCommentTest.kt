package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Toggle comment: `//`, and `#` per line inside EVAL bodies by `embeddedRegionAt` (spec §7.1, decision 27) whose
 * `EVAL {` line lies above the block (decision 58); EVAL bodies come from the real KStates tokenizer.
 */
class ToggleCommentTest {

    private val lines = listOf(
        "STATE \"a\" {", //            0
        "    DO {", //                 1
        "        EVAL {", //           2
        "            x = 1", //        3
        "", //                         4
        "            // note", //      5
        "            y = 2", //        6
        "        }", //                7
        "    }", //                    8
        "}", //                        9
    )
    private val kstates = lines.joinToString("\n")

    /** An editor on [kstates] with the caret at [column] of [line] and EVAL bodies as embedded regions. */
    private fun editorAt(line: Int, column: Int = 0): TestEditor {
        val editor = TestEditor(kstates, tokensOf = ::evalBodies)
        editor.caret.moveTo(editor.text.snapshot.value.offsetOf(line, column))
        return editor
    }

    private fun toggledLines(editor: TestEditor): List<String> {
        editor.run(EditorActionIds.TOGGLE_COMMENT)
        return editor.plain.split("\n")
    }

    private fun expect(vararg replaced: Pair<Int, String>): List<String> =
        lines.toMutableList().apply { replaced.forEach { (line, text) -> set(line, text) } }

    @Test
    fun `the fixture's EVAL body is one embedded region from the brace to the closing brace`() {
        val tokens = evalBodies(TestEditor(kstates).text.snapshot.value)
        val open = kstates.indexOf("EVAL {") + "EVAL {".length
        val close = kstates.indexOf("        }")  + "        ".length
        assertEquals(listOf(TextRange(open, close)), tokens.embeddedRegions)
    }

    @Test
    fun `an empty line inside an EVAL body gets #`() {
        val editor = editorAt(4)
        assertEquals(expect(4 to "# "), toggledLines(editor))
        assertEquals(editor.text.snapshot.value.offsetOf(4, 2), editor.caret.primary.position, "the caret moves behind the marker")
    }

    @Test
    fun `a line holding a karpfen comment inside an EVAL body gets #`() {
        assertEquals(expect(5 to "            # // note"), toggledLines(editorAt(5, 15)))
    }

    @Test
    fun `the line of the closing brace of an EVAL body gets #`() {
        assertEquals(expect(7 to "        # }"), toggledLines(editorAt(7, 9)))
    }

    @Test
    fun `the EVAL line itself gets slashes`() {
        assertEquals(expect(2 to "        // EVAL {"), toggledLines(editorAt(2, 3)))
    }

    @Test
    fun `lines outside EVAL bodies get slashes`() {
        assertEquals(expect(0 to "// STATE \"a\" {"), toggledLines(editorAt(0)))
        assertEquals(expect(9 to "// }"), toggledLines(editorAt(9)))
    }

    @Test
    fun `toggling twice restores the line`() {
        for (line in lines.indices) {
            val editor = editorAt(line)
            editor.run(EditorActionIds.TOGGLE_COMMENT)
            editor.run(EditorActionIds.TOGGLE_COMMENT)
            assertEquals(kstates, editor.plain, "line $line")
        }
    }

    @Test
    fun `commented lines are uncommented with their own marker`() {
        val editor = TestEditor("    # x = 1|", tokensOf = { TokenizeResult(it.version, emptyList(), emptyList(), listOf(TextRange(0, it.length))) })
        editor.run(EditorActionIds.TOGGLE_COMMENT)
        assertEquals("    x = 1|", editor.marked())
        val slashes = TestEditor("//no space|")
        slashes.run(EditorActionIds.TOGGLE_COMMENT)
        assertEquals("no space|", slashes.marked())
    }

    @Test
    fun `a selection comments its non-blank lines at their smallest indentation`() {
        val editor = editorAt(3)
        val snapshot = editor.text.snapshot.value
        editor.caret.select(snapshot.offsetOf(3, 0), snapshot.offsetOf(7, 0))
        val toggled = toggledLines(editor)
        assertEquals(
            expect(3 to "            # x = 1", 5 to "            # // note", 6 to "            # y = 2"),
            toggled,
            "line 7 is not part of a selection ending at its column 0; blank line 4 stays",
        )
        val after = editor.text.snapshot.value
        assertEquals(TextRange(after.offsetOf(3, 0), after.offsetOf(7, 0)), editor.caret.primary.selection, "the lines stay selected")

        editor.run(EditorActionIds.TOGGLE_COMMENT)
        assertEquals(kstates, editor.plain, "every line commented: the selection is uncommented")
    }

    @Test
    fun `a selection holding the EVAL line comments the body with slashes as well`() {
        val editor = editorAt(2)
        val snapshot = editor.text.snapshot.value
        editor.caret.select(snapshot.offsetOf(2, 0), snapshot.offsetOf(3, 99))
        assertEquals(
            expect(2 to "        // EVAL {", 3 to "        //     x = 1"),
            toggledLines(editor),
            "once its EVAL line is a comment, the body is no Python any more",
        )
        editor.run(EditorActionIds.TOGGLE_COMMENT)
        assertEquals(kstates, editor.plain)
    }

    @Test
    fun `a selection from an EVAL body opened above it keeps # for the body and slashes after it`() {
        val editor = editorAt(6)
        val snapshot = editor.text.snapshot.value
        editor.caret.select(snapshot.offsetOf(6, 0), snapshot.offsetOf(8, 99))
        assertEquals(
            expect(6 to "    #         y = 2", 7 to "    #     }", 8 to "    // }"),
            toggledLines(editor),
            "the body's closing brace still closes it",
        )
        editor.run(EditorActionIds.TOGGLE_COMMENT)
        assertEquals(kstates, editor.plain)
    }

    @Test
    fun `commenting out a macro with a multi-line EVAL keeps the file valid and toggling again restores it`() {
        val machine = Examples.file("karpfen-runtime/single_robot_example/cleaning_robot.kstates").text
        val editor = TestEditor(machine, tokensOf = ::evalBodies)
        val snapshot = editor.text.snapshot.value
        val first = snapshot.lineIndex.lineOf(machine.indexOf("MACRO \"circular_bounding_box_distance\" {"))
        val last = first + 12
        assertEquals("        }", snapshot.lineText(last), "the macro's 13 lines")
        assertEquals(listOf(), syntaxErrors(machine))
        editor.caret.select(snapshot.lineIndex.lineStart(first), snapshot.lineIndex.lineEnd(last))

        editor.run(EditorActionIds.TOGGLE_COMMENT)
        val commented = editor.plain.split("\n").subList(first, last + 1)
        assertTrue(commented.all { it.trimStart().startsWith("// ") }, commented.joinToString("\n"))
        assertEquals(listOf(), syntaxErrors(editor.plain), "the commented-out macro is a karpfen comment")

        editor.run(EditorActionIds.TOGGLE_COMMENT)
        assertEquals(machine, editor.plain, "toggling again restores the macro")
    }

    private fun syntaxErrors(text: String): List<String> =
        defaultLanguageRegistry().support(KarpfenLanguage.KSTATES).parser.parse(TestEditor(text).text.snapshot.value)
            .syntaxDiagnostics.map { it.message }

    @Test
    fun `without embedded regions every line gets slashes`() {
        val editor = TestEditor("^type \"A\" \"\" {\n    prop(\"x\", \"number\")\n}|")
        editor.run(EditorActionIds.TOGGLE_COMMENT)
        assertEquals("// type \"A\" \"\" {\n//     prop(\"x\", \"number\")\n// }", editor.plain)
    }

    @Test
    fun `tokens are read when toggling, never for enablement`() {
        val editor = editorAt(4)
        val action = editor.registry.get(EditorActionIds.TOGGLE_COMMENT)!!
        action.isEnabled(editor)
        assertEquals(0, editor.tokenReads)
        editor.run(EditorActionIds.TOGGLE_COMMENT)
        assertTrue(editor.tokenReads > 0)
    }

    @Test
    fun `toggle comment right after an edit decides with the tokens of the current text`() {
        val editor = editorAt(3, 17)
        editor.run(EditorActionIds.ENTER)
        editor.type("z = 3")
        editor.run(EditorActionIds.TOGGLE_COMMENT)
        assertEquals("            # z = 3", editor.plain.split("\n")[4])
    }

    @Test
    fun `toggle comment is one undo step`() {
        val editor = editorAt(5, 14)
        val before = editor.marked()
        editor.run(EditorActionIds.TOGGLE_COMMENT)
        editor.run(EditorActionIds.UNDO)
        assertEquals(before, editor.marked())
    }
}
