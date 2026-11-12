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
 * type the file does not declare cannot be drawn as a link and becomes a row instead.
 */
public object MetamodelProjection {

    /** The mark of a relation to a list of a type. */
    private const val MANY = " [*]"

    private val LIST = Regex("""^list\((.*)\)$""")

    /** The class diagram of the metamodel that [outline] describes. It has no boxes if the outline has no types. */
    public fun of(outline: List<OutlineNode>): StructureDiagram {
        val ids = UniqueIds()
        val types = outline.filter { it.kind == SymbolKind.TYPE }.map { it to ids.next(it.name) }
        val declared = types.map { it.first.name }.toSet()
        val nodes = ArrayList<StructureNode>()
        val links = ArrayList<StructureLink>()
        for ((type, id) in types) {
            val rows = ArrayList<String>()
            val rowRanges = ArrayList<TextRange>()
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
            nodes += StructureNode(id, type.name, rows, type.range, type.selectionRange, rowRanges)
        }
        return StructureDiagram(StructureKind.TYPES, nodes, links)
    }
}
