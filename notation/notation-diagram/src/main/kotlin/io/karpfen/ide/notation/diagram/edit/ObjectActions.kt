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

import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.structure.LinkKind
import io.karpfen.ide.notation.diagram.structure.RowKind
import io.karpfen.ide.notation.diagram.structure.StructureDiagram
import io.karpfen.ide.notation.diagram.structure.StructureLink
import io.karpfen.ide.notation.diagram.structure.StructureNode

/**
 * What a user can do at a point of an object diagram.
 *
 * - On the empty canvas of a model without an object: add the root object.
 * - On an object: rename its key, edit it as text, set its type, add a property, add an embedded object, for which
 *   the user types the relation and then the type, add a reference to an object the user clicks, which may be the
 *   object itself, move it into another object the user clicks, and delete it. The root object neither moves nor goes.
 * - On a property row: set its value, rename its key, add another value of the same key after it, move it up or down
 *   among the rows, edit it as text and delete it.
 * - On a `knows`, drawn as a line or, where no object has the key it names, as a row: rename the relation, set the key
 *   of the object it leads to, edit it as text and delete it. A `knows` drawn as a row also moves up and down among
 *   the rows.
 * - On a `has`: rename the relation, edit it as text and delete it with the object it makes.
 *
 * A type and the key of an object a `knows` leads to are typed as text and are not looked up, so a reference can name an
 * object that does not exist yet. dsl-tools reports such a name until the object exists.
 */
public object ObjectActions : StructureActions() {

    /** The entry that sets the key of the object a `knows` leads to. */
    private const val SET_TARGET = "Set target"

    /** The entry that adds an embedded object. */
    private const val ADD_OBJECT = "Add object"

    override fun onCanvas(source: String, diagram: StructureDiagram, point: DiagramPoint): List<DiagramAction> = listOfNotNull(
        DiagramAction.Change("Add root object") { ObjectEdits.addRoot(source, diagram, point) }.takeIf { diagram.nodes.isEmpty() },
    )

    override fun onBox(source: String, diagram: StructureDiagram, node: StructureNode): List<DiagramAction> {
        val id = node.id
        val all = diagram.nodes.map { it.id }.toSet()
        val isRoot = ObjectEdits.isRoot(diagram, id)
        return listOfNotNull(
            renamingBox(source, diagram, node, ObjectEdits::rename),
            editingBox(source, node),
            DiagramAction.Ask(SET_TYPE, ObjectEdits.typeOf(source, diagram, id).orEmpty()) { type ->
                DiagramAction.Change(SET_TYPE) { ObjectEdits.setType(source, diagram, id, type) }
            },
            DiagramAction.Ask(ADD_PROPERTY, "name") { name ->
                DiagramAction.Change(ADD_PROPERTY) { ObjectEdits.addProperty(source, diagram, id, name) }
            },
            DiagramAction.Ask(ADD_OBJECT, "relation") { relation ->
                DiagramAction.Ask(ADD_OBJECT, "Type") { type ->
                    DiagramAction.Change(ADD_OBJECT) { ObjectEdits.addObject(source, diagram, id, relation, type) }
                }
            },
            DiagramAction.Pick("Add reference to", "Click the object that this object knows.", all) { target ->
                DiagramAction.Change("Add reference") { ObjectEdits.addReference(source, diagram, id, target) }
            },
            DiagramAction.Pick("Move into", "Click the object that gets this object.", all - ObjectEdits.contained(diagram, id)) { owner ->
                DiagramAction.Change("Move into") { ObjectEdits.moveInto(source, diagram, id, owner) }
            }.takeIf { !isRoot },
            DiagramAction.Change(DELETE) { ObjectEdits.delete(source, diagram, id) }.takeIf { !isRoot },
        )
    }

    override fun onRow(source: String, diagram: StructureDiagram, node: StructureNode, row: Int): List<DiagramAction> {
        val member = StructureMember.Row(node.id, row)
        return when (node.rowKinds.getOrNull(row)) {
            RowKind.PROPERTY -> listOf(
                settingValue(source, diagram, member, "Set value"),
                renamingMember(source, diagram, member, RENAME),
                DiagramAction.Change("Add value") { ObjectEdits.addValue(source, diagram, node.id, row) },
            ) + movingRow(source, diagram, node, row) + listOf(
                editingRow(source, diagram, node, row),
                deletingRow(source, diagram, node, row, DELETE),
            )
            RowKind.REFERENCE -> listOf(
                renamingMember(source, diagram, member, RENAME_RELATION),
                settingValue(source, diagram, member, SET_TARGET),
            ) + movingRow(source, diagram, node, row) + listOf(
                editingRow(source, diagram, node, row),
                deletingRow(source, diagram, node, row, DELETE_RELATION),
            )
            RowKind.BEHAVIOUR, RowKind.CONTAINMENT, null -> emptyList()
        }
    }

    override fun onLink(source: String, diagram: StructureDiagram, link: StructureLink): List<DiagramAction> {
        val member = StructureMember.Link(link.id)
        return listOfNotNull(
            renamingMember(source, diagram, member, RENAME_RELATION),
            settingValue(source, diagram, member, SET_TARGET).takeIf { link.kind == LinkKind.REFERENCE },
            editingLink(source, link),
            DiagramAction.Change(DELETE_RELATION) { ObjectEdits.deleteLink(source, diagram, link.id) },
        )
    }

    /**
     * The entry [label] that gives the statement behind [member] the value the user types, in a field that starts with
     * its value: the value of a property or the key a `knows` names.
     */
    private fun settingValue(source: String, diagram: StructureDiagram, member: StructureMember, label: String): DiagramAction =
        DiagramAction.Ask(label, StructureEdits.valueOf(source, diagram, member).orEmpty()) { value ->
            DiagramAction.Change(label) { ObjectEdits.setValue(source, diagram, member, value) }
        }
}
