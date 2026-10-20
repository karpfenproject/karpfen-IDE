package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.testing.RecordingFileOperations
import io.karpfen.ide.ui.workbench.testing.TestWorkbench
import io.karpfen.ide.ui.workbench.testing.withWorkbench
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Rename and delete from the project tree (spec §8, decision 42): open, dirty and directory cases. */
class RenameDeleteFlowTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()
    private val robot: Path get() = root.resolve("karpfen-runtime/single_robot_example")
    private val renamedRobot: Path get() = root.resolve("karpfen-runtime/robot")
    private val machine: Path get() = robot.resolve("cleaning_robot.kstates")
    private val model: Path get() = robot.resolve("cleaning_robot.kmodel")
    private val events: Path get() = robot.resolve("EVENTS.kmeta")
    private val other: Path get() = root.resolve("karpfen-dsl-tools/metamodel_dsl_example.kmeta")

    private fun scenario(fileOperations: RecordingFileOperations = RecordingFileOperations(), block: suspend TestWorkbench.() -> Unit) =
        runTest(timeout = 60.seconds) {
            Examples.copyTo(root)
            withWorkbench(dir.resolve("settings"), fileOperations) {
                open(root)
                block()
            }
        }

    private fun TestWorkbench.edit(path: Path) {
        document(path).session.text.apply(listOf(TextEdit.insert(0, "// mine\n")), EditOrigin.Typing)
        settle()
    }

    /** Tabs: other, machine (dirty, active), model, events. */
    private fun TestWorkbench.openTabs() {
        openEditor(other)
        openEditor(machine)
        openEditor(model)
        openEditor(events)
        state.tabs.activate(machine)
        edit(machine)
    }

    private fun TestWorkbench.renameRobotDirectory(answer: DialogResponse) {
        val job = state.rename(robot)
        answer(DialogRequest.Rename::class, DialogResponse.Text("robot"))
        val asked = answer(DialogRequest.UnsavedChanges::class, answer)
        assertEquals(listOf(machine), asked.paths, "only the dirty document is listed")
        assertTrue(job.isCompleted)
    }

    @Test
    fun `renaming an open file reopens it under the new name in its tab position`() = scenario {
        openEditor(machine)
        openEditor(model)
        openEditor(events)
        state.tabs.activate(model)

        state.rename(model)
        val asked = answer(DialogRequest.Rename::class, DialogResponse.Text("room.kmodel"))
        assertEquals(model, asked.path)

        val renamed = robot.resolve("room.kmodel")
        assertEquals(listOf(machine, renamed, events), state.tabs.tabs.value.map { it.path })
        assertEquals(renamed, state.tabs.active.value)
        assertNull(workspace.documents.get(model))
        assertNotNull(workspace.documents.get(renamed))
        assertTrue(Files.exists(renamed) && !Files.exists(model))
        assertEquals(renamed, state.projectTree.selected.value)
    }

    @Test
    fun `renaming a directory with open documents - Save saves and reopens them in place`() = scenario {
        openTabs()
        renameRobotDirectory(DialogResponse.Save)

        val moved = listOf(other) + listOf("cleaning_robot.kstates", "cleaning_robot.kmodel", "EVENTS.kmeta").map(renamedRobot::resolve)
        assertEquals(moved, state.tabs.tabs.value.map { it.path })
        assertEquals(moved[1], state.tabs.active.value, "the active tab stays active")
        assertTrue(Files.readString(moved[1]).startsWith("// mine"), "saved before the rename")
        assertFalse(state.tabs.tabs.value[1].isDirty)
        assertFalse(Files.exists(robot))
    }

    @Test
    fun `renaming a directory with open documents - Discard drops the changes and reopens them in place`() = scenario {
        openTabs()
        renameRobotDirectory(DialogResponse.Discard)

        val renamedMachine = renamedRobot.resolve("cleaning_robot.kstates")
        val moved = listOf(other, renamedMachine) + listOf("cleaning_robot.kmodel", "EVENTS.kmeta").map(renamedRobot::resolve)
        assertEquals(moved, state.tabs.tabs.value.map { it.path })
        assertEquals(renamedMachine, state.tabs.active.value)
        assertFalse(Files.readString(renamedMachine).startsWith("// mine"))
    }

    @Test
    fun `renaming a directory with open documents - Cancel changes nothing`() = scenario {
        openTabs()
        val before = state.tabs.tabs.value
        renameRobotDirectory(DialogResponse.Cancel)

        assertEquals(before, state.tabs.tabs.value)
        assertEquals(machine, state.tabs.active.value)
        assertTrue(Files.exists(robot))
        assertTrue(document(machine).session.text.snapshot.value.text.startsWith("// mine"))
    }

    @Test
    fun `a failed rename shows the reason and reopens the documents under their old paths`() = scenario {
        openEditor(machine)
        openEditor(model)
        fileOperations.failNext = IOException("the target exists")

        state.rename(machine)
        answer(DialogRequest.Rename::class, DialogResponse.Text("x.kstates"))
        val error = answer(DialogRequest.Error::class, DialogResponse.Confirm)
        assertTrue(error.message.contains("the target exists"))
        assertEquals(listOf(machine, model), state.tabs.tabs.value.map { it.path })
        assertEquals(model, state.tabs.active.value)
    }

    @Test
    fun `cancelling or keeping the name renames nothing`() = scenario {
        state.rename(machine)
        answer(DialogRequest.Rename::class, DialogResponse.Cancel)
        state.rename(machine)
        answer(DialogRequest.Rename::class, DialogResponse.Text("cleaning_robot.kstates"))
        assertTrue(Files.exists(machine))
        assertNoDialog()
    }

    @Test
    fun `deleting an open file confirms, closes its tab without saving and deletes it`() =
        scenario(RecordingFileOperations(supportsTrash = true)) {
        openEditor(machine)
        openEditor(model)
        edit(machine)

        state.delete(machine)
        val asked = answer(DialogRequest.ConfirmDelete::class, DialogResponse.Cancel)
        assertTrue(asked.toTrash)
        assertTrue(Files.exists(machine))

        state.delete(machine)
        answer(DialogRequest.ConfirmDelete::class, DialogResponse.Confirm)
        assertFalse(Files.exists(machine))
        assertEquals(listOf(machine), fileOperations.trashed)
        assertEquals(listOf(model), state.tabs.tabs.value.map { it.path })
        assertNull(state.projectTree.tree.value?.find(machine), "the workspace was refreshed")
        assertNoDialog()
    }

    @Test
    fun `deleting a directory closes the tabs below it`() = scenario {
        openEditor(other)
        openEditor(machine)
        state.delete(robot)
        val asked = answer(DialogRequest.ConfirmDelete::class, DialogResponse.Confirm)
        assertFalse(asked.toTrash)
        assertEquals(listOf(other), state.tabs.tabs.value.map { it.path })
        assertFalse(Files.exists(robot))
    }

    @Test
    fun `a failed delete becomes an error dialog`() = scenario {
        fileOperations.failNext = IOException("permission denied")
        state.delete(machine)
        answer(DialogRequest.ConfirmDelete::class, DialogResponse.Confirm)
        val error = answer(DialogRequest.Error::class, DialogResponse.Confirm)
        assertTrue(error.message.contains("permission denied"))
        assertTrue(Files.exists(machine))
    }

    @Test
    fun `reveal in the file manager goes to the port`() = scenario {
        state.revealInFileManager(machine).joinSettled()
        assertEquals(listOf(machine), fileOperations.revealed)
    }
}
