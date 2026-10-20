package io.karpfen.ide.ui.workbench.statusbar

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.text.LineSeparator
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.ui.editor.state.EditorHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

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
 * operations and internal (decision 31 of the work-package plan).
 */
public class StatusBarState(private val scope: CoroutineScope) {

    /** What the status bar shows. */
    public val state: StateFlow<StatusBarUiState> get() = TODO("WP-WORKBENCH: status bar state")

    /** Follows the active [document] and its [editor] (null: no editor). Mediator only. */
    internal fun bindEditor(document: OpenDocument?, editor: EditorHandle?): Unit = TODO("WP-WORKBENCH: bind editor")

    /** Follows [workspace] for the problem counts (`Workspace.problems`) and the analyzing flag. Mediator only. */
    internal fun bindWorkspace(workspace: Workspace?): Unit = TODO("WP-WORKBENCH: bind workspace")
}
