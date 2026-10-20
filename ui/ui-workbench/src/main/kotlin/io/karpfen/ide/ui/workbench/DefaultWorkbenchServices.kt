package io.karpfen.ide.ui.workbench

import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.lang.services.defaultLanguageServices
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.view.systemClipboard
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.files.desktop.desktopProjectFileOperations
import io.karpfen.ide.ui.workbench.files.desktop.nativeFolderChooser
import io.karpfen.ide.ui.workbench.settings.SettingsLocations
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import io.karpfen.ide.ui.workbench.state.WorkbenchServices
import io.karpfen.ide.ui.workbench.templates.FileTemplates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path

/**
 * The production collaborators: JSON settings in [settingsDirectory], the built-in languages, real workspaces, the
 * default language services, editors with the system clipboard, desktop file operations, the native folder chooser
 * and the built-in templates. CPU work (tokenizing, parsing, analysis, highlighting, hover) runs on
 * [backgroundDispatcher], file I/O on [ioDispatcher]. Wiring only.
 */
public fun defaultWorkbenchServices(
    settingsDirectory: Path = SettingsLocations.defaultDirectory(),
    platform: HostPlatform = HostPlatform.current(),
    backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): WorkbenchServices {
    val languages = defaultLanguageRegistry()
    val workspaceOptions = WorkspaceOptions(
        ioDispatcher = ioDispatcher,
        analysisDispatcher = backgroundDispatcher,
        sessionConfig = DocumentSessionConfig(tokenizeDispatcher = backgroundDispatcher, parseDispatcher = backgroundDispatcher),
    )
    return WorkbenchServices(
        settings = SettingsRepository(settingsDirectory, ioDispatcher),
        languages = languages,
        workspaceFactory = { root, scope -> Workspace(root, scope, workspaceOptions, languages = languages) },
        languageServicesFactory = { workspace -> defaultLanguageServices(workspace.analysis) },
        editorStateFactory = { session, services, scope, settings ->
            CodeEditorState(
                session, services, scope, systemClipboard(), settings, platform,
                backgroundDispatcher = backgroundDispatcher,
            )
        },
        fileOperations = desktopProjectFileOperations(),
        folderChooser = nativeFolderChooser(),
        templates = FileTemplates(),
        platform = platform,
        ioDispatcher = ioDispatcher,
    )
}
