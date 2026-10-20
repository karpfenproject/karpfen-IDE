package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.text.LineSeparator
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.ui.editor.state.CodeEditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * What the status bar shows (spec §8): `Ln x, Col y` (one-based), selection length, language, line separator,
 * encoding, error and warning counts of the workspace, and "Analyzing…".
 */
public data class StatusBarInfo(
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

/** Status bar state; follows the active editor and the workspace (collected in [scope]). */
public class StatusBarState(private val scope: CoroutineScope) {

    /** What the status bar shows. */
    public val info: StateFlow<StatusBarInfo> get() = TODO("WP-WORKBENCH: status bar info")

    /** Follows the active [document] and its [editor] (null: no editor). */
    public fun bindEditor(document: OpenDocument?, editor: CodeEditorState?): Unit = TODO("WP-WORKBENCH: bind editor")

    /** Follows [workspace] for problem counts and the analyzing flag. */
    public fun bindWorkspace(workspace: Workspace?): Unit = TODO("WP-WORKBENCH: bind workspace")
}
