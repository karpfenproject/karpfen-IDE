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

import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.dialog.DialogState
import io.karpfen.ide.ui.workbench.layout.LayoutState
import io.karpfen.ide.ui.workbench.layout.ToolWindowId
import io.karpfen.ide.ui.workbench.runtime.RunSupport
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * The workbench as a run sees it. A run gets the active tab, Save All through the [saving] flow, the choice of the
 * descriptor, the error dialog and the Runtime tool window of the [layout]. Save All and the descriptor choice run in
 * the turn of the [runner], so they never interleave with the dialog of another intent.
 */
internal class WorkbenchRunSupport(
    private val runner: IntentRunner,
    private val tabs: EditorTabsState,
    private val saving: SaveFlow,
    private val dialogs: DialogState,
    private val layout: LayoutState,
) : RunSupport {

    override val activeFile: StateFlow<Path?> get() = tabs.active

    override suspend fun saveAll(): Boolean = runner.exclusive { saving.save(tabs.documents().filter { it.isDirty.value }) }

    override suspend fun chooseDescriptor(root: Path, descriptors: List<Path>, preselected: Path?): Path? = runner.exclusive {
        val request = DialogRequest.ChooseDescriptor(root, descriptors, descriptors.indexOf(preselected).coerceAtLeast(0))
        when (val answer = dialogs.ask(request)) {
            is DialogResponse.Chosen -> descriptors[answer.index]
            DialogResponse.Cancel -> null
        }
    }

    override suspend fun showError(title: String, message: String) {
        dialogs.showError(title, message)
    }

    override fun showRuntimeToolWindow() {
        layout.showToolWindow(ToolWindowId.RUNTIME)
    }
}
