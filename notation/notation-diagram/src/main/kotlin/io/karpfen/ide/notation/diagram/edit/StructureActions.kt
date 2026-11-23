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
import io.karpfen.ide.notation.diagram.structure.StructureDiagram
import io.karpfen.ide.notation.diagram.structure.StructureLink
import io.karpfen.ide.notation.diagram.structure.StructureNode

/**
 * What a user can do at a point of a class diagram or an object diagram. It finds what lies at the point, a link, a
 * row of a box, a box or the empty canvas, and lets the kind of diagram tell its entries for that element:
 * [TypeActions] for a class diagram and [ObjectActions] for an object diagram. The line and the label of a link count
 * as the link. A row counts before the box it belongs to, and the title of a box counts as the box.
 *
 * Which entries an element has depends on the element alone and never on what else the file or the project holds.
 * Where an entry needs a name, a type or a value, it asks for it in a field. Where it needs another element, the user
 * clicks its box. What the user types is written as it is, also when it leaves the model in error. One entry offers a
 * list from the project: "New object of" of [ObjectActions] lists the types of the metamodel of the model in a second
 * level. The entry itself is there whatever the project holds, and only its second level follows the metamodel.
 */
public abstract class StructureActions : DiagramActions<StructureDiagram, StructureLayout> {

    final override fun at(source: String, diagram: StructureDiagram, layout: StructureLayout, point: DiagramPoint): List<DiagramAction> {
        val linkId = layout.labelAt(point) ?: layout.lineAt(point, DiagramLayout.HIT_TOLERANCE)?.id
        val link = linkId?.let { id -> diagram.links.firstOrNull { it.id == id } }
        val node = layout.boxAt(point)?.let(diagram::node)
        val row = node?.let { layout.rowAt(it.id, point) }?.takeIf { it < node.rows.size }
        return when {
            link != null -> onLink(source, diagram, link)
            node != null && row != null -> onRow(source, diagram, node, row)
            node != null -> onBox(source, diagram, node)
            else -> onCanvas(source, diagram, point)
        }
    }

    /** The entries on the empty canvas of [diagram], derived from [source], at [point]. */
    protected abstract fun onCanvas(source: String, diagram: StructureDiagram, point: DiagramPoint): List<DiagramAction>

    /** The entries on the box [node] of [diagram], derived from [source], outside its rows. */
    protected abstract fun onBox(source: String, diagram: StructureDiagram, node: StructureNode): List<DiagramAction>

    /** The entries on the row [row] of the box [node] of [diagram], derived from [source]. */
    protected abstract fun onRow(source: String, diagram: StructureDiagram, node: StructureNode, row: Int): List<DiagramAction>

    /** The entries on the line or the label of [link] of [diagram], derived from [source]. */
    protected abstract fun onLink(source: String, diagram: StructureDiagram, link: StructureLink): List<DiagramAction>

    /** The entry that renames the box [node] through [rename], in a field that starts with its name. */
    protected fun renamingBox(
        source: String,
        diagram: StructureDiagram,
        node: StructureNode,
        rename: (String, StructureDiagram, String, String) -> DiagramChange?,
    ): DiagramAction {
        val name = source.substring(node.nameRange.start, node.nameRange.end)
        return DiagramAction.Ask(RENAME, name) { new -> DiagramAction.Change(RENAME) { rename(source, diagram, node.id, new) } }
    }

    /** The entry that opens the whole box [node] in a code cell titled with the title of the box. */
    protected fun editingBox(source: String, node: StructureNode): DiagramAction =
        DiagramAction.Open(EDIT_AS_TEXT, ConstructCells.of(node.id, node.title, source, node.range))

    /** The entry [label] that gives the statement behind [member] another key, in a field that starts with its key. */
    protected fun renamingMember(source: String, diagram: StructureDiagram, member: StructureMember, label: String): DiagramAction =
        DiagramAction.Ask(label, StructureEdits.keyOf(source, diagram, member).orEmpty()) { new ->
            DiagramAction.Change(label) { StructureEdits.renameMember(source, diagram, member, new) }
        }

    /** The entry that opens the statement behind [link] in a code cell titled `Relation <name>`. */
    protected fun editingLink(source: String, link: StructureLink): DiagramAction =
        DiagramAction.Open(EDIT_AS_TEXT, ConstructCells.of(link.id, "Relation ${link.label.substringBefore(" [")}", source, link.range))

    /** The entry that edits the statement of the row [row] of [node] as text, in a field that holds it on one line. */
    protected fun editingRow(source: String, diagram: StructureDiagram, node: StructureNode, row: Int): DiagramAction =
        DiagramAction.Ask(EDIT_AS_TEXT, StructureEdits.rowText(source, diagram, node.id, row).orEmpty()) { new ->
            DiagramAction.Change(EDIT_AS_TEXT) { StructureEdits.replaceRow(source, diagram, node.id, row, new) }
        }

    /** The entry [label] that deletes the statement of the row [row] of [node]. */
    protected fun deletingRow(source: String, diagram: StructureDiagram, node: StructureNode, row: Int, label: String): DiagramAction =
        DiagramAction.Change(label) { StructureEdits.deleteRow(source, diagram, node.id, row) }

    /**
     * The entries that let the statement of the row [row] of [node] change places with the row above it and the row
     * below it, each where there is such a row.
     */
    protected fun movingRow(source: String, diagram: StructureDiagram, node: StructureNode, row: Int): List<DiagramAction> =
        listOfNotNull(
            DiagramAction.Change(MOVE_UP) { StructureEdits.moveRow(source, diagram, node.id, row, up = true) }
                .takeIf { StructureEdits.canMoveRow(diagram, node.id, row, up = true) },
            DiagramAction.Change(MOVE_DOWN) { StructureEdits.moveRow(source, diagram, node.id, row, up = false) }
                .takeIf { StructureEdits.canMoveRow(diagram, node.id, row, up = false) },
        )

    protected companion object {
        /** The entry that renames a box or the key of a property. */
        public const val RENAME: String = "Rename"

        /** The entry that renames a relation. */
        public const val RENAME_RELATION: String = "Rename relation"

        /** The entry that opens or edits a construct as text. */
        public const val EDIT_AS_TEXT: String = "Edit as text"

        /** The entry that deletes a box or a property. */
        public const val DELETE: String = "Delete"

        /** The entry that deletes a relation. */
        public const val DELETE_RELATION: String = "Delete relation"

        /** The entry that adds a property. */
        public const val ADD_PROPERTY: String = "Add property"

        /** The entry that swaps a row with the row above it. */
        public const val MOVE_UP: String = "Move up"

        /** The entry that swaps a row with the row below it. */
        public const val MOVE_DOWN: String = "Move down"

        /** The entry that sets the type of a property or of an object. */
        public const val SET_TYPE: String = "Set type"
    }
}
