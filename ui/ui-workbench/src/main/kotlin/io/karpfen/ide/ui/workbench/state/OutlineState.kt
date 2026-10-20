package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.ui.editor.state.CodeEditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * UI state of the Outline tool window.
 *
 * @property nodes the outline of the active document's latest parse (last good parse while the text has syntax errors).
 * @property highlighted the chain of nodes containing the caret (innermost last), highlighted in the tree.
 * @property isStale true if [nodes] come from an older version than the current text.
 */
public data class OutlineUiState(
    public val nodes: List<OutlineNode> = emptyList(),
    public val highlighted: List<OutlineNode> = emptyList(),
    public val isStale: Boolean = false,
)

/** Outline of the active document (spec §8); follows the bound editor's parse and caret (collected in [scope]). */
public class OutlineState(private val scope: CoroutineScope) {

    /** What the Outline tool window shows. */
    public val state: StateFlow<OutlineUiState> get() = TODO("WP-WORKBENCH: outline state")

    /** Follows [editor] (the active tab's), or shows nothing for null. */
    public fun bind(editor: CodeEditorState?): Unit = TODO("WP-WORKBENCH: bind outline to the active editor")
}
