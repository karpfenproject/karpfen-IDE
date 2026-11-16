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
package io.karpfen.ide.notation.diagram.structure

import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.text.TextRange

/**
 * Derives the class diagram of a `.kmeta` file from its outline. The outline is what the parser of the document layer
 * produces, so the diagram follows the same reading of the text as the editor does and there is no second parser.
 *
 * Every type becomes a box with its name as the title. A simple property becomes a row, as in `watts: number`. A
 * `has` relation becomes a containment link and a `knows` relation a reference link to the box of the type it names,
 * with the name of the relation as the label. A relation to a list of a type carries the mark `[*]`. A relation to a
 * type the file does not declare cannot be drawn as a link and becomes a row instead. A type that `behaves after` an
 * attachable state machine shows it as its first row, as in `behaves after glowing`. That clause is read from the
 * header of the type in the text, between the description and the body.
 */
public object MetamodelProjection {

    /** The mark of a relation to a list of a type. */
    private const val MANY = " [*]"

    private val LIST = Regex("""^list\((.*)\)$""")

    /** The header of a type after its name: the description and the `behaves after` clause, up to the body. */
    private val BEHAVIOUR = Regex("""^"\s*"[^"]*"\s+(behaves\s+after\s+"([^"]*)")""")

    /** The words of a `behaves after` row, followed by the name of the state machine. */
    public const val BEHAVES_AFTER: String = "behaves after "

    /**
     * The class diagram of the metamodel that [outline] describes within [text]. It has no boxes if the outline has no
     * types. [text] must be the text the outline was made from. Without it no type shows a `behaves after` clause.
     */
    public fun of(outline: List<OutlineNode>, text: String = ""): StructureDiagram {
        val ids = UniqueIds()
        val types = outline.filter { it.kind == SymbolKind.TYPE }.map { it to ids.next(it.name) }
        val declared = types.map { it.first.name }.toSet()
        val nodes = ArrayList<StructureNode>()
        val links = ArrayList<StructureLink>()
        for ((type, id) in types) {
            val rows = ArrayList<String>()
            val rowRanges = ArrayList<TextRange>()
            val behaviour = behaviourOf(text, type)
            if (behaviour != null) {
                rows += BEHAVES_AFTER + behaviour.machine
                rowRanges += behaviour.range
            }
            val relations = UniqueIds()
            for (member in type.children) {
                val detail = member.detail.orEmpty()
                val target = LIST.find(detail)?.groupValues?.get(1) ?: detail
                val kind = when (member.kind) {
                    SymbolKind.HAS_RELATION -> LinkKind.CONTAINMENT
                    SymbolKind.KNOWS_RELATION -> LinkKind.REFERENCE
                    else -> null
                }
                if (kind != null && target in declared) {
                    val word = if (kind == LinkKind.CONTAINMENT) "has" else "knows"
                    val label = if (target == detail) member.name else member.name + MANY
                    links += StructureLink(relations.next("$word:$id.${member.name}"), kind, id, target, label, member.range)
                } else if (kind != null || member.kind == SymbolKind.SIMPLE_PROPERTY) {
                    rows += if (detail.isEmpty()) member.name else "${member.name}: $detail"
                    rowRanges += member.range
                }
            }
            nodes += StructureNode(id, type.name, rows, type.range, type.selectionRange, rowRanges, behaviour)
        }
        return StructureDiagram(StructureKind.TYPES, nodes, links)
    }

    /**
     * The `behaves after` clause of [type] in [text], or null if it has none. The header is read from the closing quote
     * of the name of the type, which ends its selection range, up to the clause.
     */
    private fun behaviourOf(text: String, type: OutlineNode): TypeBehaviour? {
        val start = type.selectionRange.end
        if (start >= text.length || start >= type.range.end) return null
        val match = BEHAVIOUR.find(text.substring(start, type.range.end)) ?: return null
        val clause = checkNotNull(match.groups[1]).range
        val name = checkNotNull(match.groups[2])
        return TypeBehaviour(
            name.value,
            TextRange(start + clause.first, start + clause.last + 1),
            TextRange(start + name.range.first, start + name.range.last + 1),
        )
    }
}
