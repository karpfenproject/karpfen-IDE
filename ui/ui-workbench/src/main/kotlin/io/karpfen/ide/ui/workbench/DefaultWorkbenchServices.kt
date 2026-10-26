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
package io.karpfen.ide.ui.workbench

import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.lang.services.defaultLanguageServices
import io.karpfen.ide.core.workspace.WorkspaceFactory
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.core.workspace.files.FileWatcher
import io.karpfen.ide.ui.editor.model.action.ClipboardPort
import io.karpfen.ide.ui.editor.model.action.defaultEditorKeymap
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.view.systemClipboard
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.files.FolderChooser
import io.karpfen.ide.ui.workbench.files.ProjectFileOperations
import io.karpfen.ide.ui.workbench.files.desktop.desktopProjectFileOperations
import io.karpfen.ide.ui.workbench.files.desktop.nativeFolderChooser
import io.karpfen.ide.ui.workbench.output.OutputBuffer
import io.karpfen.ide.ui.workbench.output.RingOutputBuffer
import io.karpfen.ide.ui.workbench.settings.SettingsLocations
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import io.karpfen.ide.ui.workbench.state.WorkbenchServices
import io.karpfen.ide.ui.workbench.templates.FileTemplates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import java.time.Clock

/**
 * The production collaborators: JSON settings in [settingsDirectory], the built-in languages, real workspaces, the
 * default language services, editors sharing one [clipboard] and one keymap for [platform], desktop file operations,
 * the native folder chooser and the built-in templates. CPU work (tokenizing, parsing, analysis, highlighting, find,
 * completion, hover) runs on [backgroundDispatcher], file I/O on [ioDispatcher]. Wiring only.
 *
 * Every time-, file-system- and toolkit-dependent collaborator is a parameter with a production default, so end-to-end
 * tests run exactly this wiring with test values: [workspaceOptions] (dispatchers, `timeSource` for undo coalescing,
 * `watch`), [fileWatcher] (a fake instead of the slow macOS `WatchService`), [clock] (settings backups),
 * [fileOperations] / [folderChooser] (the desktop adapters are headless-safe, but tests usually record calls) and
 * [clipboard] (the system clipboard, created once: every editor of the workbench gets this one port, so text and the
 * whole-line mark copied in one tab are pasted in another also where the port is not the global AWT clipboard — its
 * headless in-memory fallback, a test's fake). [languageToolsOutput] is the buffer the application's logging fills with
 * what the language tools log; the composition root passes the one it routes that logging into.
 */
public fun defaultWorkbenchServices(
    settingsDirectory: Path = SettingsLocations.defaultDirectory(),
    platform: HostPlatform = HostPlatform.current(),
    backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    workspaceOptions: WorkspaceOptions = WorkspaceOptions(
        ioDispatcher = ioDispatcher,
        analysisDispatcher = backgroundDispatcher,
        sessionConfig = DocumentSessionConfig(tokenizeDispatcher = backgroundDispatcher, parseDispatcher = backgroundDispatcher),
    ),
    fileWatcher: FileWatcher = FileWatcher(),
    clock: Clock = Clock.systemDefaultZone(),
    fileOperations: ProjectFileOperations = desktopProjectFileOperations(),
    folderChooser: FolderChooser = nativeFolderChooser(),
    clipboard: ClipboardPort = systemClipboard(),
    languageToolsOutput: OutputBuffer = RingOutputBuffer(),
): WorkbenchServices {
    return WorkbenchServices(
        settings = SettingsRepository(settingsDirectory, ioDispatcher, clock),
        workspaceFactory = WorkspaceFactory(workspaceOptions, fileWatcher, defaultLanguageRegistry()),
        languageServicesFactory = { workspace -> defaultLanguageServices(workspace.analysis, files = workspace.files) },
        editorStateFactory = { session, languageServices, scope, settings, keymap ->
            CodeEditorState(
                session, languageServices, scope, clipboard, settings,
                keymap = keymap,
                backgroundDispatcher = backgroundDispatcher,
            )
        },
        fileOperations = fileOperations,
        folderChooser = folderChooser,
        templates = FileTemplates(),
        editorKeymap = defaultEditorKeymap(platform),
        ioDispatcher = ioDispatcher,
        languageToolsOutput = languageToolsOutput,
    )
}
