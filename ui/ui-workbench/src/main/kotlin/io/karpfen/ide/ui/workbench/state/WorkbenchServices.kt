package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.state.EditorSettings
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.files.FolderChooser
import io.karpfen.ide.ui.workbench.files.ProjectFileOperations
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import io.karpfen.ide.ui.workbench.templates.FileTemplates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * The collaborators of the [WorkbenchState] (constructor injection, spec §1a); tests replace any of them with fakes.
 */
public data class WorkbenchServices(
    public val settings: SettingsRepository,
    public val languages: LanguageRegistry,
    public val workspaceFactory: WorkspaceFactory,
    public val languageServicesFactory: LanguageServicesFactory,
    public val editorStateFactory: EditorStateFactory,
    public val fileOperations: ProjectFileOperations,
    public val folderChooser: FolderChooser,
    public val templates: FileTemplates,
    public val platform: HostPlatform,
)

/** Opens a folder as a [Workspace] in [scope]. */
public fun interface WorkspaceFactory {
    /** The workspace of [root]. */
    public fun open(root: Path, scope: CoroutineScope): Workspace
}

/** Creates the [LanguageServices] of a workspace. */
public fun interface LanguageServicesFactory {
    /** Services reading [workspace]'s project analysis. */
    public fun create(workspace: Workspace): LanguageServices
}

/** Creates the editor state of an opened document. */
public fun interface EditorStateFactory {
    /** The editor state of [session] in [scope] with display [settings]. */
    public fun create(
        session: DocumentSession,
        services: LanguageServices,
        scope: CoroutineScope,
        settings: StateFlow<EditorSettings>,
    ): CodeEditorState
}
