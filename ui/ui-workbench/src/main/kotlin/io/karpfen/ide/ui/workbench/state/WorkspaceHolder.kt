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

import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.workspace.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the current [Workspace] of the workbench, its [LanguageServices] and the window title derived from it. The
 * title is the folder's name, a dash and the application name. The class is a plain holder. Opening and closing
 * workspaces is the job of the folder flow.
 */
internal class WorkspaceHolder {

    private val current = MutableStateFlow<Workspace?>(null)
    private val services = MutableStateFlow<LanguageServices?>(null)
    private val title = MutableStateFlow(APPLICATION_NAME)

    /** The workspace of the opened folder, or null while no folder is open. */
    val workspace: StateFlow<Workspace?> get() = current.asStateFlow()

    /** The language services of [workspace], or null while no folder is open. */
    val languageServices: StateFlow<LanguageServices?> get() = services.asStateFlow()

    /** The window title. */
    val windowTitle: StateFlow<String> get() = title.asStateFlow()

    /** Holds [workspace] and its [languageServices]. */
    fun set(workspace: Workspace, languageServices: LanguageServices) {
        current.value = workspace
        services.value = languageServices
        title.value = "${workspace.root.fileName ?: workspace.root} — $APPLICATION_NAME"
    }

    /** Forgets the current workspace and returns it, or null if there was none. The caller closes it. */
    fun clear(): Workspace? {
        val previous = current.value
        current.value = null
        services.value = null
        title.value = APPLICATION_NAME
        return previous
    }

    companion object {
        /** The application's name, which is also the window title while no folder is open. */
        const val APPLICATION_NAME: String = "karpfen IDE"
    }
}
