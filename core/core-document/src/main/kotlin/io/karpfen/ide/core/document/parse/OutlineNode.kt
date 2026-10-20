package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.text.TextRange

/**
 * A node of the document outline (spec §3.3), e.g. a type with its properties or a state with its nested states.
 *
 * @property name display name: the declared name (without quotes); `:T` for an anonymous KModel object of type `T`;
 *   `a -> b`, `a -> b, c` or `a, b -> c` for transitions; `ENTRY`/`DO` for blocks.
 * @property detail optional secondary text, e.g. the type of a property (`number`, `list(Obstacle)`).
 * @property range the whole construct.
 * @property selectionRange the part to select/reveal when navigating to the node (usually the name inside quotes);
 *   lies within [range].
 * @property children nested nodes in document order.
 */
public data class OutlineNode(
    public val kind: SymbolKind,
    public val name: String,
    public val detail: String?,
    public val range: TextRange,
    public val selectionRange: TextRange,
    public val children: List<OutlineNode>,
) {
    init {
        require(selectionRange in range) { "selectionRange $selectionRange must lie within range $range" }
    }
}

/**
 * The chain of nodes whose [OutlineNode.range] contains [offset] (inclusive bounds), from the top-level node down to
 * the innermost one; empty if no node contains [offset]. Used to highlight the outline node at the caret.
 */
public fun List<OutlineNode>.pathAt(offset: Int): List<OutlineNode> {
    val path = ArrayList<OutlineNode>()
    var level: List<OutlineNode> = this
    while (true) {
        val node = level.firstOrNull { it.range.containsInclusive(offset) } ?: return path
        path += node
        level = node.children
    }
}
