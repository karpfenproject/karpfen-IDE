package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeCommand
import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import io.karpfen.ide.ui.workbench.testing.TestWorkbench
import io.karpfen.ide.ui.workbench.testing.withWorkbench
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The project tree's intents of the mediator: opening a node (a directory toggles, a file opens — decided from the
 * tree's state, not by the view) and the context-menu commands, each run through its intent.
 */
class ProjectTreeIntentsTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()
    private val robot: Path get() = root.resolve("karpfen-runtime/single_robot_example")
    private val machine: Path get() = robot.resolve("cleaning_robot.kstates")

    private fun scenario(block: suspend TestWorkbench.() -> Unit) = runTest(timeout = 60.seconds) {
        Examples.copyTo(root)
        withWorkbench(dir.resolve("settings")) {
            open(root)
            block()
        }
    }

    @Test
    fun `opening a directory node toggles it and opens no tab, opening a file node opens it`() = scenario {
        assertTrue(robot !in state.projectTree.expanded.value)

        val toggle = state.openNode(robot)
        assertTrue(toggle.isCompleted, "toggling is immediate")
        assertTrue(robot in state.projectTree.expanded.value)
        state.openNode(robot).joinSettled()
        assertTrue(robot !in state.projectTree.expanded.value)
        assertEquals(emptyList(), state.tabs.tabs.value)

        state.openNode(machine).joinSettled()
        assertEquals(machine, state.tabs.active.value)
    }

    @Test
    fun `context-menu commands run through their intents`() = scenario {
        state.runTreeCommand(ProjectTreeCommand.RevealInFileManager(machine)).joinSettled()
        assertEquals(listOf(machine), fileOperations.revealed)

        state.runTreeCommand(ProjectTreeCommand.NewFile(robot, FileTemplateKind.KSTATES))
        assertEquals(DialogRequest.NewFile(robot, FileTemplateKind.KSTATES), answer(DialogRequest.NewFile::class, DialogResponse.Cancel))
        state.runTreeCommand(ProjectTreeCommand.Rename(machine))
        assertEquals(DialogRequest.Rename(machine), answer(DialogRequest.Rename::class, DialogResponse.Cancel))
        state.runTreeCommand(ProjectTreeCommand.Delete(machine))
        assertEquals(machine, answer(DialogRequest.ConfirmDelete::class, DialogResponse.Cancel).path)
        assertNoDialog()
    }
}
