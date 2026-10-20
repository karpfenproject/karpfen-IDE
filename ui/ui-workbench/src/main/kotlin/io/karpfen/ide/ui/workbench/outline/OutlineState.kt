package io.karpfen.ide.ui.workbench.outline

import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.text.TextLineage
import io.karpfen.ide.ui.editor.state.CodeEditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * UI state of the Outline tool window.
 *
 * @property path the outlined document, or null when nothing is outlined.
 * @property version the version of the parse [nodes] come from (the last good parse while the text has syntax errors),
 *   so navigation maps their ranges into the live text; null when nothing is outlined.
 * @property lineage the document's text lineage (see `TextLineage`); null when nothing is outlined.
 * @property nodes the outline of the active document's latest parse (last good parse while the text has syntax errors).
 * @property highlighted the chain of nodes containing the caret (innermost last), highlighted in the tree.
 * @property isStale true if [nodes] come from an older version than the current text.
 */
public data class OutlineUiState(
    public val path: Path? = null,
    public val version: Long? = null,
    public val lineage: TextLineage? = null,
    public val nodes: List<OutlineNode> = emptyList(),
    public val highlighted: List<OutlineNode> = emptyList(),
    public val isStale: Boolean = false,
) {
    init {
        require(lineage == null || version != null) { "a lineage needs a version" }
    }

    /**
     * Where clicking [node] navigates: its selection range in the parse it comes from (version and lineage of that
     * parse, mapped into the live text by `WorkbenchState.navigateTo`), or null when nothing is outlined.
     */
    public fun locationOf(node: OutlineNode): Location? = path?.let { Location(it, node.selectionRange, version, lineage) }
}

/**
 * Outline of the active document (spec §8); follows the bound editor's parse and caret (collected in [scope]). The
 * binding is a mediator operation and internal (decision 31 of the work-package plan).
 */
public class OutlineState(private val scope: CoroutineScope) {

    /** What the Outline tool window shows. */
    public val state: StateFlow<OutlineUiState> get() = TODO("WP-WORKBENCH: outline state")

    /** Follows [editor] (the active tab's), or shows nothing for null. Mediator only. */
    internal fun bindEditor(editor: CodeEditorState?): Unit = TODO("WP-WORKBENCH: bind outline to the active editor")
}
