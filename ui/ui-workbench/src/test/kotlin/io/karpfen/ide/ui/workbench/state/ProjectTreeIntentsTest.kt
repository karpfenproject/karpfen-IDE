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
 * Tests the intents of the mediator for the project tree. Opening a node toggles a directory and opens a file. The
 * tree's state decides which, not the view. The context menu commands each run through their intent.
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
