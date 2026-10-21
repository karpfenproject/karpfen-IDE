package io.karpfen.ide.app.e2e

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.foundation.widgets.TreeViewTestTags
import io.karpfen.ide.ui.workbench.Workbench
import io.karpfen.ide.ui.workbench.WorkbenchTestTags
import io.karpfen.ide.ui.workbench.layout.ToolWindowId
import io.karpfen.ide.ui.workbench.tabs.view.EditorAreaTestTags
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestDispatcher
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * Key routing of the composed IDE, headless (decision 15 of the work-package plan; INTEGRATION, plan §4): the
 * production workbench inside a key handler that plays the window menu's accelerators ([MenuAccelerators]). One
 * Cmd+Z in the editor undoes exactly one step — the editor consumes the stroke, so no menu command runs — and Cmd+X
 * with the focus in the project tree reaches the menu, whose Cut is disabled while the editor is unfocused, so the
 * editor text stays unchanged. (The native macOS menu bar itself is covered by the manual test.)
 */
@OptIn(ExperimentalTestApi::class)
class KeyRoutingEndToEndTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()
    private val machine: Path get() = root.resolve("${DescriptorExamples.CONTEXT_DIRECTORY}/cleaning_robot.kstates")

    @Test
    fun `one Cmd+Z in the editor undoes exactly one step and runs no menu command`() = composedIde { ide, menu, editor ->
        val original = text(editor)
        onNodeWithTag(EditorAreaTestTags.EDITOR).requestFocus()
        onNodeWithTag(EditorAreaTestTags.EDITOR).performTextInput("a")
        ide.time += 2.seconds
        onNodeWithTag(EditorAreaTestTags.EDITOR).performTextInput("b")
        settle(ide)
        assertEquals("ab$original", text(editor))

        onNodeWithTag(EditorAreaTestTags.EDITOR).performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Z) } }
        settle(ide)

        assertEquals("a$original", text(editor), "exactly one step was undone")
        assertEquals(listOf(), menu.reached, "the editor consumed Cmd+Z; the menu never saw it")

        onNodeWithTag(EditorAreaTestTags.EDITOR).performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Z) } }
        settle(ide)
        assertEquals(original, text(editor), "the next press undoes the next step")
    }

    @Test
    fun `Cmd+X with the focus in the project tree leaves the editor text unchanged`() = composedIde { ide, menu, editor ->
        val original = text(editor)
        editor.caret.select(0, "STATEMACHINE".length)
        projectTree().requestFocus()
        settle(ide)
        projectTree().assertIsFocused()
        assertFalse(editor.isFocused.value)

        projectTree().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.X) } }
        settle(ide)

        assertEquals(listOf(EditorActionIds.CUT), menu.reached, "the tree does not consume Cmd+X, the menu's Cut sees it")
        assertEquals(listOf(), menu.ran, "Cut is disabled while the editor is not focused")
        assertEquals(original, text(editor))
        assertEquals(TextRange(0, "STATEMACHINE".length), editor.caret.primary.selection)

        onNodeWithTag(EditorAreaTestTags.EDITOR).requestFocus()
        onNodeWithTag(EditorAreaTestTags.EDITOR).performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.X) } }
        settle(ide)
        assertEquals(original.removePrefix("STATEMACHINE"), text(editor), "in the focused editor Cmd+X cuts")
        assertEquals(listOf(EditorActionIds.CUT), menu.reached, "and the editor consumed that stroke")
    }

    /**
     * Composes the prepared IDE over a copy of the examples with [machine] open in the active tab, inside the menu
     * accelerators' key handler, and runs [block] with the IDE, the accelerators and the editor.
     */
    private fun composedIde(block: suspend ComposeUiTest.(EndToEndIde, MenuAccelerators, CodeEditorState) -> Unit) =
        runComposeUiTest {
            val scheduler = (currentCoroutineContext()[ContinuationInterceptor] as TestDispatcher).scheduler
            EndToEndIde(scheduler, dir.resolve("settings")).use { ide ->
                Examples.copyTo(root)
                val session = ide.prepare(root)
                val menu = MenuAccelerators(session.commands, session.state.settings.value.recentFolders.size)
                setContent {
                    Box(Modifier.size(1280.dp, 860.dp).onKeyEvent(menu::dispatch)) { Workbench(session.state) }
                }
                session.state.openFile(machine)
                settle(ide)
                val editor = assertNotNull(session.state.tabs.editorFor(machine), "the file is open")
                block(ide, menu, editor)
            }
        }

    /** The project tree (the Outline is a `TreeView` as well). */
    private fun ComposeUiTest.projectTree(): SemanticsNodeInteraction =
        onNode(hasTestTag(TreeViewTestTags.TREE) and hasAnyAncestor(hasTestTag(WorkbenchTestTags.toolWindow(ToolWindowId.PROJECT))))

    /** Runs the state's pending work (virtual time) and the resulting frames, a few times. */
    private fun ComposeUiTest.settle(ide: EndToEndIde) {
        repeat(4) {
            ide.settle()
            waitForIdle()
        }
    }

    private fun text(editor: CodeEditorState): String = editor.session.text.snapshot.value.text
}
