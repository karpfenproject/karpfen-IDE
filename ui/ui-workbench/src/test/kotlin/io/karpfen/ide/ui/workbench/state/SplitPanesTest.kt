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
package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.core.workspace.files.FileChangeKind
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.tabs.EditorViewMode
import io.karpfen.ide.ui.workbench.tabs.PaneId
import io.karpfen.ide.ui.workbench.tabs.SplitSide
import io.karpfen.ide.ui.workbench.testing.TestWorkbench
import io.karpfen.ide.ui.workbench.testing.withWorkbench
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests the editor panes through the workbench: splitting, the shared document of a file shown in several panes, the
 * unsaved-changes question for the last view only, the flows that follow the active pane, opening and navigating into
 * the active pane, rename, delete and conflicts across panes, and closing the folder.
 */
class SplitPanesTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("examples").toAbsolutePath().normalize()
    private val example: Path get() = root.resolve("karpfen-runtime/single_robot_example")
    private val machine: Path get() = example.resolve("cleaning_robot.kstates")
    private val metamodel: Path get() = example.resolve("cleaning_robot.kmeta")
    private val model: Path get() = example.resolve("cleaning_robot.kmodel")

    private fun scenario(block: suspend TestWorkbench.() -> Unit) = runTest(timeout = 60.seconds) {
        Examples.copyTo(root)
        withWorkbench(dir.resolve("settings")) {
            open(root)
            block()
        }
    }

    private fun TestWorkbench.edit(path: Path, text: String = "// edited\n") {
        document(path).session.text.apply(listOf(TextEdit.insert(0, text)), EditOrigin.Typing)
        settle()
    }

    /** The active pane. */
    private val TestWorkbench.activePane: PaneId get() = state.tabs.activePane.value

    /** Splits the active pane towards [side] and returns the new pane. */
    private fun TestWorkbench.split(side: SplitSide = SplitSide.RIGHT): PaneId {
        state.splitActivePane(side).joinSettled()
        return activePane
    }

    /** The paths of the tabs of every pane, in focus order. */
    private fun TestWorkbench.paneTabs(): List<List<Path>> = state.tabs.layout.value.panes.map { it.tabs }

    @Test
    fun `a split shows the active file in a new pane on the same document with an editor of its own`() = scenario {
        openEditor(model)
        val left = openEditor(machine)
        left.caret.moveTo(42)
        val first = activePane

        val second = split(SplitSide.RIGHT)

        assertNotSame(first, second)
        assertEquals(listOf(listOf(model, machine), listOf(machine)), paneTabs())
        val right = assertNotNull(state.tabs.editorFor(second, machine))
        assertNotSame(left, right)
        assertSame(left.session, right.session, "one document session")
        assertEquals(42, right.caret.primary.position, "the new view starts at the caret of the split pane")
        assertEquals(setOf(machine, model), workspace.documents.openDocuments.value.map { it.path }.toSet())
        assertEquals(2, workspace.documents.openDocuments.value.size, "one document per file")

        edit(machine)
        assertTrue(left.snapshot.value.text.startsWith("// edited") && right.snapshot.value.text.startsWith("// edited"))
        assertTrue(state.tabs.panes.value.values.all { pane -> pane.tabs.single { it.path == machine }.isDirty })
        right.caret.moveTo(0)
        assertEquals(42 + "// edited\n".length, left.caret.primary.position, "each view keeps its own caret")
        right.session.text.undo.undo()
        settle()
        assertFalse(left.snapshot.value.text.startsWith("// edited"), "one undo history")
    }

    @Test
    fun `a split keeps the view mode, and the view modes of the panes are independent`() = scenario {
        openEditor(machine)
        state.tabs.setViewMode(machine, EditorViewMode.DIAGRAM)
        val first = activePane

        val second = split(SplitSide.DOWN)
        assertEquals(EditorViewMode.DIAGRAM, state.tabs.viewModes.value[machine])
        state.tabs.setViewMode(machine, EditorViewMode.TEXT)

        assertEquals(EditorViewMode.DIAGRAM, state.tabs.panes.value.getValue(first).viewMode)
        assertEquals(EditorViewMode.TEXT, state.tabs.panes.value.getValue(second).viewMode)
    }

    @Test
    fun `a pane without a tab is not split`() = scenario {
        state.splitActivePane(SplitSide.LEFT).joinSettled()
        assertEquals(1, state.tabs.layout.value.panes.size)
    }

    @Test
    fun `closing a view of a dirty file asks only when it is the last view`() = scenario {
        openEditor(machine)
        val first = activePane
        val second = split()
        edit(machine)

        state.closeTab(machine).joinSettled()
        assertNoDialog()
        assertEquals(listOf(first), state.tabs.layout.value.panes.map { it.id }, "the empty pane disappears")
        assertEquals(first, activePane, "its neighbour is active")
        assertNotNull(workspace.documents.get(machine), "the document stays open with its changes")
        assertTrue(document(machine).isDirty.value)
        assertNull(state.tabs.editorFor(second, machine))

        state.closeTab(machine)
        answer(DialogRequest.UnsavedChanges::class, DialogResponse.Discard)
        assertNull(workspace.documents.get(machine), "the last view closes the document")
        assertTrue(state.tabs.tabs.value.isEmpty())
        assertEquals(1, state.tabs.layout.value.panes.size, "the last pane stays, empty")
    }

    @Test
    fun `a tab closes in its own pane only`() = scenario {
        openEditor(machine)
        val first = activePane
        val second = split()
        openEditor(model)

        state.closeTab(first, machine).joinSettled()

        assertEquals(listOf(listOf(machine, model)), paneTabs())
        assertEquals(second, activePane)
        assertNotNull(workspace.documents.get(machine))
    }

    @Test
    fun `the outline, the status bar, Save and Close Tab follow the active pane`() = scenario {
        openEditor(machine)
        val first = activePane
        split()
        openEditor(metamodel)
        assertEquals(metamodel, state.outline.state.value.path)
        assertEquals(KarpfenLanguage.KMETA, state.statusBar.state.value.language)

        state.tabs.activatePane(first)
        settle()
        assertEquals(machine, state.tabs.active.value)
        assertEquals(machine, state.outline.state.value.path)
        assertEquals(KarpfenLanguage.KSTATES, state.statusBar.state.value.language)
        assertSame(state.tabs.editorFor(first, machine), state.tabs.activeEditor.value)

        edit(machine)
        edit(metamodel)
        state.saveActive().joinSettled()
        assertFalse(document(machine).isDirty.value)
        assertTrue(document(metamodel).isDirty.value, "Save saves the active tab of the active pane")

        state.closeActiveTab().joinSettled()
        assertEquals(listOf(listOf(machine, metamodel)), paneTabs(), "Close Tab closed the tab of the active pane")
    }

    @Test
    fun `files open in the active pane, a file shown elsewhere gets a new view, and navigation moves the caret there`() =
        scenario {
            openEditor(machine)
            val first = activePane
            val second = split()
            openEditor(model)
            state.tabs.activatePane(first)

            state.openFile(model).joinSettled()
            assertEquals(listOf(listOf(machine, model), listOf(machine, model)), paneTabs())
            assertEquals(first, activePane)
            assertSame(state.tabs.editorFor(first, model)?.session, state.tabs.editorFor(second, model)?.session)

            val offset = Files.readString(machine).indexOf("STATES")
            state.navigateTo(Location(machine, TextRange(offset, offset + 6))).joinSettled()
            assertEquals(first, activePane)
            assertEquals(machine, state.tabs.active.value)
            assertEquals(offset, assertNotNull(state.tabs.editorFor(first, machine)).caret.primary.position)
            assertEquals(0, assertNotNull(state.tabs.editorFor(second, machine)).caret.primary.position, "the other pane stays")
        }

    @Test
    fun `navigation into a pane that shows the diagram alone shows the text next to it`() = scenario {
        openEditor(machine)
        val first = activePane
        val second = split()
        state.tabs.setViewMode(second, machine, EditorViewMode.DIAGRAM)

        val offset = Files.readString(machine).indexOf("STATES")
        state.navigateTo(Location(machine, TextRange(offset, offset + 6))).joinSettled()

        assertEquals(second, activePane)
        assertEquals(EditorViewMode.SPLIT, state.tabs.panes.value.getValue(second).viewMode)
        assertEquals(offset, assertNotNull(state.tabs.editorFor(second, machine)).caret.primary.position)
        assertEquals(EditorViewMode.TEXT, state.tabs.panes.value.getValue(first).viewMode, "the other pane stays")
    }

    @Test
    fun `Focus Next into a pane that shows the diagram alone asks no hidden editor for the focus`() = scenario {
        openEditor(machine)
        val first = activePane
        val second = split()
        state.tabs.setViewMode(second, machine, EditorViewMode.DIAGRAM)
        val hidden = assertNotNull(state.tabs.editorFor(second, machine))
        state.tabs.activatePane(first)
        hidden.consumeFocusRequest()

        state.tabs.focusNextPane()

        assertEquals(second, activePane)
        assertFalse(hidden.focusRequested.value, "the view of the pane holds the focus")
    }

    @Test
    fun `a definition lookup navigates in the pane of the editor that asked, also after another pane became active`() =
        scenario {
            val left = openEditor(machine)
            val first = activePane
            val second = split()
            val right = assertNotNull(state.tabs.editorFor(second, machine))
            val text = Files.readString(machine)
            val reference = text.indexOf("TRANSITION \"ready\" -> \"observe\"") + "TRANSITION \"ready\" -> \"".length + 2

            left.goToDefinition(reference)
            state.tabs.activatePane(second)
            settle()

            assertEquals(first, activePane, "the pane of the asking editor")
            assertTrue(text.substring(left.caret.primary.position).startsWith("observe\" {"))
            assertEquals(0, right.caret.primary.position, "the other pane stays")
        }

    @Test
    fun `Focus Next and Focus Previous move through the panes`() = scenario {
        openEditor(machine)
        val first = activePane
        val second = split()

        state.tabs.focusNextPane()
        assertEquals(first, activePane)
        assertTrue(assertNotNull(state.tabs.editorFor(first, machine)).focusRequested.value)
        state.tabs.focusPreviousPane()
        assertEquals(second, activePane)
    }

    @Test
    fun `renaming a file shown in two panes keeps both views in place`() = scenario {
        openEditor(model)
        openEditor(machine)
        state.tabs.activate(model)
        val first = activePane
        val second = split(SplitSide.LEFT)
        state.tabs.setViewMode(model, EditorViewMode.SPLIT)
        openEditor(metamodel)
        state.tabs.activatePane(first)
        edit(model)

        state.rename(model)
        answer(DialogRequest.Rename::class, DialogResponse.Text("room.kmodel"))
        answer(DialogRequest.UnsavedChanges::class, DialogResponse.Save)

        val renamed = example.resolve("room.kmodel")
        assertEquals(listOf(listOf(renamed, metamodel), listOf(renamed, machine)), paneTabs())
        assertEquals(first, activePane)
        assertEquals(renamed, state.tabs.active.value)
        assertEquals(metamodel, state.tabs.layout.value.pane(second)?.active)
        assertEquals(EditorViewMode.SPLIT, state.tabs.panes.value.getValue(second).viewModes[renamed])
        val editors = listOf(state.tabs.editorFor(first, renamed), state.tabs.editorFor(second, renamed))
        assertSame(editors[0]?.session, editors[1]?.session)
        assertEquals(listOf(renamed), workspace.documents.openDocuments.value.map { it.path }.filter { it.fileName.toString().endsWith(".kmodel") })
        assertNull(workspace.documents.get(model))
        assertTrue(Files.readString(renamed).startsWith("// edited"))
    }

    @Test
    fun `deleting a file closes it in every pane and the emptied panes collapse`() = scenario {
        openEditor(model)
        openEditor(machine)
        split()

        state.delete(machine)
        answer(DialogRequest.ConfirmDelete::class, DialogResponse.Confirm)

        assertEquals(listOf(listOf(model)), paneTabs())
        assertNull(workspace.documents.get(machine))
    }

    @Test
    fun `Close on a file deleted on disk closes it in every pane`() = scenario {
        openEditor(model)
        openEditor(machine)
        split()
        edit(machine)
        Files.delete(machine)
        watcher.report(machine, FileChangeKind.DELETED)
        settle()

        state.resolveConflict(machine, ConflictResolution.RELOAD).joinSettled()

        assertEquals(listOf(listOf(model)), paneTabs())
        assertNoDialog()
    }

    @Test
    fun `Cancel on overwriting a change on disk shows the file in a pane that has it`() = scenario {
        openEditor(machine)
        val first = activePane
        val second = split()
        openEditor(model)
        state.closeTab(second, machine).joinSettled()
        edit(machine)
        Files.writeString(machine, "// changed on disk\n")
        watcher.report(machine, FileChangeKind.MODIFIED)
        settle()
        assertEquals(listOf(listOf(machine), listOf(model)), paneTabs())
        assertEquals(second, activePane)

        state.saveAll()
        answer(DialogRequest.ConfirmOverwrite::class, DialogResponse.Cancel)

        assertEquals(first, activePane)
        assertEquals(machine, state.tabs.active.value)
    }

    @Test
    fun `exit asks once about a dirty file shown in two panes`() = scenario {
        openEditor(machine)
        split()
        edit(machine)

        val exit = scope.async { state.requestExit() }
        val asked = answer(DialogRequest.UnsavedChanges::class, DialogResponse.Discard)

        assertEquals(listOf(machine), asked.paths)
        assertTrue(exit.await())
    }

    @Test
    fun `opening another folder closes every pane`() = scenario {
        openEditor(machine)
        split()
        split(SplitSide.DOWN)
        val other = dir.resolve("other").also { Files.createDirectories(it) }

        open(other)

        assertEquals(listOf(emptyList<Path>()), paneTabs())
        assertTrue(workspace.documents.openDocuments.value.isEmpty())
    }
}
