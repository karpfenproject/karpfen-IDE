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
 * What a user can do at a point of a class diagram.
 *
 * - On the empty canvas: add a type.
 * - On a type: rename it, edit it as text, describe it, set or take away the state machine it behaves after, add a
 *   property, add a `has` or a `knows` relation to a type the user clicks, which may be the type itself, make it the
 *   root type unless it is, and delete it.
 * - On a property row: rename its key, set its type, make it a list or a single value, move it up or down among the
 *   rows, edit it as text and delete it.
 * - On a relation, drawn as a line or, where its target type is not declared in the file, as a row: rename it, set its
 *   target type, make it a `has` or a `knows` relation, make it a list or a single value, edit it as text and delete it.
 *   A relation drawn as a row also moves up and down among the rows.
 * - On the row of a `behaves after` clause: set the state machine and take the clause away.
 *
 * A type and a state machine are typed as text and are not looked up, so a relation can lead to a type that does not
 * exist yet. dsl-tools reports such a name until the type exists.
 */
public object TypeActions : StructureActions() {

    /** The entry that sets or takes away the state machine a type behaves after. */
    private const val BEHAVES_AFTER = "Behaves after"

    /** The entry that sets the target type of a relation. */
    private const val SET_TARGET_TYPE = "Set target type"

    /** The name a new type gets, with a number behind it where that name is taken. */
    private const val TYPE_NAME = "Type"

    override fun onCanvas(source: String, diagram: StructureDiagram, point: DiagramPoint): List<DiagramAction> = listOf(
        DiagramAction.Change("Add type") {
            TypeEdits.addType(source, diagram, StructureEdits.fresh(TYPE_NAME, diagram.nodes.map { it.id }), point)
        },
    )

    override fun onBox(source: String, diagram: StructureDiagram, node: StructureNode): List<DiagramAction> {
        val id = node.id
        val all = diagram.nodes.map { it.id }.toSet()
        return listOfNotNull(
            renamingBox(source, diagram, node, TypeEdits::rename),
            editingBox(source, node),
            DiagramAction.Ask("Describe", TypeEdits.descriptionOf(source, diagram, id).orEmpty()) { description ->
                DiagramAction.Change("Describe") { TypeEdits.describe(source, diagram, id, description) }
            },
            behavingAfter(source, diagram, node),
            DiagramAction.Ask(ADD_PROPERTY, "name") { name ->
                DiagramAction.Change(ADD_PROPERTY) { TypeEdits.addProperty(source, diagram, id, name) }
            },
            DiagramAction.Pick("Add has relation to", "Click the type that this type has.", all) { target ->
                DiagramAction.Change("Add relation") { TypeEdits.addRelation(source, diagram, id, LinkKind.CONTAINMENT, target) }
            },
            DiagramAction.Pick("Add knows relation to", "Click the type that this type knows.", all) { target ->
                DiagramAction.Change("Add relation") { TypeEdits.addRelation(source, diagram, id, LinkKind.REFERENCE, target) }
            },
            DiagramAction.Change("Make root type") { TypeEdits.makeRoot(source, diagram, id) }.takeIf { !TypeEdits.isRoot(diagram, id) },
            DiagramAction.Change(DELETE) { TypeEdits.delete(source, diagram, id) },
        )
    }

    override fun onRow(source: String, diagram: StructureDiagram, node: StructureNode, row: Int): List<DiagramAction> {
        val member = StructureMember.Row(node.id, row)
        return when (node.rowKinds.getOrNull(row)) {
            RowKind.BEHAVIOUR -> listOf(
                behavingAfter(source, diagram, node),
                DiagramAction.Change("Remove behaves after") { TypeEdits.behaveAfter(source, diagram, node.id, "") },
            )
            RowKind.PROPERTY -> listOf(
                renamingMember(source, diagram, member, RENAME),
                settingType(source, diagram, member, SET_TYPE),
                listing(source, diagram, member),
            ) + movingRow(source, diagram, node, row) + listOf(
                editingRow(source, diagram, node, row),
                deletingRow(source, diagram, node, row, DELETE),
            )
            RowKind.CONTAINMENT, RowKind.REFERENCE -> relationEntries(source, diagram, member) + movingRow(source, diagram, node, row) + listOf(
                editingRow(source, diagram, node, row),
                deletingRow(source, diagram, node, row, DELETE_RELATION),
            )
            null -> emptyList()
        }
    }

    override fun onLink(source: String, diagram: StructureDiagram, link: StructureLink): List<DiagramAction> =
        relationEntries(source, diagram, StructureMember.Link(link.id)) + listOf(
            editingLink(source, link),
            DiagramAction.Change(DELETE_RELATION) { TypeEdits.deleteLink(source, diagram, link.id) },
        )

    /**
     * The entries that every relation has, drawn as a line or as a row: rename it, set its target type, change its kind
     * and make it a list or a single value.
     */
    private fun relationEntries(source: String, diagram: StructureDiagram, member: StructureMember): List<DiagramAction> {
        val kind = TypeEdits.kindOf(source, diagram, member)
        val other = if (kind == LinkKind.CONTAINMENT) LinkKind.REFERENCE else LinkKind.CONTAINMENT
        return listOf(
            renamingMember(source, diagram, member, RENAME_RELATION),
            settingType(source, diagram, member, SET_TARGET_TYPE),
            DiagramAction.Change(if (other == LinkKind.CONTAINMENT) "Make has relation" else "Make knows relation") {
                TypeEdits.setKind(source, diagram, member, other)
            },
            listing(source, diagram, member),
        )
    }

    /**
     * The entry [label] that gives the rule behind [member] the type the user types, in a field that starts with its
     * type without `list`.
     */
    private fun settingType(source: String, diagram: StructureDiagram, member: StructureMember, label: String): DiagramAction =
        DiagramAction.Ask(label, StructureEdits.valueOf(source, diagram, member).orEmpty()) { type ->
            DiagramAction.Change(label) { TypeEdits.setType(source, diagram, member, type) }
        }

    /** The entry that makes the rule behind [member] a list if it holds a single value, and a single value if a list. */
    private fun listing(source: String, diagram: StructureDiagram, member: StructureMember): DiagramAction {
        val isList = TypeEdits.isList(source, diagram, member)
        return DiagramAction.Change(if (isList) "Make single value" else "Make list") { TypeEdits.setList(source, diagram, member, !isList) }
    }

    /**
     * The entry that lets the type [node] behave after the state machine the user types, in a field that starts with
     * the name of the one it behaves after or empty. An empty field takes the clause away.
     */
    private fun behavingAfter(source: String, diagram: StructureDiagram, node: StructureNode): DiagramAction =
        DiagramAction.Ask(BEHAVES_AFTER, node.behaviour?.machine.orEmpty()) { machine ->
            DiagramAction.Change(BEHAVES_AFTER) { TypeEdits.behaveAfter(source, diagram, node.id, machine) }
        }
}
