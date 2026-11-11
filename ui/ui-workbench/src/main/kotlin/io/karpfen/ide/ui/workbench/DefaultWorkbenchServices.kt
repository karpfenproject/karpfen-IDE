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

import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.lang.services.defaultLanguageServices
import io.karpfen.ide.core.workspace.WorkspaceFactory
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.core.workspace.files.FileWatcher
import io.karpfen.ide.runtime.host.RuntimeHostFactory
import io.karpfen.ide.runtime.session.RuntimeSessions
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
import io.karpfen.ide.ui.workbench.runtime.UnavailableRuntimeHosts
import io.karpfen.ide.ui.workbench.settings.SettingsLocations
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import io.karpfen.ide.ui.workbench.state.WorkbenchServices
import io.karpfen.ide.ui.workbench.templates.FileTemplates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import java.time.Clock

/**
 * Builds the production collaborators of the workbench. They are JSON settings in [settingsDirectory], the built-in
 * languages, real workspaces, the default language services, editors that share one [clipboard] and one keymap for
 * [platform], the desktop file operations, the native folder chooser and the built-in templates. CPU work such as
 * tokenizing, parsing, analysis, highlighting, find, completion and hover runs on [backgroundDispatcher]. File I/O runs
 * on [ioDispatcher]. The function only wires things together and holds no logic of its own.
 *
 * Every collaborator that depends on time, the file system or the UI toolkit is a parameter with a production default.
 * End-to-end tests therefore run exactly this wiring and only swap in test values:
 *
 * - [workspaceOptions] carries the dispatchers, the `timeSource` for undo coalescing and the `watch` switch.
 * - [fileWatcher] is replaced by a fake, because the macOS `WatchService` is slow.
 * - [clock] dates the settings backups.
 * - [fileOperations] and [folderChooser] are the desktop adapters. They are safe in a headless JVM, but tests usually
 *   replace them with doubles that record the calls.
 * - [clipboard] is the system clipboard and is created once. Every editor of the workbench gets this one port. Text
 *   and the whole-line mark copied in one tab can then be pasted in another, also where the port is not the global
 *   AWT clipboard, such as its in-memory fallback in a headless JVM or a test's fake.
 * - [dslReader] holds the readers of dsl-tools that every workspace reads its files with. End-to-end tests make them
 *   fail on purpose.
 *
 * [languageToolsOutput] has no default. It is the buffer that the application's logging fills with what the language
 * tools log. The composition root passes the buffer it routes that logging into, so no caller can end up with an
 * output that nothing fills.
 *
 * The runtime is the composition root's choice. The application passes the single [runtimeSessions] of the process and
 * the IDE's runtime hosts as [runtimeHosts]. Without them the wiring creates sessions of its own and uses hosts that
 * never have a runtime, so a test that runs this wiring never reaches Docker or a runtime at a URL.
 */
public fun defaultWorkbenchServices(
    languageToolsOutput: OutputBuffer,
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
    dslReader: DslReader = DslReader(),
    runtimeSessions: RuntimeSessions = RuntimeSessions(),
    runtimeHosts: RuntimeHostFactory = UnavailableRuntimeHosts(),
): WorkbenchServices {
    return WorkbenchServices(
        settings = SettingsRepository(settingsDirectory, ioDispatcher, clock),
        workspaceFactory = WorkspaceFactory(workspaceOptions, fileWatcher, dslReader),
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
        runtimeSessions = runtimeSessions,
        runtimeHosts = runtimeHosts,
    )
}
