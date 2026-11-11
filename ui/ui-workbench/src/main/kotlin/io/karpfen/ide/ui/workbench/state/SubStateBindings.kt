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

import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.ui.workbench.layout.LayoutState
import io.karpfen.ide.ui.workbench.layout.ToolWindowId
import io.karpfen.ide.ui.workbench.outline.OutlineState
import io.karpfen.ide.ui.workbench.output.OutputState
import io.karpfen.ide.ui.workbench.problems.ProblemsState
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeState
import io.karpfen.ide.ui.workbench.runtime.RuntimeState
import io.karpfen.ide.ui.workbench.statusbar.StatusBarState
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Binds the read-only sub-states to what they follow. Everything is collected in [scope].
 *
 * - [bindWorkspace] binds the project tree to `Workspace.files`, and Problems and the status bar counts to the
 *   workspace. The folder flow calls it.
 * - After [start], the Outline and the caret part of the status bar follow the editor of the active tab. They follow
 *   [EditorTabsState.activeEditor] directly, because views switch tabs without going through the mediator.
 * - After [start], the Language Tools Output follows the visibility of its tool window in the [layout], and the status
 *   bar's indicator follows the output's unseen problems.
 * - [bindRuntime] binds the status bar's runtime segment and the runtime log to the runtime state of the workspace.
 */
internal class SubStateBindings(
    private val scope: CoroutineScope,
    private val tabs: EditorTabsState,
    private val projectTree: ProjectTreeState,
    private val outline: OutlineState,
    private val problems: ProblemsState,
    private val statusBar: StatusBarState,
    private val output: OutputState,
    private val layout: LayoutState,
) {
    private var treeBinding: Job? = null
    private var runtimeBinding: Job? = null

    /**
     * Starts following the active editor, the visibility of the output's tool window and the output's unseen problems.
     */
    fun start() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            tabs.activeEditor.collect { editor ->
                val document = tabs.active.value?.let(tabs::documentFor)
                outline.bindEditor(editor)
                statusBar.bindEditor(document, editor)
            }
        }
        output.bindVisibility(layout.state.map { it.toolWindow(ToolWindowId.OUTPUT).visible }.distinctUntilChanged())
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            output.state.map { it.unseen }.distinctUntilChanged().collect(statusBar::showUnseenOutput)
        }
    }

    /**
     * Binds the status bar's runtime segment to the host of [runtime], and the runtime log to the visibility of the
     * Runtime tool window. Null removes the segment.
     */
    fun bindRuntime(runtime: RuntimeState?) {
        runtimeBinding?.cancel()
        statusBar.showRuntime(runtime?.host?.value)
        runtimeBinding = runtime?.let { bound ->
            bound.bindLogVisibility(layout.state.map { it.toolWindow(ToolWindowId.RUNTIME).visible }.distinctUntilChanged())
            scope.launch(start = CoroutineStart.UNDISPATCHED) { bound.host.collect(statusBar::showRuntime) }
        }
    }

    /** Binds the tree, Problems and the status bar counts to [workspace]. Null clears them. */
    fun bindWorkspace(workspace: Workspace?) {
        treeBinding?.cancel()
        projectTree.setTree(workspace?.files?.value)
        problems.bindWorkspace(workspace)
        statusBar.bindWorkspace(workspace)
        treeBinding = workspace?.let { bound ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) { bound.files.collect(projectTree::setTree) }
        }
    }
}
