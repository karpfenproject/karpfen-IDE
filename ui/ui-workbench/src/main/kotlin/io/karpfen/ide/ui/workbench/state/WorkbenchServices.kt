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
import io.karpfen.ide.ui.editor.model.action.EditorKeymap
import io.karpfen.ide.ui.editor.model.action.defaultEditorKeymap
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.state.EditorSettings
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.files.FolderChooser
import io.karpfen.ide.ui.workbench.files.ProjectFileOperations
import io.karpfen.ide.ui.workbench.output.OutputBuffer
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import io.karpfen.ide.ui.workbench.templates.FileTemplates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * The collaborators of the [WorkbenchState] (constructor injection); tests replace any of them with fakes. A plain
 * holder of services, without value semantics.
 *
 * @property workspaceFactory opens folders (core-workspace's seam, shared with the CLI); a workspace's languages come
 *   from it (`Workspace.languages`), so the services hold no language registry of their own.
 * @property editorKeymap the one editor keymap of the workbench: every editor is created with it
 *   ([EditorStateFactory.create]) and the editor commands of the menu take their shortcuts from it, so the menu and the
 *   editors always bind the same strokes. Its platform is [platform].
 * @property ioDispatcher where the workbench runs blocking file work ([fileOperations]); tests pass a test dispatcher.
 * @property languageToolsOutput the output of the language tools, which the Language Tools Output tool window shows:
 *   the buffer the application's logging fills, which the composition root hands over.
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
) {
    /**
     * The platform of the whole workbench, the one source of truth for it: the [editorKeymap]'s, which the editors'
     * pointer gestures and the menu shortcuts use too; the composition root creates its `CommandRegistry` with it.
     */
    public val platform: HostPlatform get() = editorKeymap.platform
}

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
