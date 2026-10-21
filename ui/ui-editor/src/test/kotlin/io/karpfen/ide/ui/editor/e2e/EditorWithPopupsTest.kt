package io.karpfen.ide.ui.editor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.model.action.EditorActionTitles
import io.karpfen.ide.ui.editor.popup.EditorPopupTags
import io.karpfen.ide.ui.editor.state.CodeEditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `CodeEditor` and `EditorPopups` together (INTEGRATION, plan §4): completion opened by typing and driven by the text
 * area's keys through `handleKey`, the find bar's Escape returning focus, find next — from the find bar and by key — to
 * an off-screen match revealing it, the context menu by right-click, and Cmd+click navigation and the hover tooltip
 * with the real language services and parser.
 */
@OptIn(ExperimentalTestApi::class)
class EditorWithPopupsTest {

    @Test
    fun `typing opens completion and the text area's keys drive it through handleKey`() = runComposeUiTest {
        val ui = EditorWithPopups(this)
        ui.compose(MACHINE)
        ui.editor.requestFocus()
        ui.state.caret.moveTo(MACHINE.indexOf('\n', MACHINE.indexOf("TRANSITIONS {")))

        ui.editor.performKeyInput { pressKey(Key.Enter) }
        ui.typeChars("TRANSITION \"ready\" -> \"ob")

        val opened = assertNotNull(ui.state.completion.state.value, "typing an identifier in a target string opens completion")
        assertEquals("observe", opened.selected.label)
        onNodeWithTag(EditorPopupTags.COMPLETION).assertIsDisplayed()

        ui.editor.performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertNull(ui.state.completion.state.value, "Escape closes completion")
        onNodeWithTag(EditorPopupTags.COMPLETION).assertDoesNotExist()

        ui.editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        waitForIdle()
        assertEquals("observe", assertNotNull(ui.state.completion.state.value, "Ctrl+Space reopens it").selected.label)
        ui.editor.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertNull(ui.state.completion.state.value, "Enter accepted the item")
        assertEquals("TRANSITION \"ready\" -> \"observe\"", ui.lineAt(ui.state.caret.primary.position).trim())
        assertEquals(listOf(), ui.unconsumedKeys, "Enter, Escape and Ctrl+Space were consumed by the editor")
        assertTrue(ui.providerFailures.isEmpty(), "no provider failed: ${ui.providerFailures}")
    }

    @Test
    fun `Enter after DO and a brace opens the block instead of accepting the DO snippet`() = runComposeUiTest {
        assertEnterOpensBlock(EditorWithPopups(this), "DO", STATE_BODY.indexOf(EMPTY_BODY_LINE) + 1 + 12, "            ")
    }

    @Test
    fun `Enter after TRANSITIONS and a brace opens the block instead of accepting the TRANSITIONS snippet`() = runComposeUiTest {
        assertEnterOpensBlock(EditorWithPopups(this), "TRANSITIONS", STATE_BODY.indexOf("\n}\n"), "    ", lineBreakFirst = true)
    }

    /**
     * Types [keyword] at [offset] of [STATE_BODY] (after a line break when [lineBreakFirst]), checks that it opened the
     * list, types ` {` and Enter, and asserts the block of spec §7.1 at [indent]: `}` on its own line.
     */
    private fun ComposeUiTest.assertEnterOpensBlock(ui: EditorWithPopups, keyword: String, offset: Int, indent: String, lineBreakFirst: Boolean = false) {
        ui.compose(STATE_BODY)
        ui.editor.requestFocus()
        ui.state.caret.moveTo(offset)
        if (lineBreakFirst) {
            ui.editor.performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
        }

        ui.typeChars(keyword)
        assertNotNull(ui.state.completion.state.value, "$keyword opened the list with its snippet")
        ui.typeChars(" {")
        assertNull(ui.state.completion.state.value, "the space ended the word being completed")
        ui.editor.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        val start = ui.plain.indexOf("$keyword {")
        assertEquals("$indent$keyword {", ui.lineAt(start), "spec §7.1: `}` goes on its own line")
        assertEquals("$indent    ", ui.lineAt(ui.state.caret.primary.position), "the caret on the indented line")
        assertEquals("$indent}", ui.lineAt(ui.plain.indexOf('}', start)))
        assertEquals(listOf(), ui.unconsumedKeys)
    }

    @Test
    fun `Tab after a block keyword and a brace indents instead of accepting the keyword's snippet`() = runComposeUiTest {
        val ui = EditorWithPopups(this)
        ui.compose(STATE_BODY)
        ui.editor.requestFocus()
        ui.state.caret.moveTo(STATE_BODY.indexOf(EMPTY_BODY_LINE) + 1 + 12)

        ui.typeChars("DO {")
        ui.editor.performKeyInput { pressKey(Key.Tab) }
        waitForIdle()

        assertEquals("            DO {    }", ui.lineAt(ui.state.caret.primary.position), "Tab inserted an indent")
    }

    @Test
    fun `Escape in the find bar closes it and returns focus to the text area`() = runComposeUiTest {
        val ui = EditorWithPopups(this)
        ui.compose(MACHINE)
        ui.editor.requestFocus()

        ui.editor.performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.F) } }
        waitForIdle()
        onNodeWithTag(EditorPopupTags.FIND_BAR).assertIsDisplayed()
        onNodeWithTag(EditorPopupTags.FIND_QUERY).assertIsFocused()
        assertFalse(ui.state.isFocused.value, "the query field took the focus")

        onNodeWithTag(EditorPopupTags.FIND_QUERY).performTextInput("observe")
        waitForIdle()
        val first = MACHINE.indexOf("observe")
        assertEquals(TextRange(first, first + "observe".length), ui.state.caret.primary.selection, "the first match is selected")

        onNodeWithTag(EditorPopupTags.FIND_QUERY).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        onNodeWithTag(EditorPopupTags.FIND_BAR).assertDoesNotExist()
        ui.editor.assertIsFocused()
        assertTrue(ui.state.isFocused.value)
        ui.editor.performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertEquals(first + "observe".length, ui.state.caret.primary.position, "keys reach the editor again")
    }

    @Test
    fun `find next from the find bar and by key reveals an off-screen match`() = runComposeUiTest {
        val ui = EditorWithPopups(this)
        ui.compose(NEEDLES)
        val needles = Regex("needle").findAll(NEEDLES).map { it.range.first }.toList()
        ui.editor.requestFocus()

        ui.editor.performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.F) } }
        waitForIdle()
        onNodeWithTag(EditorPopupTags.FIND_QUERY).performTextInput("needle")
        waitForIdle()
        assertEquals(needles[0], ui.state.caret.primary.selection.start)
        assertTrue(ui.isVisible(needles[0]))
        assertFalse(ui.isVisible(needles[1]), "the second match starts off-screen")

        onNodeWithTag(EditorPopupTags.FIND_QUERY).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(TextRange(needles[1], needles[1] + 6), ui.state.caret.primary.selection)
        assertTrue(ui.isVisible(needles[1]), "Enter in the find bar revealed the match")
        assertNotNull(ui.host.boundsOf(needles[1]), "and its line is laid out")
        onNodeWithTag(EditorPopupTags.FIND_STATUS, useUnmergedTree = true).assertIsDisplayed()

        onNodeWithTag(EditorPopupTags.FIND_QUERY).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(ui.isVisible(needles[2]))
        ui.editor.performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.G) } }
        waitForIdle()

        assertEquals(TextRange(needles[2], needles[2] + 6), ui.state.caret.primary.selection)
        assertTrue(ui.isVisible(needles[2]), "Cmd+G revealed the match")
        assertEquals(listOf(), ui.unconsumedKeys)
    }

    @Test
    fun `a right-click opens the context menu and its entries act where the user clicked`() = runComposeUiTest {
        val ui = EditorWithPopups(this)
        ui.compose(MACHINE)
        val ready = MACHINE.indexOf("INITIAL STATE \"ready\"")

        ui.textArea.performMouseInput { rightClick(ui.pointAt(ready + 2)) }
        waitForIdle()
        onNodeWithTag(EditorPopupTags.CONTEXT_MENU).assertIsDisplayed()
        assertEquals(ready + 2, ui.state.caret.primary.position, "the caret moved to the click")
        CONTEXT_MENU_TITLES.forEach { onNodeWithText(it).assertIsDisplayed() }

        onNodeWithText(EditorActionTitles.of(EditorActionIds.TOGGLE_COMMENT)).performClick()
        waitForIdle()
        onNodeWithTag(EditorPopupTags.CONTEXT_MENU).assertDoesNotExist()
        assertEquals("        // INITIAL STATE \"ready\" { }", ui.lineAt(ready))

        val observe = MACHINE.indexOf("STATE \"observe\"") + "STATE \"".length + "// ".length
        ui.state.caret.select(observe, observe + "observe".length)
        ui.textArea.performMouseInput { rightClick(ui.pointAt(observe + 3)) }
        waitForIdle()
        assertEquals(TextRange(observe, observe + 7), ui.state.caret.primary.selection, "a click inside the selection keeps it")
        onNodeWithText(EditorActionTitles.of(EditorActionIds.COPY)).performClick()
        waitForIdle()
        assertEquals("observe", ui.clipboard.content)
    }

    @Test
    fun `Cmd+click on a state reference navigates to its declaration with the real language services`() = runComposeUiTest {
        val ui = EditorWithPopups(this)
        ui.compose(MACHINE)
        mainClock.advanceTimeBy(1_000)
        val reference = MACHINE.indexOf("\"ready\" -> \"observe\"") + "\"ready\" -> \"".length
        val declaration = MACHINE.indexOf("STATE \"observe\"") + "STATE \"".length
        ui.state.scrollTo(reference, center = true)
        waitForIdle()

        ui.editor.performKeyInput { keyDown(Key.MetaLeft) }
        ui.textArea.performMouseInput { click(ui.pointAt(reference + 2)) }
        ui.editor.performKeyInput { keyUp(Key.MetaLeft) }
        waitForIdle()

        val target = ui.navigated.single()
        assertEquals(TextRange(declaration, declaration + "observe".length), target.range)
        assertEquals(ui.state.session.id.path, target.path)
        assertEquals(0, ui.state.caret.primary.position, "the caret stays")
        assertTrue(ui.providerFailures.isEmpty(), "no provider failed: ${ui.providerFailures}")
    }

    @Test
    fun `resting over a syntax error shows the parser's message in the hover tooltip`() = runComposeUiTest {
        val ui = EditorWithPopups(this)
        ui.compose("STATEMACHINE ATTACHED TO \"Robot\" {\n    STATES {\n        STATE \"a\" { }\n    }\n    TRANSITIONS { }\n}\n")
        ui.editor.requestFocus()
        val transitions = ui.plain.indexOf("TRANSITIONS")
        ui.state.caret.moveTo(transitions)
        ui.typeChars("X ")
        mainClock.advanceTimeBy(1_000)
        val diagnostic = assertNotNull(ui.state.session.diagnostics.value.diagnostics.firstOrNull(), "the parser reported the stray X")
        mainClock.autoAdvance = false

        ui.textArea.performMouseInput { moveTo(ui.pointAt(diagnostic.range.start)) }
        mainClock.advanceTimeBy(CodeEditorState.HOVER_DELAY.inWholeMilliseconds + 100)

        val hover = assertNotNull(ui.state.overlays.hover.value, "the hover appeared after the delay")
        assertTrue(diagnostic.message in hover.diagnostics.map { it.message }, "${hover.diagnostics}")
        onNodeWithTag(EditorPopupTags.HOVER).assertIsDisplayed()
        onNodeWithText(diagnostic.message, substring = true).assertIsDisplayed()
    }

    private companion object {
        val MACHINE: String = Examples.file("karpfen-runtime/single_robot_example/cleaning_robot.kstates").text

        /** A state machine whose initial state has an empty body line ([EMPTY_BODY_LINE]). */
        const val STATE_BODY = "STATEMACHINE ATTACHED TO \"Robot\" {\n    STATES {\n        INITIAL STATE \"a\" {\n            \n        }\n    }\n}\n"

        /** The line break before the empty body line of [STATE_BODY] and the line itself. */
        const val EMPTY_BODY_LINE = "\n            \n"

        /** 200 lines with "needle" on lines 5, 120 and 190, so the second and third match start off-screen. */
        val NEEDLES: String = (0 until 200).joinToString("\n") { line ->
            if (line in setOf(5, 120, 190)) "    // line $line: needle" else "    // line $line"
        }

        /** The titles of the default context menu (spec §7.2: Cut, Copy, Paste, Go to Definition, Toggle Comment). */
        val CONTEXT_MENU_TITLES: List<String> = listOf(
            EditorActionIds.CUT,
            EditorActionIds.COPY,
            EditorActionIds.PASTE,
            EditorActionIds.GO_TO_DEFINITION,
            EditorActionIds.TOGGLE_COMMENT,
        ).map(EditorActionTitles::of)
    }
}
