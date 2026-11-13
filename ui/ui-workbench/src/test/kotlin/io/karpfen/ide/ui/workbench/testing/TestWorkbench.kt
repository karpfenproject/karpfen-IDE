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
package io.karpfen.ide.ui.workbench.testing

import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.services.defaultLanguageServices
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.WorkspaceFactory
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.runtime.protocol.RuntimeClient
import io.karpfen.ide.runtime.protocol.RuntimeClientOptions
import io.karpfen.ide.runtime.protocol.fake.FakeRuntime
import io.karpfen.ide.runtime.session.RuntimeSessionOptions
import io.karpfen.ide.runtime.session.RuntimeSessions
import io.karpfen.ide.ui.editor.model.action.defaultEditorKeymap
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.output.RingOutputBuffer
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import io.karpfen.ide.ui.workbench.state.LanguageServicesFactory
import io.karpfen.ide.ui.workbench.state.WorkbenchServices
import io.karpfen.ide.ui.workbench.state.WorkbenchState
import io.karpfen.ide.ui.workbench.templates.FileTemplates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource

/**
 * A [WorkbenchState] for tests. It is wired like the production services, but runs on the virtual time of
 * [scheduler]. Every dispatcher is the same test dispatcher. That covers the mediator's scope, the workspace's I/O and
 * analysis, the runtime sessions, the editors' background work and the workbench's I/O. [settle] therefore runs
 * everything that is due.
 *
 * The collaborators are these:
 *
 * - a [FakeFileWatcher], a [ControllableFileStore], a [RecordingFileOperations] and a [FakeFolderChooser].
 * - JSON settings in [settingsDirectory] with a fixed clock. [settings] counts their writes.
 * - real workspaces, language services and editors with the macOS keymap.
 * - a [RingOutputBuffer] as the output of the language tools.
 * - [FakeRuntimeHosts] for the runtime hosts.
 * - real runtime sessions whose clients reach [fakeRuntime]. With [scriptSessions] set, the workbench uses
 *   [scriptedSessions] instead, which launch scriptable fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestWorkbench(
    val scheduler: TestCoroutineScheduler,
    val settingsDirectory: Path,
    val fileOperations: RecordingFileOperations = RecordingFileOperations(),
    languageServices: (Workspace) -> LanguageServices = { defaultLanguageServices(it.analysis, files = it.files) },
    scriptSessions: Boolean = false,
) {
    /** The one test dispatcher on [scheduler] that runs all of the workbench's work. */
    val dispatcher: CoroutineDispatcher = StandardTestDispatcher(scheduler)

    /** The scope of the workbench state. */
    val scope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob())

    /** The file watcher. Tests deliver changes on disk through it. */
    val watcher = FakeFileWatcher()

    /** Answers the folder dialog. */
    val folderChooser = FakeFolderChooser()

    /** The workspaces' file store, which fails reads and writes on demand. */
    val fileStore = ControllableFileStore()

    /** The clipboard every editor shares. */
    val clipboard = TestClipboard()

    /** The time source of the documents' undo coalescing. */
    val time = TestTimeSource()

    /** The JSON settings in [settingsDirectory], counting their writes. */
    val settings = CountingSettingsRepository(SettingsRepository(settingsDirectory, dispatcher, FIXED_CLOCK))

    /** The one editor keymap of the workbench. */
    val keymap = defaultEditorKeymap(HostPlatform.MAC_OS)

    /**
     * The options of every workspace. Each workspace watches its files, runs on [dispatcher] and coalesces undo on
     * [time].
     */
    val options = WorkspaceOptions(
        watch = true,
        ioDispatcher = dispatcher,
        analysisDispatcher = dispatcher,
        sessionConfig = DocumentSessionConfig(tokenizeDispatcher = dispatcher, parseDispatcher = dispatcher),
        timeSource = time,
    )

    /** The built-in languages. */
    val languages = defaultLanguageRegistry()

    /**
     * The output of the language tools that the Language Tools Output shows. Tests append to it as the logging would.
     */
    val languageToolsOutput = RingOutputBuffer()

    /** The runtime that the clients of the sessions reach. It answers only what a test programs. */
    val fakeRuntime = FakeRuntime(scope)

    /** The hosts of the opened workspaces. */
    val runtimeHosts = FakeRuntimeHosts()

    /** The real sessions, on [dispatcher] and the virtual time of [scheduler], with a seeded random. */
    val realSessions: RuntimeSessions = RuntimeSessions(
        RuntimeSessionOptions(dispatcher = dispatcher, timeSource = scheduler.timeSource, random = Random(SESSION_SEED)),
    )

    /** Sessions that prepare like [realSessions] and launch scriptable fakes. */
    val scriptedSessions = ScriptedRuntimeSessions(realSessions)

    /** The workbench services wired from the test values above. */
    val services = WorkbenchServices(
        settings = settings,
        workspaceFactory = WorkspaceFactory { root, workspaceScope ->
            Workspace(root, workspaceScope, options, languages, fileStore = fileStore, fileWatcher = watcher)
        },
        languageServicesFactory = LanguageServicesFactory(languageServices),
        editorStateFactory = { session, services, editorScope, editorSettings, keymap ->
            CodeEditorState(session, services, editorScope, clipboard, editorSettings, keymap = keymap, backgroundDispatcher = dispatcher)
        },
        fileOperations = fileOperations,
        folderChooser = folderChooser,
        templates = FileTemplates(),
        editorKeymap = keymap,
        ioDispatcher = dispatcher,
        languageToolsOutput = languageToolsOutput,
        runtimeSessions = if (scriptSessions) scriptedSessions else realSessions,
        runtimeHosts = runtimeHosts,
        runtimeClients = { endpoint ->
            val options = RuntimeClientOptions(dispatcher = dispatcher, timeSource = scheduler.timeSource)
            RuntimeClient(endpoint, options, fakeRuntime.transports())
        },
    )

    /** The workbench state under test. */
    val state: WorkbenchState = WorkbenchState(scope, services)

    /** The current workspace. Fails when no folder is open. */
    val workspace: Workspace get() = assertNotNull(state.workspace.value, "no folder is open")

    /** Runs everything due on the virtual clock. Compose UI tests run their frames on a clock of their own. */
    fun settle() = scheduler.advanceUntilIdle()

    /** Opens [root] and settles, including the first analysis. */
    suspend fun open(root: Path) {
        state.openFolder(root).joinSettled()
        workspace.awaitAnalysis()
        settle()
    }

    /** Opens [path] in a tab and returns its editor. */
    fun openEditor(path: Path): CodeEditorState {
        state.openFile(path).joinSettled()
        return assertNotNull(state.tabs.editorFor(path.toAbsolutePath().normalize()), "no tab for $path")
    }

    /** The open document of [path]. */
    fun document(path: Path): OpenDocument = assertNotNull(workspace.documents.get(path.toAbsolutePath().normalize()))

    /** Settles and asserts the job completed. */
    fun Job.joinSettled() {
        settle()
        assertTrue(isCompleted, "the intent did not complete (waiting for a dialog: ${state.dialogs.current.value})")
    }

    /** Settles, asserts that a dialog of [type] is shown, answers it with [response] and settles again. */
    fun <T : DialogRequest<*>> answer(type: KClass<T>, response: DialogResponse): T {
        settle()
        val current = state.dialogs.current.value
        assertTrue(type.isInstance(current), "expected a ${type.simpleName}, was $current")
        state.dialogs.respond(response)
        settle()
        @Suppress("UNCHECKED_CAST")
        return current as T
    }

    /**
     * Settles, asserts that the run dialog is shown, answers it with the [bridges] ticked by their positions and the
     * capture box set to [capture], and settles again. Returns the dialog as it was shown.
     */
    fun answerRun(bridges: Set<Int> = emptySet(), capture: Boolean = false): DialogRequest.ConfigureRun =
        answer(DialogRequest.ConfigureRun::class, DialogResponse.RunWith(bridges, capture))

    /** Asserts that no dialog is shown. */
    fun assertNoDialog() {
        settle()
        assertNull(state.dialogs.current.value, "unexpected dialog")
    }

    /** Closes the workbench and cancels its scope. */
    fun close() {
        state.close()
        scope.cancel()
    }

    companion object {
        /** The clock of the settings backups. */
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-21T08:30:00Z"), ZoneOffset.UTC)

        /** The seed of the sessions' random, so the client ids are the same in every run. */
        const val SESSION_SEED: Int = 7

        /** Writes [text] to [path], creating its directories, and returns [path]. */
        fun write(path: Path, text: String): Path {
            Files.createDirectories(path.parent)
            return Files.writeString(path, text)
        }
    }
}

/** Runs [block] with a [TestWorkbench] and closes it afterwards. */
suspend fun TestScope.withWorkbench(
    settingsDirectory: Path,
    fileOperations: RecordingFileOperations = RecordingFileOperations(),
    scriptSessions: Boolean = false,
    block: suspend TestWorkbench.() -> Unit,
) {
    val workbench = TestWorkbench(testScheduler, settingsDirectory, fileOperations, scriptSessions = scriptSessions)
    try {
        workbench.block()
    } finally {
        workbench.close()
    }
}

/** Asserts equality of two paths after normalization. */
fun assertSamePath(expected: Path, actual: Path?) =
    assertEquals(expected.toAbsolutePath().normalize(), actual?.toAbsolutePath()?.normalize())
