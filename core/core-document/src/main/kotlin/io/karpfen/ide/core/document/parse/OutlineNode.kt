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
package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.text.TextRange

/**
 * A node of the document outline, such as a type with its properties or a state with its nested states.
 *
 * @property kind what the node declares or stands for.
 * @property name the display name. For a declaration it is the declared name without quotes. An anonymous KModel
 *   object of type `T` shows as `:T`. A transition shows as `a -> b`, `a -> b, c` or `a, b -> c`. A block shows as
 *   `ENTRY` or `DO`. A descriptor KEY shows its key. A descriptor ENTRY shows its first string, which is the file of a
 *   state machine entry, or else its position, such as `[0]`.
 * @property detail optional secondary text that depends on the [kind]. A TYPE shows its description. A SIMPLE_PROPERTY
 *   shows its primitive, such as `number`. A HAS_RELATION or KNOWS_RELATION shows its target, such as `Vector` or
 *   `list(Obstacle)`. An OBJECT shows its type name. A PROPERTY_ASSIGNMENT shows its value. A KNOWS_ASSIGNMENT shows
 *   the key of the target object. A STATEMACHINE shows its `ATTACHED TO` type. A MACRO shows its RETURNS expression. A
 *   MACRO_PARAMETER shows its parameter type. A BINDING shows the macro name. A KEY shows its value when that is a
 *   string, a number or a literal. All other nodes have null. Where a node also has a [SymbolDeclaration.typeName],
 *   both hold the same type.
 * @property range the whole construct.
 * @property selectionRange the part to select and reveal when navigating to the node, usually the name inside quotes.
 *   It lies within [range].
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
 * The chain of nodes whose [OutlineNode.range] contains [offset], with both bounds inclusive. It runs from the
 * top-level node down to the innermost one, and it is empty if no node contains [offset]. The outline uses it to
 * highlight the node at the caret.
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
