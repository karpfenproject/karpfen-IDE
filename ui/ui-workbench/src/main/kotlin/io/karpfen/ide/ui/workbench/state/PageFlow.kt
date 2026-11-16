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

import io.karpfen.ide.core.workspace.analysis.ProjectFragments
import io.karpfen.ide.ui.workbench.dialog.DialogState
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import io.karpfen.ide.ui.workbench.tabs.EditorViewMode
import java.nio.file.Path

/**
 * The flow behind the steps between the pages of a state machine in the diagram: from a container state to the
 * diagram of the fragment it uses, and from a fragment back to a file that uses it. The fragment is found by its name
 * through [ProjectFragments] over the latest analysis of the workspace in [holder], so it is known while files have
 * errors. The file opens through [files] and shows its diagram the way the file it was reached from shows it, which is
 * next to the text or alone. A name that no file of the project declares becomes an error dialog.
 */
internal class PageFlow(
    private val holder: WorkspaceHolder,
    private val files: FileOpenFlow,
    private val tabs: EditorTabsState,
    private val dialogs: DialogState,
) {

    /** Opens the diagram of the fragment named [fragment], as the file at [from] finds it. */
    suspend fun openPage(from: Path, fragment: String) {
        val analysis = holder.workspace.value?.analysis?.value ?: return
        val declared = ProjectFragments(analysis).named(fragment, near = from)
        if (declared == null) {
            dialogs.showError("Cannot open the page $fragment", "No file of the project declares the fragment \"$fragment\".")
            return
        }
        openWithDiagram(declared.path, from)
    }

    /** Opens [path] and shows its diagram the way the file at [from] shows it. */
    suspend fun openWithDiagram(path: Path, from: Path) {
        val mode = tabs.viewModes.value[from]?.takeIf { it != EditorViewMode.TEXT } ?: EditorViewMode.DIAGRAM
        if (files.openFile(path)) tabs.setViewMode(path, mode)
    }
}
