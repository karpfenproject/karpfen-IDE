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
package io.karpfen.ide.notation.diagram.edit

import io.karpfen.ide.notation.diagram.layout.DiagramLayout
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.StructureLayout
import io.karpfen.ide.notation.diagram.structure.LinkKind
import io.karpfen.ide.notation.diagram.structure.StructureDiagram
import io.karpfen.ide.notation.diagram.structure.StructureKind
import io.karpfen.ide.notation.diagram.structure.StructureNode

/**
 * What a user can do at a point of a class diagram or an object diagram.
 *
 * - On the line or the label of a link: rename the relation and delete it.
 * - On a row of a box: edit the statement behind the row as text, and delete it.
 * - On a type: rename it, add a property, add a relation to a type the user clicks, and delete it.
 * - On an object: rename it, add a property, add an object it has, add a reference to an object the user clicks, and
 *   delete it.
 * - On the empty canvas of a class diagram: add a type.
 */
public object StructureActions : DiagramActions<StructureDiagram, StructureLayout> {

    override fun at(source: String, diagram: StructureDiagram, layout: StructureLayout, point: DiagramPoint): List<DiagramAction> {
        val link = layout.labelAt(point) ?: layout.lineAt(point, DiagramLayout.HIT_TOLERANCE)?.id
        val node = layout.boxAt(point)?.let(diagram::node)
        val row = node?.let { layout.rowAt(it.id, point) }?.takeIf { it < node.rows.size }
        return when {
            link != null -> onLink(source, diagram, link)
            node != null && row != null -> onRow(source, diagram, node, row)
            node != null -> onNode(source, diagram, node)
            diagram.kind == StructureKind.TYPES -> listOf(
                DiagramAction.Change("Add type") {
                    StructureEdits.addType(source, diagram, StructureEdits.fresh("Type", diagram.nodes.map { it.id }), point)
                },
            )
            else -> emptyList()
        }
    }

    private fun onLink(source: String, diagram: StructureDiagram, id: String): List<DiagramAction> {
        val label = diagram.links.firstOrNull { it.id == id }?.label.orEmpty().substringBefore(" [")
        return listOf(
            DiagramAction.Ask("Rename relation", label) { new ->
                DiagramAction.Change("Rename relation") { StructureEdits.renameLink(source, diagram, id, new) }
            },
            DiagramAction.Change("Delete relation") { StructureEdits.deleteLink(source, diagram, id) },
        )
    }

    private fun onRow(source: String, diagram: StructureDiagram, node: StructureNode, row: Int): List<DiagramAction> = listOf(
        DiagramAction.Ask("Edit as text", StructureEdits.rowText(source, diagram, node.id, row).orEmpty()) { new ->
            DiagramAction.Change("Edit as text") { StructureEdits.replaceRow(source, diagram, node.id, row, new) }
        },
        DiagramAction.Change("Delete") { StructureEdits.deleteRow(source, diagram, node.id, row) },
    )

    private fun onNode(source: String, diagram: StructureDiagram, node: StructureNode): List<DiagramAction> {
        val id = node.id
        val all = diagram.nodes.map { it.id }.toSet()
        val isType = diagram.kind == StructureKind.TYPES
        val isRoot = !isType && diagram.links.none { it.kind == LinkKind.CONTAINMENT && it.target == id }
        return listOfNotNull(
            DiagramAction.Ask("Rename", id) { new -> DiagramAction.Change("Rename") { StructureEdits.rename(source, diagram, id, new) } },
            DiagramAction.Ask("Add property", "name") { name ->
                DiagramAction.Change("Add property") { StructureEdits.addProperty(source, diagram, id, name) }
            },
            DiagramAction.Pick("Add has relation to", "Click the type that this type has.", all) { target ->
                DiagramAction.Change("Add relation") { StructureEdits.addRelation(source, diagram, id, LinkKind.CONTAINMENT, target) }
            }.takeIf { isType },
            DiagramAction.Pick("Add knows relation to", "Click the type that this type knows.", all) { target ->
                DiagramAction.Change("Add relation") { StructureEdits.addRelation(source, diagram, id, LinkKind.REFERENCE, target) }
            }.takeIf { isType },
            DiagramAction.Ask("Add object", "relation") { relation ->
                DiagramAction.Change("Add object") { StructureEdits.addObject(source, diagram, id, relation) }
            }.takeIf { !isType },
            DiagramAction.Pick("Add reference to", "Click the object that this object knows.", all - id) { target ->
                DiagramAction.Change("Add reference") { StructureEdits.addReference(source, diagram, id, target) }
            }.takeIf { !isType && all.size > 1 },
            DiagramAction.Change("Delete") { StructureEdits.delete(source, diagram, id) }.takeIf { !isRoot },
        )
    }
}
