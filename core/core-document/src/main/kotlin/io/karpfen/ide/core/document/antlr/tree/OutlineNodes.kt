package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.text.TextRange

/**
 * An [OutlineNode] whose range is widened to cover [selection] and every child, so the outline's invariants hold on
 * partial trees of broken text too: the selection lies within the node, and every child lies within its parent (which
 * `pathAt` relies on).
 */
internal fun outlineNode(
    kind: SymbolKind,
    name: String,
    detail: String?,
    range: TextRange,
    selection: TextRange,
    children: List<OutlineNode> = emptyList(),
): OutlineNode {
    val covering = children.fold(range.union(selection)) { acc, child -> acc.union(child.range) }
    return OutlineNode(kind, name, detail, covering, selection, children)
}
