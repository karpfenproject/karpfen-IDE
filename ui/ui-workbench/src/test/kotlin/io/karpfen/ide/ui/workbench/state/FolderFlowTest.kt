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
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.settings.ProjectSettings
import io.karpfen.ide.ui.workbench.settings.ProjectSettingsFile
import io.karpfen.ide.ui.workbench.testing.assertSamePath
import io.karpfen.ide.ui.workbench.testing.withWorkbench
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Tests opening folders. It covers the workspace, the tree, the problems, the recent folders, the window title and the
 * unsaved-changes flow.
 */
class FolderFlowTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("examples").toAbsolutePath().normalize()
    private val settingsDirectory: Path get() = dir.resolve("settings")

    @Test
    fun `opening a folder binds the tree, the problems and the title and records the folder`() = runTest(timeout = 60.seconds) {
        Examples.copyTo(root)
        withWorkbench(settingsDirectory) {
            assertEquals("karpfen IDE", state.windowTitle.value)
            open(root)

            assertSamePath(root, state.workspace.value?.root)
            assertTrue(state.languageServices.value != null)
            assertEquals("examples - karpfen IDE", state.windowTitle.value)
            assertSamePath(root, state.projectTree.tree.value?.root?.path)
            assertEquals(setOf(root), state.projectTree.expanded.value)
            assertEquals(listOf(root.toString()), state.settings.value.recentFolders)
            assertEquals(0, state.statusBar.state.value.errorCount, "the examples have no errors")
            assertFalse(state.statusBar.state.value.isAnalyzing)
            assertTrue(Files.exists(settingsDirectory.resolve("settings.json")), "the recent folder is persisted")
        }
    }

    @Test
    fun `switching folders asks about unsaved changes and Cancel keeps the folder`() = runTest(timeout = 60.seconds) {
        Examples.copyTo(root)
        val other = Files.createDirectories(dir.resolve("other")).toAbsolutePath().normalize()
        val file = root.resolve("karpfen-runtime/single_robot_example/cleaning_robot.kmeta")
        withWorkbench(settingsDirectory) {
            open(root)
            val editor = openEditor(file)
            editor.session.text.apply(listOf(TextEdit.insert(0, "// x\n")), EditOrigin.Typing)
            settle()

            val cancelled = state.openFolder(other)
            answer(DialogRequest.UnsavedChanges::class, DialogResponse.Cancel)
            assertTrue(cancelled.isCompleted)
            assertSamePath(root, workspace.root)
            assertNotNull(state.tabs.editorFor(file))

            val discarded = state.openFolder(other)
            answer(DialogRequest.UnsavedChanges::class, DialogResponse.Discard)
            assertTrue(discarded.isCompleted)
            assertSamePath(other, workspace.root)
            assertTrue(state.tabs.tabs.value.isEmpty(), "the tabs of the old folder are closed")
            assertNull(state.outline.state.value.path)
            assertFalse(Files.readString(file).startsWith("// x"), "discarded changes are not written")
            assertEquals(listOf(other.toString(), root.toString()), state.settings.value.recentFolders)
        }
    }

    @Test
    fun `a missing folder becomes an error dialog`() = runTest(timeout = 60.seconds) {
        withWorkbench(settingsDirectory) {
            val job = state.openFolder(dir.resolve("missing"))
            val error = answer(DialogRequest.Error::class, DialogResponse.Confirm)
            assertEquals("Cannot open folder", error.title)
            assertTrue(job.isCompleted)
            assertNull(state.workspace.value)
        }
    }

    @Test
    fun `a folder opens with the files that were open in it, and a folder that was only looked at gets no project file`() =
        runTest(timeout = 60.seconds) {
            Examples.copyTo(root)
            val other = Files.createDirectories(dir.resolve("other")).toAbsolutePath().normalize()
            val machine = root.resolve("karpfen-runtime/single_robot_example/cleaning_robot.kstates")
            val meta = root.resolve("karpfen-runtime/single_robot_example/cleaning_robot.kmeta")
            withWorkbench(settingsDirectory) {
                state.start()
                open(root)
                openEditor(machine)
                openEditor(meta)
                state.tabs.activate(machine)
                settle()

                open(other)
                settle()

                assertEquals(emptyList(), state.tabs.tabs.value.map { it.path })
                val stored = ProjectSettingsFile.parse(Files.readString(root.resolve(ProjectSettings.FILE_NAME)))
                assertEquals(
                    listOf(
                        "karpfen-runtime/single_robot_example/cleaning_robot.kstates",
                        "karpfen-runtime/single_robot_example/cleaning_robot.kmeta",
                    ),
                    stored.openFiles,
                )
                assertEquals("karpfen-runtime/single_robot_example/cleaning_robot.kstates", stored.activeFile)

                Files.delete(meta)
                open(root)
                settle()

                assertEquals(listOf(machine), state.tabs.tabs.value.map { it.path }, "the deleted file is skipped")
                assertEquals(machine, state.tabs.active.value)
                assertTrue(Files.notExists(other.resolve(ProjectSettings.FILE_NAME)), "nothing changed in the other folder")
            }
        }

    @Test
    fun `a recent folder that no longer exists keeps the open folder and leaves the recent folders`() = runTest(timeout = 60.seconds) {
        Examples.copyTo(root)
        val stale = Files.createDirectories(dir.resolve("stale")).toAbsolutePath().normalize()
        val file = root.resolve("karpfen-runtime/single_robot_example/cleaning_robot.kmeta")
        withWorkbench(settingsDirectory) {
            open(stale)
            open(root)
            val editor = openEditor(file)
            editor.session.text.apply(listOf(TextEdit.insert(0, "// x\n")), EditOrigin.Typing)
            settle()
            Files.delete(stale)

            val job = state.openFolder(stale)
            val error = answer(DialogRequest.Error::class, DialogResponse.Confirm)

            assertEquals("Cannot open folder", error.title)
            assertTrue(job.isCompleted)
            assertSamePath(root, workspace.root)
            assertNotNull(state.tabs.editorFor(file), "the open folder and its unsaved changes are kept")
            assertTrue(state.tabs.tabs.value.single().isDirty)
            assertEquals(listOf(root.toString()), state.settings.value.recentFolders, "the stale entry is removed")
        }
    }

    @Test
    fun `a failing folder dialog becomes an error dialog and Save and Exit still run`() = runTest(timeout = 60.seconds) {
        Examples.copyTo(root)
        val file = root.resolve("karpfen-runtime/single_robot_example/cleaning_robot.kmeta")
        withWorkbench(settingsDirectory) {
            open(root)
            val editor = openEditor(file)
            editor.session.text.apply(listOf(TextEdit.insert(0, "// mine\n")), EditOrigin.Typing)
            settle()
            folderChooser.failure = IOException("The folder dialog failed: Win32ShellFolder2")

            val choose = state.chooseAndOpenFolder()
            val error = answer(DialogRequest.Error::class, DialogResponse.Confirm)

            assertEquals("Cannot choose a folder", error.title)
            assertEquals("The folder dialog failed: Win32ShellFolder2", error.message)
            assertTrue(choose.isCompleted)
            state.saveActive().joinSettled()
            assertTrue(Files.readString(file).startsWith("// mine"), "Save ran after the failed dialog")
            assertTrue(state.requestExit(), "exit is not blocked")
        }
    }

    @Test
    fun `a folder that cannot be listed is an error dialog and never recent`() = runTest(timeout = 60.seconds) {
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"), "needs POSIX permissions")
        Examples.copyTo(root)
        val locked = Files.createDirectories(dir.resolve("locked")).toAbsolutePath().normalize()
        Files.setPosixFilePermissions(locked, emptySet())
        try {
            assumeFalse(Files.isReadable(locked), "the user can read any directory (root)")
            withWorkbench(settingsDirectory) {
                open(root)

                val job = state.openFolder(locked)
                val error = answer(DialogRequest.Error::class, DialogResponse.Confirm)

                assertEquals("Cannot open folder", error.title)
                assertTrue("cannot be read" in error.message, error.message)
                assertTrue(job.isCompleted)
                assertSamePath(root, workspace.root)
                assertEquals(listOf(root.toString()), state.settings.value.recentFolders)
            }
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `choose and open folder uses the folder chooser`() = runTest(timeout = 60.seconds) {
        Examples.copyTo(root)
        withWorkbench(settingsDirectory) {
            folderChooser.answer = null
            state.chooseAndOpenFolder().joinSettled()
            assertNull(state.workspace.value, "cancelled")

            folderChooser.answer = root
            state.chooseAndOpenFolder().joinSettled()
            assertSamePath(root, state.workspace.value?.root)
            assertEquals(2, folderChooser.calls.size)
        }
    }
}
