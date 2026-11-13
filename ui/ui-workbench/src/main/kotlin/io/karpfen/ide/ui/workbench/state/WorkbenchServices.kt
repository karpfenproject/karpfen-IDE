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

import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.WorkspaceFactory
import io.karpfen.ide.runtime.host.RuntimeHostFactory
import io.karpfen.ide.runtime.protocol.RuntimeClient
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.session.RuntimeSessions
import io.karpfen.ide.ui.editor.model.action.EditorKeymap
import io.karpfen.ide.ui.editor.model.action.defaultEditorKeymap
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.state.EditorSettings
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.files.FolderChooser
import io.karpfen.ide.ui.workbench.files.ProjectFileOperations
import io.karpfen.ide.ui.workbench.output.OutputBuffer
import io.karpfen.ide.ui.workbench.runtime.UnavailableRuntimeHosts
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import io.karpfen.ide.ui.workbench.templates.FileTemplates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * The collaborators of the [WorkbenchState], passed in through the constructor. Tests replace any of them with fakes.
 * The class is a plain holder of services and has no value semantics.
 *
 * @property workspaceFactory opens folders. It is the seam of core-workspace. A workspace gets
 *   its languages from it as `Workspace.languages`, so the services hold no language registry of their own.
 * @property editorKeymap the single editor keymap of the workbench. Every editor is created with it through
 *   [EditorStateFactory.create], and the editor commands of the menu take their shortcuts from it. The menu and the
 *   editors therefore always bind the same keystrokes. Its platform is [platform].
 * @property ioDispatcher where the workbench runs blocking file work through [fileOperations]. Tests pass a test
 *   dispatcher.
 * @property languageToolsOutput the output of the language tools, which the Language Tools Output tool window shows.
 *   It is the buffer that the application's logging fills, handed over by the composition root.
 * @property runtimeSessions prepares the deployments of the Run command and launches their sessions. There is one per
 *   process. It holds the single ledger of the environments left on a runtime.
 * @property runtimeHosts creates the runtime host of each opened workspace. Unless the composition root hands one in,
 *   no host ever has a runtime.
 * @property runtimeClients creates the client of a runtime endpoint. Tests hand in clients of the fake runtime.
 */
public class WorkbenchServices(
    public val settings: SettingsRepository,
    public val workspaceFactory: WorkspaceFactory,
    public val languageServicesFactory: LanguageServicesFactory,
    public val editorStateFactory: EditorStateFactory,
    public val fileOperations: ProjectFileOperations,
    public val folderChooser: FolderChooser,
    public val templates: FileTemplates,
    public val languageToolsOutput: OutputBuffer,
    public val editorKeymap: EditorKeymap = defaultEditorKeymap(),
    public val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    public val runtimeSessions: RuntimeSessions = RuntimeSessions(),
    public val runtimeHosts: RuntimeHostFactory = UnavailableRuntimeHosts(),
    public val runtimeClients: (RuntimeEndpoint) -> RuntimeClient = { RuntimeClient(it) },
) {
    /**
     * The platform of the whole workbench and the single source of truth for it. It is the platform of the
     * [editorKeymap], which the editors' pointer gestures and the menu shortcuts use too. The composition root creates
     * its `CommandRegistry` with it.
     */
    public val platform: HostPlatform get() = editorKeymap.platform
}

/** Creates the [LanguageServices] of a workspace. */
public fun interface LanguageServicesFactory {
    /** Creates the services that read the project analysis of [workspace]. */
    public fun create(workspace: Workspace): LanguageServices
}

/**
 * Creates the editor state of an opened document. Implementations decide the editor's background dispatcher. The
 * production factory passes its background dispatcher, and tests pass a test dispatcher.
 */
public fun interface EditorStateFactory {
    /**
     * Creates the editor state of [session] in [scope], which runs on the UI thread. The state gets the display
     * [settings] and the workbench's [keymap], which is `WorkbenchServices.editorKeymap`. The state must use that
     * keymap.
     */
    public fun create(
        session: DocumentSession,
        languageServices: LanguageServices,
        scope: CoroutineScope,
        settings: StateFlow<EditorSettings>,
        keymap: EditorKeymap,
    ): CodeEditorState
}
