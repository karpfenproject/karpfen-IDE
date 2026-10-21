package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.core.workspace.document.ExternalChangeKind
import io.karpfen.ide.core.workspace.files.FileChangeKind
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.testing.TestWorkbench
import io.karpfen.ide.ui.workbench.testing.withWorkbench
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Tabs (spec §8): opening, dirty marker, saving, closing with unsaved changes, external-change conflicts. */
class TabFlowsTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("examples").toAbsolutePath().normalize()
    private val example: Path get() = root.resolve("karpfen-runtime/single_robot_example")
    private val machine: Path get() = example.resolve("cleaning_robot.kstates")
    private val metamodel: Path get() = example.resolve("cleaning_robot.kmeta")
    private val model: Path get() = example.resolve("cleaning_robot.kmodel")

    private fun TestWorkbench.edit(path: Path, text: String = "// edited\n") {
        document(path).session.text.apply(listOf(TextEdit.insert(0, text)), EditOrigin.Typing)
        settle()
    }

    private fun scenario(block: suspend TestWorkbench.() -> Unit) = runTest(timeout = 60.seconds) {
        Examples.copyTo(root)
        withWorkbench(dir.resolve("settings")) {
            open(root)
            block()
        }
    }

    @Test
    fun `opening files adds tabs after the active one and reopening activates the tab`() = scenario {
        openEditor(machine)
        openEditor(model)
        state.tabs.activate(machine)
        val editor = openEditor(metamodel)

        val titles = state.tabs.tabs.value.map { it.title }
        assertEquals(listOf("cleaning_robot.kstates", "cleaning_robot.kmeta", "cleaning_robot.kmodel"), titles)
        assertEquals(metamodel, state.tabs.active.value)
        assertTrue(state.tabs.activeEditor.value === editor)
        assertEquals("karpfen-runtime/single_robot_example/cleaning_robot.kmeta", state.tabs.tabs.value[1].tooltip)

        openEditor(machine)
        assertEquals(3, state.tabs.tabs.value.size, "no second tab")
        assertEquals(machine, state.tabs.active.value)
    }

    @Test
    fun `the outline and the status bar follow the active tab`() = scenario {
        openEditor(machine)
        openEditor(metamodel)
        assertEquals(metamodel, state.outline.state.value.path)
        assertEquals(KarpfenLanguage.KMETA, state.statusBar.state.value.language)

        state.tabs.activate(machine)
        settle()
        assertEquals(machine, state.outline.state.value.path)
        assertEquals(KarpfenLanguage.KSTATES, state.statusBar.state.value.language)
        assertEquals(1, state.statusBar.state.value.line)

        state.closeTab(machine).joinSettled()
        state.closeTab(metamodel).joinSettled()
        assertNull(state.outline.state.value.path)
        assertNull(state.statusBar.state.value.language)
    }

    @Test
    fun `an edit marks the tab dirty and saving clears it`() = scenario {
        openEditor(machine)
        edit(machine)
        assertTrue(state.tabs.tabs.value.single().isDirty)

        state.saveActive().joinSettled()
        assertFalse(state.tabs.tabs.value.single().isDirty)
        assertTrue(Files.readString(machine).startsWith("// edited"))

        openEditor(model)
        edit(model)
        edit(machine)
        state.saveAll().joinSettled()
        assertTrue(state.tabs.tabs.value.none { it.isDirty })
        assertTrue(Files.readString(model).startsWith("// edited"))
    }

    @Test
    fun `closing a clean tab closes it without a dialog and activates the neighbour`() = scenario {
        openEditor(machine)
        openEditor(model)
        openEditor(metamodel)
        state.tabs.activate(model)

        state.closeTab(model).joinSettled()
        assertEquals(listOf(machine, metamodel), state.tabs.tabs.value.map { it.path })
        assertEquals(metamodel, state.tabs.active.value, "the right neighbour")
        assertNull(workspace.documents.get(model), "the document is closed")

        state.closeActiveTab().joinSettled()
        assertEquals(machine, state.tabs.active.value, "the left neighbour of the last tab")
    }

    @Test
    fun `closing a dirty tab asks - Cancel keeps it, Save writes it, Discard drops the changes`() = scenario {
        openEditor(machine)
        edit(machine)

        state.closeTab(machine)
        answer(DialogRequest.UnsavedChanges::class, DialogResponse.Cancel)
        assertNotNull(state.tabs.editorFor(machine))

        state.closeTab(machine)
        val asked = answer(DialogRequest.UnsavedChanges::class, DialogResponse.Save)
        assertEquals(listOf(machine), asked.paths)
        assertNull(state.tabs.editorFor(machine))
        assertTrue(Files.readString(machine).startsWith("// edited"))

        openEditor(model)
        edit(model)
        state.closeTab(model)
        answer(DialogRequest.UnsavedChanges::class, DialogResponse.Discard)
        assertTrue(state.tabs.tabs.value.isEmpty())
        assertFalse(Files.readString(model).startsWith("// edited"))
    }

    @Test
    fun `two close requests for a dirty tab show one dialog`() = scenario {
        openEditor(machine)
        edit(machine)

        val first = state.closeTab(machine)
        val second = state.closeTab(machine)
        answer(DialogRequest.UnsavedChanges::class, DialogResponse.Discard)

        assertNoDialog()
        assertTrue(first.isCompleted && second.isCompleted)
        assertTrue(state.tabs.tabs.value.isEmpty())
    }

    @Test
    fun `two close requests for a dirty tab answered Cancel show one dialog and keep the tab`() = scenario {
        openEditor(machine)
        edit(machine)

        val first = state.closeTab(machine)
        val second = state.closeTab(machine)
        answer(DialogRequest.UnsavedChanges::class, DialogResponse.Cancel)

        assertNull(state.dialogs.current.value, "no second unsaved-changes dialog")
        assertTrue(first.isCompleted && second.isCompleted)
        assertNotNull(state.tabs.editorFor(machine))
    }

    @Test
    fun `a double Close Tab of the active dirty tab answered Cancel shows one dialog`() = scenario {
        openEditor(machine)
        edit(machine)

        val first = state.closeActiveTab()
        val second = state.closeActiveTab()
        answer(DialogRequest.UnsavedChanges::class, DialogResponse.Cancel)

        assertNull(state.dialogs.current.value, "no second unsaved-changes dialog")
        assertTrue(first.isCompleted && second.isCompleted)
        assertNotNull(state.tabs.editorFor(machine))
    }

    @Test
    fun `a close request after the first one was answered asks again`() = scenario {
        openEditor(machine)
        edit(machine)

        state.closeTab(machine)
        answer(DialogRequest.UnsavedChanges::class, DialogResponse.Cancel)
        val again = state.closeTab(machine)
        answer(DialogRequest.UnsavedChanges::class, DialogResponse.Discard)

        assertTrue(again.isCompleted)
        assertTrue(state.tabs.tabs.value.isEmpty())
    }

    @Test
    fun `an external change of a dirty file shows the conflict and Reload takes the disk state`() = scenario {
        openEditor(machine)
        edit(machine)
        Files.writeString(machine, "STATEMACHINE ATTACHED TO \"Robot\" { STATES { } TRANSITIONS { } }\n")
        watcher.report(machine, FileChangeKind.MODIFIED)
        settle()
        assertEquals(ExternalChangeKind.MODIFIED, state.tabs.tabs.value.single().conflict)

        state.resolveConflict(machine, ConflictResolution.RELOAD).joinSettled()
        assertNull(state.tabs.tabs.value.single().conflict)
        assertFalse(state.tabs.tabs.value.single().isDirty)
        assertEquals(Files.readString(machine), document(machine).session.text.snapshot.value.text)
    }

    @Test
    fun `Keep mine keeps the buffer and clears the conflict`() = scenario {
        openEditor(machine)
        edit(machine)
        Files.writeString(machine, "changed on disk\n")
        watcher.report(machine, FileChangeKind.MODIFIED)
        settle()

        state.resolveConflict(machine, ConflictResolution.KEEP_MINE).joinSettled()
        val tab = state.tabs.tabs.value.single()
        assertNull(tab.conflict)
        assertTrue(tab.isDirty)
        assertTrue(document(machine).session.text.snapshot.value.text.startsWith("// edited"))
    }

    @Test
    fun `Close on a file deleted on disk closes the tab without saving`() = scenario {
        openEditor(machine)
        edit(machine)
        Files.delete(machine)
        watcher.report(machine, FileChangeKind.DELETED)
        settle()
        assertEquals(ExternalChangeKind.DELETED, state.tabs.tabs.value.single().conflict)

        state.resolveConflict(machine, ConflictResolution.RELOAD).joinSettled()
        assertTrue(state.tabs.tabs.value.isEmpty())
        assertFalse(Files.exists(machine), "nothing was saved")
        assertNoDialog()
    }

    @Test
    fun `a failing reload becomes an error dialog and keeps the conflict`() = scenario {
        openEditor(machine)
        edit(machine)
        Files.writeString(machine, "changed on disk\n")
        watcher.report(machine, FileChangeKind.MODIFIED)
        settle()
        val conflict = state.tabs.tabs.value.single().conflict
        assertNotNull(conflict)
        fileStore.failingReads.add(machine)

        val job = state.resolveConflict(machine, ConflictResolution.RELOAD)
        answer(DialogRequest.Error::class, DialogResponse.Confirm)
        assertTrue(job.isCompleted)
        assertEquals(conflict, state.tabs.tabs.value.single().conflict)
    }

    @Test
    fun `a failing save becomes an error dialog and keeps the tab dirty`() = scenario {
        openEditor(machine)
        edit(machine)
        fileStore.failingWrites.add(machine)

        val job = state.saveActive()
        val error = answer(DialogRequest.Error::class, DialogResponse.Confirm)
        assertTrue(job.isCompleted)
        assertTrue(error.message.isNotBlank())
        assertTrue(state.tabs.tabs.value.single().isDirty)

        state.closeTab(machine)
        answer(DialogRequest.UnsavedChanges::class, DialogResponse.Save)
        answer(DialogRequest.Error::class, DialogResponse.Confirm)
        assertNotNull(state.tabs.editorFor(machine), "a failed save keeps the tab open")
    }

    @Test
    fun `opening a file without a folder or a missing file shows an error`() = runTest(timeout = 60.seconds) {
        Examples.copyTo(root)
        withWorkbench(dir.resolve("settings")) {
            state.openFile(machine)
            answer(DialogRequest.Error::class, DialogResponse.Confirm)
            open(root)
            state.openFile(example.resolve("missing.kstates"))
            answer(DialogRequest.Error::class, DialogResponse.Confirm)
            assertTrue(state.tabs.tabs.value.isEmpty())
        }
    }
}
