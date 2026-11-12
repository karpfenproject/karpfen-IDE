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
import java.util.IdentityHashMap

/**
 * Derives the object diagram of a `.kmodel` file from its outline. The outline is what the parser of the document
 * layer produces, so the diagram follows the same reading of the text as the editor does and there is no second
 * parser.
 *
 * Every object becomes a box with its key and its type as the title, as in `bulb : Bulb`. An object without a key
 * shows its type alone. A property becomes a row with its value, as in `watts = 40`. A `has` statement becomes a
 * containment link to the box of the object it makes, and a `knows` statement a reference link to the box of the
 * object with the key it names, each with the name of the relation as the label. A `knows` statement that names a key
 * no object has cannot be drawn as a link and becomes a row instead. A long value is cut and ends with an ellipsis.
 */
public object ModelProjection {

    /** The longest row of an object in characters. */
    private const val MAX_ROW = 48

    /** The mark of a row that was cut. */
    private const val ELLIPSIS = "…"

    /** The start of the outline name of an object without a key. */
    private const val ANONYMOUS = ":"

    /** The object diagram of the model that [outline] describes. It has no boxes if the outline has no object. */
    public fun of(outline: List<OutlineNode>): StructureDiagram {
        val ids = UniqueIds()
        val objects = outline.filter { it.kind == SymbolKind.OBJECT }.flatMap(::withNested)
        val idOf = IdentityHashMap<OutlineNode, String>()
        objects.forEach { idOf[it] = ids.next(it.name) }
        val keys = objects.map { it.name }.filter { !it.startsWith(ANONYMOUS) }.toSet()
        val nodes = ArrayList<StructureNode>()
        val links = ArrayList<StructureLink>()
        for (item in objects) {
            val id = idOf.getValue(item)
            val rows = ArrayList<String>()
            val rowRanges = ArrayList<TextRange>()
            val relations = UniqueIds()
            for (statement in item.children) {
                when (statement.kind) {
                    SymbolKind.PROPERTY_ASSIGNMENT -> {
                        rows += row(statement)
                        rowRanges += statement.range
                    }
                    SymbolKind.HAS_ASSIGNMENT -> statement.children.filter { it.kind == SymbolKind.OBJECT }.forEach { made ->
                        links += StructureLink(
                            relations.next("has:$id.${statement.name}"), LinkKind.CONTAINMENT, id, idOf.getValue(made), statement.name, statement.range,
                        )
                    }
                    SymbolKind.KNOWS_ASSIGNMENT -> {
                        val target = statement.detail
                        if (target != null && target in keys) {
                            links += StructureLink(
                                relations.next("knows:$id.${statement.name}"), LinkKind.REFERENCE, id, target, statement.name, statement.range,
                            )
                        } else {
                            rows += row(statement)
                            rowRanges += statement.range
                        }
                    }
                    else -> Unit
                }
            }
            nodes += StructureNode(id, title(item), rows, item.range, item.selectionRange, rowRanges)
        }
        return StructureDiagram(StructureKind.OBJECTS, nodes, links.sortedBy { it.range.start })
    }

    /** The object [item] and every object made inside it, each owner before what it owns. */
    private fun withNested(item: OutlineNode): List<OutlineNode> =
        listOf(item) + item.children
            .filter { it.kind == SymbolKind.HAS_ASSIGNMENT }
            .flatMap { has -> has.children.filter { it.kind == SymbolKind.OBJECT } }
            .flatMap(::withNested)

    /** The title of the box of [item]: its key and its type, or the type alone for an object without a key. */
    private fun title(item: OutlineNode): String {
        val type = item.detail.orEmpty()
        return when {
            item.name.startsWith(ANONYMOUS) -> ": $type"
            type.isEmpty() -> item.name
            else -> "${item.name} : $type"
        }
    }

    /** The row of the statement [statement], which reads its name, an equals sign and its value. */
    private fun row(statement: OutlineNode): String {
        val row = "${statement.name} = ${statement.detail.orEmpty()}"
        return if (row.length <= MAX_ROW) row else row.take(MAX_ROW - 1) + ELLIPSIS
    }
}
