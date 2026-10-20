package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.WorkspaceFactory
import io.karpfen.ide.ui.editor.model.action.EditorKeymap
import io.karpfen.ide.ui.editor.model.action.defaultEditorKeymap
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.state.EditorSettings
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.files.FolderChooser
import io.karpfen.ide.ui.workbench.files.ProjectFileOperations
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import io.karpfen.ide.ui.workbench.templates.FileTemplates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * The collaborators of the [WorkbenchState] (constructor injection, spec §1a); tests replace any of them with fakes.
 * A plain holder of services, without value semantics.
 *
 * @property workspaceFactory opens folders (core-workspace's seam, shared with the CLI).
 * @property editorKeymap the one editor keymap of the workbench: every editor is created with it
 *   ([EditorStateFactory.create]) and the editor commands of the menu take their shortcuts from it, so the menu and the
 *   editors always bind the same strokes (decision 15 of the work-package plan).
 * @property ioDispatcher where the workbench runs blocking file work ([fileOperations]); tests pass a test dispatcher.
 */
public class WorkbenchServices(
    public val settings: SettingsRepository,
    public val languages: LanguageRegistry,
    public val workspaceFactory: WorkspaceFactory,
    public val languageServicesFactory: LanguageServicesFactory,
    public val editorStateFactory: EditorStateFactory,
    public val fileOperations: ProjectFileOperations,
    public val folderChooser: FolderChooser,
    public val templates: FileTemplates,
    public val platform: HostPlatform,
    public val editorKeymap: EditorKeymap = defaultEditorKeymap(platform),
    public val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
)

/** Creates the [LanguageServices] of a workspace. */
public fun interface LanguageServicesFactory {
    /** Services reading [workspace]'s project analysis. */
    public fun create(workspace: Workspace): LanguageServices
}

/**
 * Creates the editor state of an opened document. Implementations decide the editor's background dispatcher (the
 * production one passes its background dispatcher, tests a test dispatcher).
 */
public fun interface EditorStateFactory {
    /**
     * The editor state of [session] in [scope] (the UI thread) with display [settings] and the workbench's [keymap]
     * (`WorkbenchServices.editorKeymap`), which the state must use.
     */
    public fun create(
        session: DocumentSession,
        languageServices: LanguageServices,
        scope: CoroutineScope,
        settings: StateFlow<EditorSettings>,
        keymap: EditorKeymap,
    ): CodeEditorState
}
