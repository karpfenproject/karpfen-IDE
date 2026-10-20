package io.karpfen.ide.ui.workbench

import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.lang.services.defaultLanguageServices
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.view.systemClipboard
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.files.FolderChooser
import io.karpfen.ide.ui.workbench.files.ProjectFileOperations
import io.karpfen.ide.ui.workbench.settings.SettingsLocations
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import io.karpfen.ide.ui.workbench.state.WorkbenchServices
import io.karpfen.ide.ui.workbench.templates.FileTemplates
import java.nio.file.Path

/**
 * The production collaborators: JSON settings in [settingsDirectory], the built-in languages, real workspaces, the
 * default language services, editors with the system clipboard, desktop file operations, the native folder chooser
 * and the built-in templates. Wiring only.
 */
public fun defaultWorkbenchServices(
    settingsDirectory: Path = SettingsLocations.defaultDirectory(),
    platform: HostPlatform = HostPlatform.current(),
): WorkbenchServices {
    val languages = defaultLanguageRegistry()
    return WorkbenchServices(
        settings = SettingsRepository(settingsDirectory),
        languages = languages,
        workspaceFactory = { root, scope -> Workspace(root, scope, languages = languages) },
        languageServicesFactory = { workspace -> defaultLanguageServices(workspace.analysis) },
        editorStateFactory = { session, services, scope, settings ->
            CodeEditorState(session, services, scope, systemClipboard(), settings, platform)
        },
        fileOperations = ProjectFileOperations(),
        folderChooser = FolderChooser(),
        templates = FileTemplates(),
        platform = platform,
    )
}
