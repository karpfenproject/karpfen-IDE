package io.karpfen.ide.app

import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.ui.editor.model.action.defaultEditorKeymap
import io.karpfen.ide.ui.foundation.command.Command
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.dialog.DialogState
import io.karpfen.ide.ui.workbench.files.DeleteOutcome
import io.karpfen.ide.ui.workbench.files.ProjectFileOperations
import io.karpfen.ide.ui.workbench.layout.LayoutState
import io.karpfen.ide.ui.workbench.outline.OutlineState
import io.karpfen.ide.ui.workbench.problems.ProblemsState
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeState
import io.karpfen.ide.ui.workbench.settings.IdeSettings
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import io.karpfen.ide.ui.workbench.state.WorkbenchServices
import io.karpfen.ide.ui.workbench.state.WorkbenchState
import io.karpfen.ide.ui.workbench.statusbar.StatusBarState
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import io.karpfen.ide.ui.workbench.templates.FileTemplates
import io.karpfen.ide.ui.workbench.templates.TemplateContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path
import kotlin.time.Duration

/**
 * A scripted [WorkbenchState] (WP-WORKBENCH may not be merged, so app tests never run the real one): records the
 * calls of the composition root, registers one command, and lets a test decide how long `openFolder` takes, whether
 * `start` fails and how `requestExit` answers. The sub-states are the real classes, only constructed (their members
 * are never called).
 */
internal class FakeWorkbenchState(val scope: CoroutineScope, val services: WorkbenchServices) : WorkbenchState {
    val calls = mutableListOf<String>()
    var registry: CommandRegistry? = null
    var openFolderTime: Duration = Duration.ZERO
    var openedFolder: Path? = null
    var startFailure: Exception? = null

    /** `requestExit` waits for this answer. */
    var exitAnswer = CompletableDeferred(true)

    override val workspace: StateFlow<Workspace?> = MutableStateFlow(null)
    override val languageServices: StateFlow<LanguageServices?> = MutableStateFlow(null)
    override val projectTree: ProjectTreeState = ProjectTreeState()
    override val tabs: EditorTabsState = EditorTabsState(scope)
    override val outline: OutlineState = OutlineState(scope)
    override val problems: ProblemsState = ProblemsState(scope)
    override val statusBar: StatusBarState = StatusBarState(scope)
    override val dialogs: DialogState = DialogState()
    override val layout: LayoutState = LayoutState()
    override val settings: StateFlow<IdeSettings> = MutableStateFlow(IdeSettings())
    override val windowTitle: StateFlow<String> = MutableStateFlow("karpfen IDE")

    override suspend fun start() {
        calls += "start"
        startFailure?.let { throw it }
    }

    override fun openFolder(path: Path): Job {
        calls += "openFolder $path"
        return scope.launch {
            delay(openFolderTime)
            openedFolder = path
        }
    }

    override fun registerCommands(registry: CommandRegistry) {
        calls += "registerCommands"
        this.registry = registry
        registry.register(Command(FAKE_COMMAND_ID, "Fake") {})
    }

    override suspend fun requestExit(): Boolean {
        calls += "requestExit"
        return exitAnswer.await()
    }

    override fun close() {
        calls += "close"
    }

    override fun chooseAndOpenFolder(): Job = unexpected("chooseAndOpenFolder")
    override fun openFile(path: Path): Job = unexpected("openFile")
    override fun navigateTo(location: Location): Job = unexpected("navigateTo")
    override fun createFile(directory: Path, kind: FileTemplateKind): Job = unexpected("createFile")
    override fun rename(path: Path): Job = unexpected("rename")
    override fun delete(path: Path): Job = unexpected("delete")
    override fun revealInFileManager(path: Path): Job = unexpected("revealInFileManager")
    override fun resolveConflict(path: Path, resolution: ConflictResolution): Job = unexpected("resolveConflict")
    override fun saveActive(): Job = unexpected("saveActive")
    override fun saveAll(): Job = unexpected("saveAll")
    override fun closeTab(path: Path): Job = unexpected("closeTab")
    override fun closeActiveTab(): Job = unexpected("closeActiveTab")

    private fun unexpected(intent: String): Nothing = throw AssertionError("the composition root does not call $intent")

    companion object {
        const val FAKE_COMMAND_ID = "fake.command"
    }
}

/**
 * A services factory that counts its calls and builds [WorkbenchServices] of fakes for [platform] (the keymap's
 * platform is the services' platform); none of the collaborators is used by the composition root.
 */
internal class RecordingServicesFactory(platform: HostPlatform, ioDispatcher: CoroutineDispatcher) : () -> WorkbenchServices {
    var calls = 0
        private set

    val services = WorkbenchServices(
        settings = UnusedSettings,
        languages = defaultLanguageRegistry(),
        workspaceFactory = { _, _ -> throw AssertionError("no workspace is opened by the fake state") },
        languageServicesFactory = { throw AssertionError("no language services are created") },
        editorStateFactory = { _, _, _, _, _ -> throw AssertionError("no editor is created") },
        fileOperations = UnusedFileOperations,
        folderChooser = { _, _ -> null },
        templates = UnusedTemplates,
        editorKeymap = defaultEditorKeymap(platform),
        ioDispatcher = ioDispatcher,
    )

    override fun invoke(): WorkbenchServices {
        calls++
        return services
    }

    private object UnusedSettings : SettingsRepository {
        override val settings: StateFlow<IdeSettings> = MutableStateFlow(IdeSettings())
        override suspend fun load(): IdeSettings = throw AssertionError("settings are loaded by the workbench state")
        override suspend fun update(transform: (IdeSettings) -> IdeSettings): IdeSettings = throw AssertionError("unused")
    }

    private object UnusedFileOperations : ProjectFileOperations {
        override val supportsTrash: Boolean = false
        override val supportsRevealInFileManager: Boolean = false
        override val supportsOpenExternally: Boolean = false
        override fun createFile(directory: Path, fileName: String, content: String): Path = throw AssertionError("unused")
        override fun rename(path: Path, newName: String): Path = throw AssertionError("unused")
        override fun delete(path: Path): DeleteOutcome = throw AssertionError("unused")
        override fun revealInFileManager(path: Path) = throw AssertionError("unused")
        override fun openExternally(path: Path) = throw AssertionError("unused")
    }

    private object UnusedTemplates : FileTemplates {
        override fun fileName(kind: FileTemplateKind, baseName: String): String = throw AssertionError("unused")
        override fun content(kind: FileTemplateKind, baseName: String, context: TemplateContext): String =
            throw AssertionError("unused")
    }
}
