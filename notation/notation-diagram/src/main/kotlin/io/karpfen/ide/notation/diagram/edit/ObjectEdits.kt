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
import io.karpfen.ide.notation.diagram.edit.StructureEdits.bodyOf
import io.karpfen.ide.notation.diagram.edit.StructureEdits.clean
import io.karpfen.ide.notation.diagram.edit.StructureEdits.partsOf
import io.karpfen.ide.notation.diagram.edit.StructureEdits.quoted
import io.karpfen.ide.notation.diagram.layout.DiagramGrid
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.structure.LinkKind
import io.karpfen.ide.notation.diagram.structure.RowKind
import io.karpfen.ide.notation.diagram.structure.StructureDiagram
import io.karpfen.ide.notation.diagram.structure.StructureKind
import io.karpfen.ide.notation.diagram.structure.StructureLink
import io.karpfen.ide.notation.diagram.text.TextBlocks

/**
 * The changes of a model that its object diagram offers, each as edits of the text: the root object, the objects
 * embedded in it with their keys and types, where an object is embedded, and the statements of an object, which are
 * its property values, its embedded objects and its references. Every function takes the text and the object diagram
 * that was derived from exactly that text, and gives the change or null if there is nothing to change.
 * [StructureEdits] holds the changes that the class diagram shares.
 *
 * A type, a key or a value is written as the user typed it. Whether the metamodel has the type, whether a key is taken
 * or names an object, and whether a value fits its property is checked by dsl-tools, which reports what is wrong.
 */
public object ObjectEdits {

    /** The key the root object gets when it is added. */
    public const val ROOT_KEY: String = "object"

    /** The relation an added reference gets, with a number behind it where the object has that name already. */
    private const val REFERENCE = "ref"

    /** The words that make an object. */
    private const val MAKE_OBJECT = "make object"

    /**
     * Adds the root object `make object "object":"" { }` to a model that has no object, drawn with its middle at [at].
     * Its type is empty until it is set.
     */
    public fun addRoot(text: String, diagram: StructureDiagram, at: DiagramPoint? = null): DiagramChange? {
        if (!diagram.isObjects || diagram.nodes.isNotEmpty()) return null
        val before = if (text.isNotEmpty() && !text.endsWith("\n")) "\n" else ""
        val edit = TextEdit(TextRange(text.length, text.length), "$before$MAKE_OBJECT ${quoted(ROOT_KEY)}:\"\" { }\n")
        return DiagramChange(listOf(edit), arranges = at != null) { vis ->
            if (at == null) vis else vis.copy(boxes = vis.boxes + (ROOT_KEY to DiagramGrid.snap(at)))
        }
    }

    /**
     * Gives the object [id] the key [new], and every `knows` of the file that leads to it. The object keeps its place in
     * the layout, and so do the waypoints and labels of its links, unless the new key is empty or another object has
     * it.
     */
    public fun rename(text: String, diagram: StructureDiagram, id: String, new: String): DiagramChange? {
        if (!diagram.isObjects) return null
        return StructureEdits.renameBox(text, diagram, id, new, diagram.links.filter { it.target == id && it.kind == LinkKind.REFERENCE })
    }

    /** The type of the object [id] as it stands in the text, or null if [diagram] has no such object. */
    public fun typeOf(text: String, diagram: StructureDiagram, id: String): String? =
        typeRange(text, diagram, id)?.let { text.substring(it.start, it.end) }

    /** Gives the object [id] the type [type], whether the metamodel has that type or not. */
    public fun setType(text: String, diagram: StructureDiagram, id: String, type: String): DiagramChange? {
        val range = typeRange(text, diagram, id) ?: return null
        val name = clean(type)
        return if (text.substring(range.start, range.end) == name) null else DiagramChange(listOf(TextEdit(range, name)))
    }

    /** Gives the object [id] the property [name] with the empty value, `prop("name") -> ""`, as its last statement. */
    public fun addProperty(text: String, diagram: StructureDiagram, id: String, name: String): DiagramChange? {
        if (!diagram.isObjects) return null
        return StructureEdits.addStatement(text, diagram, id, "prop(${quoted(name)}) -> \"\"")
    }

    /**
     * Adds a value to the property of the row [row] of the object [id], for a property that holds a list: a statement
     * `prop("key") -> ""` with the same key goes right after the statement of the row, on a line of its own.
     */
    public fun addValue(text: String, diagram: StructureDiagram, id: String, row: Int): DiagramChange? {
        val node = diagram.node(id)?.takeIf { diagram.isObjects && it.rowKinds.getOrNull(row) == RowKind.PROPERTY } ?: return null
        val range = node.rowRanges[row]
        val key = StatementParts.of(text, range)?.key ?: return null
        val statement = "prop(\"${text.substring(key.start, key.end)}\") -> \"\""
        return DiagramChange(listOf(TextEdit(TextRange(range.end, range.end), "\n" + TextBlocks.indentOf(text, range.start) + statement)))
    }

    /**
     * Gives the statement behind [member] the value [value]: the value of a property, or the key of the object a
     * `knows` leads to. A `has` statement makes an object and has no such value.
     */
    public fun setValue(text: String, diagram: StructureDiagram, member: StructureMember, value: String): DiagramChange? {
        val range = partsOf(text, diagram, member)?.value?.takeIf { diagram.isObjects } ?: return null
        val new = clean(value)
        return if (text.substring(range.start, range.end) == new) null else DiagramChange(listOf(TextEdit(range, new)))
    }

    /**
     * Gives the object [id] a new object of the type [type] through the `has` relation [relation]. The new object is
     * named after the relation, with a number behind it where an object has that key already.
     */
    public fun addObject(text: String, diagram: StructureDiagram, id: String, relation: String, type: String): DiagramChange? {
        if (!diagram.isObjects) return null
        val key = StructureEdits.fresh(clean(relation), diagram.nodes.map { it.id })
        return StructureEdits.addStatement(text, diagram, id, "has(${quoted(relation)}) -> $MAKE_OBJECT ${quoted(key)}:${quoted(type)} { }")
    }

    /** Gives the object [id] a reference to the object [target] through a `knows` relation named `ref`. */
    public fun addReference(text: String, diagram: StructureDiagram, id: String, target: String): DiagramChange? {
        if (!diagram.isObjects || diagram.node(target) == null) return null
        val name = StructureEdits.fresh(REFERENCE, StructureEdits.membersOf(diagram, id))
        return StructureEdits.addStatement(text, diagram, id, "knows(${quoted(name)}) -> ${quoted(target)}")
    }

    /** True if the object [id] is the root object of its model, which no `has` statement makes. */
    public fun isRoot(diagram: StructureDiagram, id: String): Boolean = madeBy(diagram, id) == null

    /** The object [id] and every object it contains, directly or further down. */
    public fun contained(diagram: StructureDiagram, id: String): Set<String> {
        val found = LinkedHashSet<String>()
        val pending = ArrayDeque(listOf(id))
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (found.add(next)) pending += diagram.links.filter { it.kind == LinkKind.CONTAINMENT && it.source == next }.map { it.target }
        }
        return found
    }

    /**
     * Moves the object [id] into the object [owner]: the `has` statement that makes it goes, with the object and
     * everything in it, to the end of the body of [owner], under the same relation. Its lines get the indentation of
     * their new place. The root object is made by no statement and does not move, and no object moves into itself or
     * into an object it contains. Moving an object into the object that has it already puts it last there.
     */
    public fun moveInto(text: String, diagram: StructureDiagram, id: String, owner: String): DiagramChange? {
        val made = madeBy(diagram, id)?.takeIf { diagram.isObjects } ?: return null
        val target = diagram.node(owner)?.takeIf { owner !in contained(diagram, id) } ?: return null
        val body = bodyOf(text, target) ?: return null
        val removal = TextBlocks.removal(text, made.range)
        val insertion = TextBlocks.append(text, body, TextBlocks.lifted(text, made.range))
        return if (StructureEdits.overlap(removal, insertion)) null else DiagramChange(listOf(removal, insertion))
    }

    /**
     * Deletes the object [id] with the `has` statement that makes it, with the objects it contains and with every
     * reference to one of them. The root object cannot be deleted, because a model without it is no model.
     */
    public fun delete(text: String, diagram: StructureDiagram, id: String): DiagramChange? {
        val made = madeBy(diagram, id)?.takeIf { diagram.isObjects } ?: return null
        val gone = contained(diagram, id)
        val references = diagram.links.filter { it.kind == LinkKind.REFERENCE && it.target in gone && it.source !in gone }
        return DiagramChange(listOf(TextBlocks.removal(text, made.range)) + references.map { TextBlocks.removal(text, it.range) })
    }

    /** Deletes the statement behind the link [id]. A `has` statement goes with the object it makes, as by [delete]. */
    public fun deleteLink(text: String, diagram: StructureDiagram, id: String): DiagramChange? {
        val link = diagram.links.firstOrNull { it.id == id }?.takeIf { diagram.isObjects } ?: return null
        if (link.kind == LinkKind.CONTAINMENT) return delete(text, diagram, link.target)
        return DiagramChange(listOf(TextBlocks.removal(text, link.range)))
    }

    /** The `has` statement that makes the object [id], or null for the root object. */
    private fun madeBy(diagram: StructureDiagram, id: String): StructureLink? =
        diagram.links.firstOrNull { it.kind == LinkKind.CONTAINMENT && it.target == id }

    /** The inside of the second string of `make object "key":"Type"` of the object [id], which is its type. */
    private fun typeRange(text: String, diagram: StructureDiagram, id: String): TextRange? {
        val node = diagram.node(id)?.takeIf { diagram.isObjects } ?: return null
        val body = bodyOf(text, node) ?: return null
        val header = node.nameRange.end + 1
        return StatementParts.stringsIn(text.substring(header, body.start), header).firstOrNull()
    }

    /** True if the boxes of this diagram are objects. */
    private val StructureDiagram.isObjects: Boolean
        get() = kind == StructureKind.OBJECTS
}
