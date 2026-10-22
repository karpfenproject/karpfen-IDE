package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.text.TextRange

/**
 * A node of the document outline, e.g. a type with its properties or a state with its nested states.
 *
 * @property kind what the node declares or stands for.
 * @property name display name: the declared name (without quotes); `:T` for an anonymous KModel object of type `T`;
 *   `a -> b`, `a -> b, c` or `a, b -> c` for transitions; `ENTRY`/`DO` for blocks; the key for a descriptor KEY; for a
 *   descriptor ENTRY its first string (the file of a state machine entry) or its position (`[0]`).
 * @property detail optional secondary text by kind: TYPE → the description; SIMPLE_PROPERTY → the primitive
 *   (`number`); HAS_RELATION/KNOWS_RELATION → the target (`Vector`, `list(Obstacle)`); OBJECT → the type name;
 *   PROPERTY_ASSIGNMENT → the value; KNOWS_ASSIGNMENT → the target object key; STATEMACHINE → the `ATTACHED TO` type;
 *   MACRO → the RETURNS expression; MACRO_PARAMETER → the parameter type; BINDING → the macro name; KEY → its value
 *   when that is a string, a number or a literal; null otherwise (the same types as [SymbolDeclaration.typeName] where
 *   both exist).
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
