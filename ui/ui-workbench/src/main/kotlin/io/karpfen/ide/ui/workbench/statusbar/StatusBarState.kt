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
package io.karpfen.ide.ui.workbench.statusbar

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.text.LineSeparator
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import io.karpfen.ide.runtime.host.HostStatus
import io.karpfen.ide.ui.editor.model.caret.CaretSnapshot
import io.karpfen.ide.ui.editor.state.EditorHandle
import io.karpfen.ide.ui.editor.state.EditorSettings
import io.karpfen.ide.ui.workbench.output.UnseenOutputProblems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the status bar shows.
 *
 * [line] and [column] are one-based and appear as `Ln x, Col y`. The column is the display column, with tabs expanded
 * by `TabStops` of the editor's `tabDisplayWidth`, so it matches what the user sees. The state also holds the selection
 * length, the language, the line separator and the encoding. The error and warning counts of the workspace come from
 * `WorkspaceProblems.errorCount` and `WorkspaceProblems.warningCount`. [isAnalyzing] shows "Analyzing…".
 * [unseenOutput] holds the errors and warnings of the language tools that arrived while their output was hidden.
 * [runtime] tells what the runtime of the workspace is doing. It is null while no workspace is open.
 */
public data class StatusBarUiState(
    public val line: Int? = null,
    public val column: Int? = null,
    public val selectionLength: Int = 0,
    public val language: KarpfenLanguage? = null,
    public val lineSeparator: LineSeparator? = null,
    public val encoding: String = "UTF-8",
    public val errorCount: Int = 0,
    public val warningCount: Int = 0,
    public val isAnalyzing: Boolean = false,
    public val unseenOutput: UnseenOutputProblems = UnseenOutputProblems(),
    public val runtime: HostStatus? = null,
)

/**
 * The state of the status bar. It follows the active editor and the workspace, collected in [scope]. It also shows
 * what the mediator passes on from the Language Tools Output and the runtime. The bindings are operations of the
 * mediator and are internal, so no view can bypass the mediator. [CaretPositionText] computes the caret position.
 */
public class StatusBarState(private val scope: CoroutineScope) {

    private val status = MutableStateFlow(StatusBarUiState())
    private var editorBinding: Job? = null
    private var workspaceBinding: Job? = null

    /** What the status bar shows. */
    public val state: StateFlow<StatusBarUiState> get() = status.asStateFlow()

    /**
     * Follows the active [document] and its [editor]. Null for either clears the editor's part. Only the mediator
     * calls it.
     */
    internal fun bindEditor(document: OpenDocument?, editor: EditorHandle?) {
        editorBinding?.cancel()
        editorBinding = null
        if (document == null || editor == null) {
            status.update { it.withoutEditor() }
            return
        }
        val text = editor.session.text
        val language = editor.session.language
        fun show(carets: CaretSnapshot, snapshot: TextSnapshot, settings: EditorSettings, separator: LineSeparator) {
            val caret = CaretPositionText.of(snapshot, carets.primary, settings.tabDisplayWidth)
            status.update {
                it.copy(
                    line = caret.line, column = caret.column, selectionLength = caret.selectionLength,
                    language = language, lineSeparator = separator,
                )
            }
        }
        show(editor.caret.state.value, text.snapshot.value, editor.settings.value, document.lineSeparator.value)
        editorBinding = scope.launch {
            combine(editor.caret.state, text.snapshot, editor.settings, document.lineSeparator) { carets, snapshot, settings, separator ->
                show(carets, snapshot, settings, separator)
            }.collect()
        }
    }

    /**
     * Follows [workspace] for the problem counts from `Workspace.problems` and for the analyzing flag. Only the
     * mediator calls it.
     */
    internal fun bindWorkspace(workspace: Workspace?) {
        workspaceBinding?.cancel()
        workspaceBinding = null
        if (workspace == null) {
            status.update { it.copy(errorCount = 0, warningCount = 0, isAnalyzing = false) }
            return
        }
        fun show(problems: WorkspaceProblems, analyzing: Boolean) {
            status.update { it.copy(errorCount = problems.errorCount, warningCount = problems.warningCount, isAnalyzing = analyzing) }
        }
        show(workspace.problems.value, workspace.isAnalyzing.value)
        workspaceBinding = scope.launch {
            combine(workspace.problems, workspace.isAnalyzing, ::show).collect()
        }
    }

    /**
     * Shows the [unseen] errors and warnings of the language tools, as the Language Tools Output counts them. Only the
     * mediator calls it.
     */
    internal fun showUnseenOutput(unseen: UnseenOutputProblems) {
        status.update { it.copy(unseenOutput = unseen) }
    }

    /**
     * Shows what the runtime of the workspace is doing. Null removes the segment, which happens while no workspace is
     * open. Only the mediator calls it.
     */
    internal fun showRuntime(runtime: HostStatus?) {
        status.update { it.copy(runtime = runtime) }
    }

    /** This state without the editor's part, which is the caret, the selection, the language and the line separator. */
    private fun StatusBarUiState.withoutEditor(): StatusBarUiState =
        copy(line = null, column = null, selectionLength = 0, language = null, lineSeparator = null)
}
