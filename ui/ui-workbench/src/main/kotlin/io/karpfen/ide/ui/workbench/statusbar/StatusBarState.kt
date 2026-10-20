package io.karpfen.ide.ui.workbench.statusbar

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.text.LineSeparator
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import io.karpfen.ide.ui.editor.model.caret.CaretSnapshot
import io.karpfen.ide.ui.editor.state.EditorHandle
import io.karpfen.ide.ui.editor.state.EditorSettings
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
 * What the status bar shows (spec §8): `Ln x, Col y` (one-based; [column] is the display column with tabs expanded by
 * `TabStops` of the editor's `tabDisplayWidth`, so it matches what the user sees), selection length, language, line
 * separator, encoding, error and warning counts of the workspace (`WorkspaceProblems.errorCount`/`warningCount`), and
 * "Analyzing…".
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
)

/**
 * Status bar state; follows the active editor and the workspace (collected in [scope]). The bindings are mediator
 * operations and internal (decision 31 of the work-package plan). The caret position is [CaretPositionText]'s.
 */
public class StatusBarState(private val scope: CoroutineScope) {

    private val status = MutableStateFlow(StatusBarUiState())
    private var editorBinding: Job? = null
    private var workspaceBinding: Job? = null

    /** What the status bar shows. */
    public val state: StateFlow<StatusBarUiState> get() = status.asStateFlow()

    /** Follows the active [document] and its [editor] (null: no editor). Mediator only. */
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

    /** Follows [workspace] for the problem counts (`Workspace.problems`) and the analyzing flag. Mediator only. */
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

    private fun StatusBarUiState.withoutEditor(): StatusBarUiState =
        copy(line = null, column = null, selectionLength = 0, language = null, lineSeparator = null)
}
