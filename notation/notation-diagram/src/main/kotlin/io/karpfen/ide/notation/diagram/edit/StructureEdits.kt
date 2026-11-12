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
import io.karpfen.ide.notation.diagram.layout.DiagramGrid
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.VisLayout
import io.karpfen.ide.notation.diagram.structure.LinkKind
import io.karpfen.ide.notation.diagram.structure.StructureDiagram
import io.karpfen.ide.notation.diagram.structure.StructureKind
import io.karpfen.ide.notation.diagram.structure.StructureNode
import io.karpfen.ide.notation.diagram.text.TextBlocks

/**
 * The changes of a metamodel and of a model that the class diagram and the object diagram offer, each as edits of the
 * text. Every function takes the text and the diagram that was derived from exactly that text, and gives the change
 * or null if there is nothing to change. The diagram says by its kind whether its boxes are types or objects.
 *
 * An edit touches only the lines it is about. A new statement goes to the end of the block of its box, on a line of
 * its own.
 */
public object StructureEdits {

    private val STRING = Regex(""""([^"]*)"""")
    private val LINK_ID = Regex("""^(has|knows):(.*)\.([^.#]*)(#\d+)?$""")

    /** A name that is not in [taken]: [base], or [base] with the first free number behind it. */
    public fun fresh(base: String, taken: Collection<String>): String =
        generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base$it" }.first { it !in taken }

    /**
     * Adds the type [name] to a metamodel, drawn with its middle at [at]. It becomes the first type of the file,
     * because the last type of a metamodel is its root type, which a new type must not take over.
     */
    public fun addType(text: String, diagram: StructureDiagram, name: String, at: DiagramPoint? = null): DiagramChange? {
        if (diagram.kind != StructureKind.TYPES || name.isBlank() || diagram.node(name) != null) return null
        val first = diagram.nodes.minByOrNull { it.range.start }
        val start = first?.let { text.lastIndexOf('\n', it.range.start - 1) + 1 } ?: text.length
        val before = if (first == null && text.isNotEmpty() && !text.endsWith("\n")) "\n" else ""
        val edit = TextEdit(TextRange(start, start), "${before}type ${quoted(name)} \"\" {\n}\n")
        return DiagramChange(listOf(edit), arranges = at != null) { vis ->
            if (at == null) vis else vis.copy(boxes = vis.boxes + (name to DiagramGrid.snap(at)))
        }
    }

    /**
     * Renames the box [id] to [new]: the type with every relation that leads to it, or the object with every
     * reference that leads to it. The box keeps its place in the layout, and so do the waypoints and labels of its
     * links.
     */
    public fun rename(text: String, diagram: StructureDiagram, id: String, new: String): DiagramChange? {
        val node = diagram.node(id) ?: return null
        val name = clean(new)
        if (name.isEmpty() || name == id || diagram.node(name) != null) return null
        val incoming = diagram.links.filter { it.target == id && (diagram.kind == StructureKind.TYPES || it.kind == LinkKind.REFERENCE) }
        val uses = incoming.mapNotNull { link -> strings(text, link.range).lastOrNull()?.let { TextEdit(it, name) } }
        return DiagramChange(listOf(TextEdit(node.nameRange, name)) + uses) { vis ->
            fun <V> Map<String, V>.lines(): Map<String, V> = mapKeys { relinked(it.key, source = id, newSource = name) }
            VisLayout(vis.boxes.mapKeys { if (it.key == id) name else it.key }, vis.bars, vis.waypoints.lines(), vis.labels.lines(), vis.frames)
        }
    }

    /** Adds [statement] as the last statement of the box [id]. */
    public fun addStatement(text: String, diagram: StructureDiagram, id: String, statement: String): DiagramChange? {
        val body = diagram.node(id)?.let { bodyOf(text, it) } ?: return null
        return DiagramChange(listOf(TextBlocks.append(text, body, statement)))
    }

    /** Gives the type or object [id] the property [name]: a text property for a type, an empty value for an object. */
    public fun addProperty(text: String, diagram: StructureDiagram, id: String, name: String): DiagramChange? {
        val key = clean(name).ifEmpty { return null }
        val statement = if (diagram.kind == StructureKind.TYPES) "prop(${quoted(key)}, \"string\")" else "prop(${quoted(key)}) -> \"\""
        return addStatement(text, diagram, id, statement)
    }

    /**
     * Gives the type [id] a relation to the type [target]: a `has` relation for a containment and a `knows` relation
     * for a reference. The relation is named after the target, with a number behind it where the type has that name
     * already.
     */
    public fun addRelation(text: String, diagram: StructureDiagram, id: String, kind: LinkKind, target: String): DiagramChange? {
        if (diagram.kind != StructureKind.TYPES || diagram.node(target) == null) return null
        val name = fresh(target.replaceFirstChar { it.lowercase() }, membersOf(diagram, id))
        return addStatement(text, diagram, id, "${word(kind)}(${quoted(name)}, ${quoted(target)})")
    }

    /**
     * Gives the object [id] a new object through the `has` relation [relation]. The new object is named after the
     * relation and has the type `Type`, which the user then sets.
     */
    public fun addObject(text: String, diagram: StructureDiagram, id: String, relation: String): DiagramChange? {
        val key = clean(relation).ifEmpty { return null }
        if (diagram.kind != StructureKind.OBJECTS) return null
        val name = fresh(key, diagram.nodes.map { it.id })
        return addStatement(text, diagram, id, "has(${quoted(key)}) -> make object ${quoted(name)}:\"Type\" { }")
    }

    /** Gives the object [id] a reference to the object [target] through a `knows` relation named `ref`. */
    public fun addReference(text: String, diagram: StructureDiagram, id: String, target: String): DiagramChange? {
        if (diagram.kind != StructureKind.OBJECTS || diagram.node(target) == null) return null
        return addStatement(text, diagram, id, "knows(${quoted(fresh("ref", membersOf(diagram, id)))}) -> ${quoted(target)}")
    }

    /**
     * Deletes the box [id]. A type goes with every relation that leads to it. An object goes with the `has` statement
     * that makes it, with the objects it contains and with every reference to one of them. The outermost object of a
     * model cannot be deleted.
     */
    public fun delete(text: String, diagram: StructureDiagram, id: String): DiagramChange? {
        val node = diagram.node(id) ?: return null
        if (diagram.kind == StructureKind.TYPES) {
            val relations = diagram.links.filter { it.target == id && it.source != id }
            return DiagramChange(listOf(TextBlocks.removal(text, node.range)) + relations.map { TextBlocks.removal(text, it.range) })
        }
        val made = diagram.links.firstOrNull { it.kind == LinkKind.CONTAINMENT && it.target == id } ?: return null
        val gone = contained(diagram, id)
        val references = diagram.links.filter { it.kind == LinkKind.REFERENCE && it.target in gone && it.source !in gone }
        return DiagramChange(listOf(TextBlocks.removal(text, made.range)) + references.map { TextBlocks.removal(text, it.range) })
    }

    /** Deletes the relation behind the link [id]. In a model, a `has` statement goes with the object it makes. */
    public fun deleteLink(text: String, diagram: StructureDiagram, id: String): DiagramChange? {
        val link = diagram.links.firstOrNull { it.id == id } ?: return null
        if (diagram.kind == StructureKind.OBJECTS && link.kind == LinkKind.CONTAINMENT) return delete(text, diagram, link.target)
        return DiagramChange(listOf(TextBlocks.removal(text, link.range)))
    }

    /** Renames the relation behind the link [id] to [new]. The link keeps its waypoints and its label place. */
    public fun renameLink(text: String, diagram: StructureDiagram, id: String, new: String): DiagramChange? {
        val link = diagram.links.firstOrNull { it.id == id } ?: return null
        val name = clean(new).ifEmpty { return null }
        val key = strings(text, link.range).firstOrNull() ?: return null
        if (text.substring(key.start, key.end) == name) return null
        return DiagramChange(listOf(TextEdit(key, name))) { vis ->
            fun <V> Map<String, V>.lines(): Map<String, V> = mapKeys { if (it.key == id) relinked(id, newKey = name) else it.key }
            vis.copy(waypoints = vis.waypoints.lines(), labels = vis.labels.lines())
        }
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

    /** Deletes the statement that the row [row] of the box [id] shows. */
    public fun deleteRow(text: String, diagram: StructureDiagram, id: String, row: Int): DiagramChange? =
        diagram.node(id)?.rowRanges?.getOrNull(row)?.let { DiagramChange(listOf(TextBlocks.removal(text, it))) }

    /** The names of the members of the box [id]: of its relations that are drawn as links, and of its rows. */
    private fun membersOf(diagram: StructureDiagram, id: String): Set<String> =
        diagram.links.filter { it.source == id }.mapNotNull { LINK_ID.matchEntire(it.id)?.groupValues?.get(3) }.toSet() +
            diagram.node(id)?.rows.orEmpty().map { it.substringBefore(':').substringBefore('=').trim() }

    /** The object [id] and every object it contains, directly or further down. */
    private fun contained(diagram: StructureDiagram, id: String): Set<String> {
        val found = LinkedHashSet<String>()
        val pending = ArrayDeque(listOf(id))
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (found.add(next)) pending += diagram.links.filter { it.kind == LinkKind.CONTAINMENT && it.source == next }.map { it.target }
        }
        return found
    }

    /** The braces of the box [node] with everything between them. The search starts behind the quote that ends its name. */
    private fun bodyOf(text: String, node: StructureNode): TextRange? = TextBlocks.blockFrom(text, node.nameRange.end + 1)

    /** The insides of the string literals in [range] of [text], in order. A statement of a nested object is left out. */
    private fun strings(text: String, range: TextRange): List<TextRange> {
        val statement = text.substring(range.start, range.end).substringBefore("make object")
        return STRING.findAll(statement).map { TextRange(range.start + it.range.first + 1, range.start + it.range.last) }.toList()
    }

    private fun word(kind: LinkKind): String = if (kind == LinkKind.CONTAINMENT) "has" else "knows"

    private fun clean(name: String): String = name.trim().replace('"', '\'')

    private fun quoted(name: String): String = "\"" + clean(name) + "\""

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
