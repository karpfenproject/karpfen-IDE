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

import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.notation.diagram.layout.VisLayout
import io.karpfen.ide.notation.diagram.structure.RowKind
import io.karpfen.ide.notation.diagram.structure.StructureDiagram
import io.karpfen.ide.notation.diagram.structure.StructureLink
import io.karpfen.ide.notation.diagram.structure.StructureNode
import io.karpfen.ide.notation.diagram.text.TextBlocks

/**
 * The changes that the class diagram and the object diagram share, each as edits of the text: the statements of a box,
 * their keys and values, and their order. [TypeEdits] holds what only a metamodel has and [ObjectEdits] what only a
 * model has. Every function takes the text and the diagram that was derived from exactly that text, and gives the
 * change or null if there is nothing to change.
 *
 * An edit touches only the lines it is about and writes what the user typed, trimmed and with a `"` turned into `'`.
 * It does not check what it writes against the language. A key that is empty or taken, or a name that nothing
 * declares, is written as it is, and the validation of dsl-tools reports it.
 */
public object StructureEdits {

    private val LINK_ID = Regex("""^(has|knows):(.*)\.([^.#]*)(#\d+)?$""")

    /** A name that is not in [taken]: [base], or [base] with the first free number behind it. */
    public fun fresh(base: String, taken: Collection<String>): String =
        generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base$it" }.first { it !in taken }

    /** Adds [statement] as the last statement of the box [id]. */
    public fun addStatement(text: String, diagram: StructureDiagram, id: String, statement: String): DiagramChange? {
        val body = diagram.node(id)?.let { bodyOf(text, it) } ?: return null
        return DiagramChange(listOf(TextBlocks.append(text, body, statement)))
    }

    /** The key of the statement behind [member], as it stands in the text, or null if it has none. */
    public fun keyOf(text: String, diagram: StructureDiagram, member: StructureMember): String? =
        partsOf(text, diagram, member)?.key?.let { text.substring(it.start, it.end) }

    /**
     * The second string of the statement behind [member], as it stands in the text: the type of a rule of a type,
     * without `list`, the value of a property of an object, or the key that a `knows` of an object names. Null for a
     * statement without one, such as a `has` of an object.
     */
    public fun valueOf(text: String, diagram: StructureDiagram, member: StructureMember): String? =
        partsOf(text, diagram, member)?.value?.let { text.substring(it.start, it.end) }

    /**
     * Gives the statement behind [member] the key [new]. A link keeps its waypoints and its label place under its new
     * id.
     */
    public fun renameMember(text: String, diagram: StructureDiagram, member: StructureMember, new: String): DiagramChange? {
        val key = partsOf(text, diagram, member)?.key ?: return null
        val name = clean(new)
        if (text.substring(key.start, key.end) == name) return null
        val layout: ((VisLayout) -> VisLayout)? = (member as? StructureMember.Link)?.let { link ->
            { vis ->
                fun <V> Map<String, V>.lines(): Map<String, V> = mapKeys { if (it.key == link.id) relinked(link.id, newKey = name) else it.key }
                vis.copy(waypoints = vis.waypoints.lines(), labels = vis.labels.lines())
            }
        }
        return DiagramChange(listOf(TextEdit(key, name)), layout = layout)
    }

    /** The text of the statement that the row [row] of the box [id] shows, as it stands in the text. */
    public fun rowText(text: String, diagram: StructureDiagram, id: String, row: Int): String? =
        diagram.node(id)?.rowRanges?.getOrNull(row)?.let { text.substring(it.start, it.end) }

    /** Puts [statement] in the place of the statement that the row [row] of the box [id] shows. */
    public fun replaceRow(text: String, diagram: StructureDiagram, id: String, row: Int, statement: String): DiagramChange? {
        val range = diagram.node(id)?.rowRanges?.getOrNull(row) ?: return null
        val new = statement.trim()
        return if (new.isEmpty() || new == text.substring(range.start, range.end)) null else DiagramChange(listOf(TextEdit(range, new)))
    }

    /**
     * Deletes the statement that the row [row] of the box [id] shows, with its line if nothing else stands on it. The
     * row of a `behaves after` clause is no statement, and [TypeEdits.behaveAfter] takes the clause out.
     */
    public fun deleteRow(text: String, diagram: StructureDiagram, id: String, row: Int): DiagramChange? {
        val node = diagram.node(id) ?: return null
        if (node.rowKinds.getOrNull(row) == RowKind.BEHAVIOUR) return null
        return node.rowRanges.getOrNull(row)?.let { DiagramChange(listOf(TextBlocks.removal(text, it))) }
    }

    /**
     * True if the statement of the row [row] of the box [id] can change places with the statement of the row above it,
     * if [up], or below it. Both rows must be statements, which the row of a `behaves after` clause is not.
     */
    public fun canMoveRow(diagram: StructureDiagram, id: String, row: Int, up: Boolean): Boolean {
        val kinds = diagram.node(id)?.rowKinds ?: return false
        val other = if (up) row - 1 else row + 1
        return row in kinds.indices && other in kinds.indices && kinds[row] != RowKind.BEHAVIOUR && kinds[other] != RowKind.BEHAVIOUR
    }

    /**
     * Lets the statement of the row [row] of the box [id] change places with the statement of the row above it, if
     * [up], or below it. The two statements swap their texts, and every statement between them, which the diagram draws
     * as a link, stays where it is. The order matters for the values of a list, which keep the order of the text.
     */
    public fun moveRow(text: String, diagram: StructureDiagram, id: String, row: Int, up: Boolean): DiagramChange? {
        if (!canMoveRow(diagram, id, row, up)) return null
        val ranges = diagram.node(id)?.rowRanges ?: return null
        val (first, second) = if (up) ranges[row - 1] to ranges[row] else ranges[row] to ranges[row + 1]
        val firstText = text.substring(first.start, first.end)
        val secondText = text.substring(second.start, second.end)
        if (firstText == secondText) return null
        return DiagramChange(listOf(TextEdit(first, secondText), TextEdit(second, firstText)))
    }

    /**
     * Gives the box [id] the name [new] in its declaration and in the statements [incoming], whose second string names
     * the box. The box keeps its place in the layout, and so do the waypoints and labels of its links, as long as the
     * new name is one no other box has. A name that is empty or taken is written all the same, and the box is then
     * placed anew.
     */
    internal fun renameBox(text: String, diagram: StructureDiagram, id: String, new: String, incoming: List<StructureLink>): DiagramChange? {
        val node = diagram.node(id) ?: return null
        val name = clean(new)
        if (text.substring(node.nameRange.start, node.nameRange.end) == name) return null
        val uses = incoming.mapNotNull { link -> StatementParts.of(text, link.range)?.value?.let { TextEdit(it, name) } }
        val keepsPlace = name.isNotEmpty() && diagram.node(name) == null
        return DiagramChange(listOf(TextEdit(node.nameRange, name)) + uses, layout = if (!keepsPlace) null else { vis ->
            fun <V> Map<String, V>.lines(): Map<String, V> = mapKeys { relinked(it.key, source = id, newSource = name) }
            VisLayout(vis.boxes.mapKeys { if (it.key == id) name else it.key }, vis.bars, vis.waypoints.lines(), vis.labels.lines(), vis.frames)
        })
    }

    /** The parts of the statement behind [member], or null if [diagram] has no such member. */
    internal fun partsOf(text: String, diagram: StructureDiagram, member: StructureMember): StatementParts? =
        member.rangeIn(diagram)?.let { StatementParts.of(text, it) }

    /**
     * The names of the members of the box [id]: of its relations that are drawn as links, and of its rows. The row of a
     * `behaves after` clause names no member.
     */
    internal fun membersOf(diagram: StructureDiagram, id: String): Set<String> {
        val node = diagram.node(id)
        val rows = node?.rows.orEmpty().filterIndexed { index, _ -> node?.rowKinds?.getOrNull(index) != RowKind.BEHAVIOUR }
        return diagram.links.filter { it.source == id }.mapNotNull { LINK_ID.matchEntire(it.id)?.groupValues?.get(3) }.toSet() +
            rows.map { it.substringBefore(':').substringBefore('=').trim() }
    }

    /** The braces of the box [node] with everything between them. The search starts behind the quote that ends its name. */
    internal fun bodyOf(text: String, node: StructureNode): TextRange? = TextBlocks.blockFrom(text, node.nameRange.end + 1)

    /** [name] as the text writes it: trimmed, and with a `"` turned into `'`. */
    internal fun clean(name: String): String = name.trim().replace('"', '\'')

    /** [name] as the text writes it, in quotes. */
    internal fun quoted(name: String): String = "\"" + clean(name) + "\""

    /** True if the edits [first] and [second] touch the same characters or one inserts inside what the other replaces. */
    internal fun overlap(first: TextEdit, second: TextEdit): Boolean {
        val (a, b) = if (first.range.start <= second.range.start) first.range to second.range else second.range to first.range
        return b.start < a.end
    }

    /**
     * The id of a link with its source renamed from [source] to [newSource], or with its relation renamed to
     * [newKey]. An id of another form, or of another source, stays as it is.
     */
    private fun relinked(id: String, source: String? = null, newSource: String? = null, newKey: String? = null): String {
        val match = LINK_ID.matchEntire(id) ?: return id
        val (word, from, key, ordinal) = match.destructured
        return when {
            newKey != null -> "$word:$from.$newKey$ordinal"
            from == source && newSource != null -> "$word:$newSource.$key$ordinal"
            else -> id
        }
    }
}
