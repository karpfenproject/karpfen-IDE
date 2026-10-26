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
package io.karpfen.ide.ui.workbench.e2e

import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.defaultWorkbenchServices
import io.karpfen.ide.ui.workbench.output.RingOutputBuffer
import io.karpfen.ide.ui.workbench.state.WorkbenchServices
import io.karpfen.ide.ui.workbench.state.WorkbenchState
import io.karpfen.ide.ui.workbench.testing.FakeFileWatcher
import io.karpfen.ide.ui.workbench.testing.FakeFolderChooser
import io.karpfen.ide.ui.workbench.testing.RecordingFileOperations
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource

/**
 * The workbench exactly as the application wires it — `defaultWorkbenchServices(…)`, whose time-, file-system- and
 * toolkit-dependent parameters are its test seams — with the built-in languages, real workspaces, the default language
 * services, real editors sharing the (headless, in-memory) system clipboard, the macOS keymap and the built-in
 * templates — but with test values for everything time-, thread-, file-system- and toolkit-dependent: one test
 * dispatcher on [scheduler] for the mediator, the workspace, the sessions, the editors' background work and the
 * workbench's I/O; a [TestTimeSource] for undo coalescing; a [FakeFileWatcher]; a fixed clock; recording file
 * operations and a fake folder chooser; settings in [settingsDirectory].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductionWorkbench(val scheduler: TestCoroutineScheduler, settingsDirectory: Path) : AutoCloseable {
    /** The one test dispatcher on [scheduler] that runs all of the workbench's work. */
    val dispatcher: CoroutineDispatcher = StandardTestDispatcher(scheduler)

    /** The scope of the workbench state. */
    val scope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob())

    /** The file watcher; tests deliver external changes through it. */
    val watcher = FakeFileWatcher()

    /** Records the desktop file operations instead of running them. */
    val fileOperations = RecordingFileOperations()

    /** Answers the folder dialog. */
    val folderChooser = FakeFolderChooser()

    /** The time source of the documents' undo coalescing. */
    val time = TestTimeSource()

    /** The output of the language tools; tests append to it as the application's logging would. */
    val languageToolsOutput = RingOutputBuffer()

    /** The production wiring with the test values above. */
    val services: WorkbenchServices = defaultWorkbenchServices(
        languageToolsOutput = languageToolsOutput,
        settingsDirectory = settingsDirectory,
        platform = HostPlatform.MAC_OS,
        backgroundDispatcher = dispatcher,
        ioDispatcher = dispatcher,
        workspaceOptions = WorkspaceOptions(
            ioDispatcher = dispatcher,
            analysisDispatcher = dispatcher,
            sessionConfig = DocumentSessionConfig(tokenizeDispatcher = dispatcher, parseDispatcher = dispatcher),
            timeSource = time,
        ),
        fileWatcher = watcher,
        clock = FIXED_CLOCK,
        fileOperations = fileOperations,
        folderChooser = folderChooser,
    )

    /** The workbench state under test. */
    val state: WorkbenchState = WorkbenchState(scope, services)

    /** The current workspace (fails without one). */
    val workspace: Workspace get() = assertNotNull(state.workspace.value, "no folder is open")

    /** Runs everything due on the virtual clock: debounced parses, file events and analyses included. */
    fun settle() = scheduler.advanceUntilIdle()

    /** Starts the state, opens [root] and settles, including the first analysis. */
    suspend fun open(root: Path) {
        state.start()
        state.openFolder(root).joinSettled()
        workspace.awaitAnalysis()
        settle()
    }

    /** Opens [path] in a tab and returns its editor. */
    fun openEditor(path: Path): CodeEditorState {
        state.openFile(path).joinSettled()
        return assertNotNull(state.tabs.editorFor(normalized(path)), "no tab for $path")
    }

    /** The open document of [path]. */
    fun document(path: Path): OpenDocument = assertNotNull(workspace.documents.get(normalized(path)), "$path is not open")

    /** Settles and asserts the job completed (no dialog is waiting). */
    fun Job.joinSettled() {
        settle()
        assertTrue(isCompleted, "the intent did not complete (waiting for a dialog: ${state.dialogs.current.value})")
    }

    override fun close() {
        state.close()
        scope.cancel()
    }

    companion object {
        /** The clock of the settings backups. */
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-21T08:30:00Z"), ZoneOffset.UTC)

        /** [path] as the workspace names it. */
        fun normalized(path: Path): Path = path.toAbsolutePath().normalize()
    }
}
