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
import io.karpfen.ide.ui.workbench.settings.ProjectSettings
import io.karpfen.ide.ui.workbench.tabs.PaneNode
import io.karpfen.ide.ui.workbench.tabs.SplitOrientation
import io.karpfen.ide.ui.workbench.tabs.SplitSide
import io.karpfen.ide.ui.workbench.testing.TestWorkbench
import io.karpfen.ide.ui.workbench.testing.withWorkbench
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

/**
 * Tests how the editor panes go into the project file and come back from it: the tree with its shares, the tabs and
 * active tab of every pane and the active pane are written when they change, debounced, and restored when the folder
 * opens again. A file of a project with one pane stays as it is, and a file that names files that are gone or holds a
 * tree that cannot be read opens as far as it can be read.
 */
class EditorPanesPersistenceTest {

    @TempDir
    lateinit var dir: Path

    private val settingsDirectory: Path get() = dir.resolve("settings")
    private val root: Path get() = dir.resolve("examples").toAbsolutePath().normalize()
    private val other: Path get() = dir.resolve("other").toAbsolutePath().normalize()
    private val projectFile: Path get() = root.resolve(ProjectSettings.FILE_NAME)
    private val example: Path get() = root.resolve("karpfen-runtime/single_robot_example")
    private val machine: Path get() = example.resolve("cleaning_robot.kstates")
    private val metamodel: Path get() = example.resolve("cleaning_robot.kmeta")
    private val model: Path get() = example.resolve("cleaning_robot.kmodel")

    private fun scenario(block: suspend TestWorkbench.() -> Unit) = runTest(timeout = 60.seconds) {
        Examples.copyTo(root)
        Files.createDirectories(other)
        withWorkbench(settingsDirectory) {
            state.start()
            block()
        }
    }

    /** The name of [path] in the project file. */
    private fun name(path: Path): String = root.relativize(path).joinToString("/")

    /** The lines of the project file that hold the panes. */
    private fun paneLines(): List<String> = Files.readString(projectFile).lines()
        .filter { line -> listOf("split", "open", "active").any { line.startsWith(it) } }

    /** The paths of the tabs of every pane, in focus order. */
    private fun TestWorkbench.paneTabs(): List<List<Path>> = state.tabs.layout.value.panes.map { it.tabs }

    /** The active tab of every pane, in focus order. */
    private fun TestWorkbench.paneActives(): List<Path?> = state.tabs.layout.value.panes.map { it.active }

    /** The index of the active pane in focus order. */
    private fun TestWorkbench.activeIndex(): Int = state.tabs.layout.value.let { layout -> layout.panes.indexOfFirst { it.id == layout.activePane } }

    @Test
    fun `a nested tree of panes is written to the project file and restored when the folder opens again`() = scenario {
        open(root)
        openEditor(model)
        openEditor(machine)
        state.splitActivePane(SplitSide.RIGHT).joinSettled()
        openEditor(metamodel)
        state.splitActivePane(SplitSide.DOWN).joinSettled()
        val outer = assertIs<PaneNode.Split>(state.tabs.layout.value.root)
        state.tabs.resize(outer.id, 0.3f)
        state.tabs.activatePane(state.tabs.layout.value.panes[1].id)
        settle()

        open(other)
        settle()

        val machineName = name(machine)
        val metamodelName = name(metamodel)
        assertEquals(
            listOf(
                "split = row 30 (1, column 50 (2, 3))",
                "open.1 = ${name(model)}",
                "open.1 = $machineName",
                "active.1 = $machineName",
                "open.2 = $machineName",
                "open.2 = $metamodelName",
                "active.2 = $metamodelName",
                "open.3 = $metamodelName",
                "active.3 = $metamodelName",
                "activePane = 2",
            ),
            paneLines(),
        )
        assertEquals(listOf(emptyList()), paneTabs(), "the other folder has no panes of its own")

        open(root)
        settle()

        assertEquals(listOf(listOf(model, machine), listOf(machine, metamodel), listOf(metamodel)), paneTabs())
        assertEquals(listOf(machine, metamodel, metamodel), paneActives())
        assertEquals(1, activeIndex())
        assertEquals(metamodel, state.tabs.active.value, "the commands follow the restored active pane")
        val restored = assertIs<PaneNode.Split>(state.tabs.layout.value.root)
        assertEquals(SplitOrientation.ROW, restored.orientation)
        assertEquals(0.3f, restored.share)
        val inner = assertIs<PaneNode.Split>(restored.second)
        assertEquals(SplitOrientation.COLUMN, inner.orientation)
        assertEquals(0.5f, inner.share)
        assertEquals(1, workspace.documents.openDocuments.value.count { it.path == machine }, "one document for the panes of a file")
    }

    @Test
    fun `the project file of a project with one pane stays as it is when the folder opens and its tabs are restored`() = scenario {
        val stored = "theme = dark\nopen = ${name(machine)}\nopen = ${name(model)}\nactive = ${name(machine)}\n"
        TestWorkbench.write(projectFile, stored)

        open(root)
        settle()

        assertEquals(listOf(listOf(machine, model)), paneTabs())
        assertEquals(machine, state.tabs.active.value)
        assertEquals(stored, Files.readString(projectFile), "restoring writes nothing")

        state.tabs.activate(model)
        settle()
        assertEquals(listOf("open = ${name(machine)}", "open = ${name(model)}", "active = ${name(model)}"), paneLines())
    }

    @Test
    fun `files that are gone are skipped and a pane left without files disappears`() = scenario {
        TestWorkbench.write(
            projectFile,
            """
            split = column 40 (1, row 50 (2, 3))
            open.1 = ${name(machine)}
            open.2 = gone.kstates
            active.2 = gone.kstates
            open.3 = ${name(model)}
            open.3 = also gone.kmeta
            activePane = 2
            """.trimIndent() + "\n",
        )

        open(root)
        settle()

        assertEquals(listOf(listOf(machine), listOf(model)), paneTabs())
        val split = assertIs<PaneNode.Split>(state.tabs.layout.value.root)
        assertEquals(SplitOrientation.COLUMN, split.orientation)
        assertEquals(0.4f, split.share)
        assertEquals(0, activeIndex(), "the active pane is gone, so the first pane is active")
        assertEquals(
            listOf(
                "split = column 40 (1, 2)",
                "open.1 = ${name(machine)}",
                "active.1 = ${name(machine)}",
                "open.2 = ${name(model)}",
                "active.2 = ${name(model)}",
                "activePane = 1",
            ),
            paneLines(),
            "the file is written again with what was restored",
        )
    }

    @Test
    fun `a tree that cannot be read opens one pane with every tab`() = scenario {
        TestWorkbench.write(
            projectFile,
            "split = row 50 (1, 2\nopen.1 = ${name(model)}\nopen.2 = ${name(machine)}\nactive.2 = ${name(machine)}\nactivePane = 2\n",
        )

        open(root)
        settle()

        assertEquals(listOf(listOf(model, machine)), paneTabs())
        assertEquals(machine, state.tabs.active.value, "the active tab of the active pane")

        state.tabs.activate(model)
        settle()

        assertEquals(listOf("open = ${name(model)}", "open = ${name(machine)}", "active = ${name(model)}"), paneLines())
    }

    @Test
    fun `a drag of the border between two panes writes the project file once, after the debounce`() = scenario {
        open(root)
        openEditor(machine)
        state.splitActivePane(SplitSide.RIGHT).joinSettled()
        settle()
        val split = assertIs<PaneNode.Split>(state.tabs.layout.value.root)
        val before = settings.updates

        (1..20).forEach {
            state.tabs.resize(split.id, 0.5f + it / 100f)
            scheduler.advanceTimeBy(20.milliseconds)
            scheduler.runCurrent()
        }
        assertEquals(before, settings.updates, "still dragging")
        settle()

        assertEquals(before + 1, settings.updates)
        assertEquals("split = row 70 (1, 2)", paneLines().first())
    }

    @Test
    fun `closing the second pane of a project goes back to the lines of one pane`() = scenario {
        open(root)
        openEditor(machine)
        state.splitActivePane(SplitSide.DOWN).joinSettled()
        settle()
        assertEquals("split = column 50 (1, 2)", paneLines().first())
        val second = state.tabs.activePane.value

        state.closeTab(second, machine).joinSettled()
        settle()

        assertIs<PaneNode.Pane>(state.tabs.layout.value.root)
        assertEquals(listOf("open = ${name(machine)}", "active = ${name(machine)}"), paneLines())
    }
}
