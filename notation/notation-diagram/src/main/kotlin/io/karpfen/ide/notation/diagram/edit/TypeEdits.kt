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
import io.karpfen.ide.notation.diagram.structure.StructureDiagram
import io.karpfen.ide.notation.diagram.structure.StructureKind
import io.karpfen.ide.notation.diagram.text.TextBlocks

/**
 * The changes of a metamodel that its class diagram offers, each as edits of the text: types with their description,
 * their `behaves after` clause and their place as the root type, and the rules of a type, which are its properties
 * and its relations. Every function takes the text and the class diagram that was derived from exactly that text, and
 * gives the change or null if there is nothing to change. [StructureEdits] holds the changes that the object diagram
 * shares.
 *
 * A type or a state machine that a rule or a clause names is written as the user typed it. Whether the metamodel
 * declares the type, whether the type of a property is a primitive type and whether the state machine exists is
 * checked by dsl-tools, which reports what is wrong.
 */
public object TypeEdits {

    /** The keyword of a `has` relation. */
    private const val HAS = "has"

    /** The keyword of a `knows` relation. */
    private const val KNOWS = "knows"

    /** The keyword of a property. */
    private const val PROP = "prop"

    /** The type a new property gets. */
    private const val STRING = "string"

    /**
     * Adds the type [name] to a metamodel, drawn with its middle at [at]. It becomes the first type of the file,
     * because the last type of a metamodel is its root type, which a new type must not take over.
     */
    public fun addType(text: String, diagram: StructureDiagram, name: String, at: DiagramPoint? = null): DiagramChange? {
        if (!diagram.isTypes || diagram.node(name) != null) return null
        val first = diagram.nodes.minByOrNull { it.range.start }
        val start = first?.let { text.lastIndexOf('\n', it.range.start - 1) + 1 } ?: text.length
        val before = if (first == null && text.isNotEmpty() && !text.endsWith("\n")) "\n" else ""
        val edit = TextEdit(TextRange(start, start), "${before}type ${quoted(name)} \"\" {\n}\n")
        return DiagramChange(listOf(edit), arranges = at != null) { vis ->
            if (at == null) vis else vis.copy(boxes = vis.boxes + (clean(name) to DiagramGrid.snap(at)))
        }
    }

    /**
     * Renames the type [id] to [new], and every relation of the file that leads to it. The type keeps its place in the
     * layout, and so do the waypoints and labels of its links, unless the new name is empty or another type has it.
     */
    public fun rename(text: String, diagram: StructureDiagram, id: String, new: String): DiagramChange? {
        if (!diagram.isTypes) return null
        return StructureEdits.renameBox(text, diagram, id, new, diagram.links.filter { it.target == id })
    }

    /** Gives the type [id] the property [name] of the type `string`, as its last rule. */
    public fun addProperty(text: String, diagram: StructureDiagram, id: String, name: String): DiagramChange? {
        if (!diagram.isTypes) return null
        return StructureEdits.addStatement(text, diagram, id, "$PROP(${quoted(name)}, ${quoted(STRING)})")
    }

    /**
     * Gives the type [id] a relation to the type [target]: a `has` relation for a containment and a `knows` relation
     * for a reference. The relation is named after the target with a lower-case first letter, with a number behind it
     * where the type has that name already.
     */
    public fun addRelation(text: String, diagram: StructureDiagram, id: String, kind: LinkKind, target: String): DiagramChange? {
        if (!diagram.isTypes || diagram.node(target) == null) return null
        val name = StructureEdits.fresh(target.replaceFirstChar { it.lowercase() }, StructureEdits.membersOf(diagram, id))
        return StructureEdits.addStatement(text, diagram, id, "${word(kind)}(${quoted(name)}, ${quoted(target)})")
    }

    /**
     * Gives the rule behind [member] the type [type]: the primitive type of a property or the target type of a
     * relation. A rule that holds a list keeps holding one. The type is written as it is typed, whether the metamodel
     * declares it or not.
     */
    public fun setType(text: String, diagram: StructureDiagram, member: StructureMember, type: String): DiagramChange? {
        val value = partsOf(text, diagram, member)?.value?.takeIf { diagram.isTypes } ?: return null
        val name = clean(type)
        return if (text.substring(value.start, value.end) == name) null else DiagramChange(listOf(TextEdit(value, name)))
    }

    /** True if the rule behind [member] holds a list, which its type `list("type")` says. */
    public fun isList(text: String, diagram: StructureDiagram, member: StructureMember): Boolean =
        diagram.isTypes && partsOf(text, diagram, member)?.list != null

    /**
     * Makes the rule behind [member] hold a list, if [list], by writing `list(...)` around its quoted type, or a single
     * value, by taking `list(...)` away.
     */
    public fun setList(text: String, diagram: StructureDiagram, member: StructureMember, list: Boolean): DiagramChange? {
        val parts = partsOf(text, diagram, member)?.takeIf { diagram.isTypes } ?: return null
        val value = parts.value ?: return null
        val quotedType = text.substring(value.start - 1, value.end + 1)
        val around = parts.list
        val edit = when {
            list && around == null -> TextEdit(TextRange(value.start - 1, value.end + 1), "list($quotedType)")
            !list && around != null -> TextEdit(around, quotedType)
            else -> return null
        }
        return DiagramChange(listOf(edit))
    }

    /**
     * Makes the relation behind [member] a relation of the kind [kind]: `has` for a containment and `knows` for a
     * reference. Only the keyword changes. A property is no relation and stays as it is.
     */
    public fun setKind(text: String, diagram: StructureDiagram, member: StructureMember, kind: LinkKind): DiagramChange? {
        val word = partsOf(text, diagram, member)?.word?.takeIf { diagram.isTypes } ?: return null
        val now = text.substring(word.start, word.end)
        if (now != HAS && now != KNOWS || now == word(kind)) return null
        return DiagramChange(listOf(TextEdit(word, word(kind))))
    }

    /** The kind of the relation behind [member], or null if it is no relation. */
    public fun kindOf(text: String, diagram: StructureDiagram, member: StructureMember): LinkKind? {
        val word = partsOf(text, diagram, member)?.word?.takeIf { diagram.isTypes } ?: return null
        return when (text.substring(word.start, word.end)) {
            HAS -> LinkKind.CONTAINMENT
            KNOWS -> LinkKind.REFERENCE
            else -> null
        }
    }

    /** The description of the type [id], which is the second text of its declaration, or null if it has none. */
    public fun descriptionOf(text: String, diagram: StructureDiagram, id: String): String? =
        description(text, diagram, id)?.let { text.substring(it.start, it.end) }

    /** Sets the description of the type [id] to [description]. */
    public fun describe(text: String, diagram: StructureDiagram, id: String, description: String): DiagramChange? {
        val range = description(text, diagram, id) ?: return null
        val new = clean(description)
        return if (new == text.substring(range.start, range.end)) null else DiagramChange(listOf(TextEdit(range, new)))
    }

    /**
     * Lets the type [id] behave after the attachable state machine [machine]: it changes the name in the `behaves after`
     * clause of the type, or adds the clause after the description if the type has none. A blank [machine] removes the
     * clause together with the blanks before it.
     */
    public fun behaveAfter(text: String, diagram: StructureDiagram, id: String, machine: String): DiagramChange? {
        val node = diagram.node(id)?.takeIf { diagram.isTypes } ?: return null
        val name = clean(machine)
        val behaviour = node.behaviour
        val edit = when {
            behaviour == null && name.isEmpty() -> return null
            behaviour == null -> {
                val end = description(text, diagram, id)?.end ?: return null
                TextEdit(TextRange(end + 1, end + 1), " behaves after ${quoted(name)}")
            }
            name.isEmpty() -> {
                val start = text.substring(0, behaviour.range.start).trimEnd().length
                TextEdit(TextRange(start, behaviour.range.end), "")
            }
            name == behaviour.machine -> return null
            else -> TextEdit(behaviour.machineRange, name)
        }
        return DiagramChange(listOf(edit))
    }

    /** True if the type [id] is the root type of its metamodel, which is the last type of the file. */
    public fun isRoot(diagram: StructureDiagram, id: String): Boolean = diagram.nodes.maxByOrNull { it.range.start }?.id == id

    /**
     * Makes the type [id] the root type of its metamodel, which is the last type of the file. The type moves to the
     * end of the file with everything it holds.
     */
    public fun makeRoot(text: String, diagram: StructureDiagram, id: String): DiagramChange? {
        val node = diagram.node(id) ?: return null
        val last = diagram.nodes.maxByOrNull { it.range.start } ?: return null
        if (!diagram.isTypes || last.id == id) return null
        val moved = TextBlocks.lifted(text, node.range)
        val ordered = listOf(TextBlocks.removal(text, node.range), TextEdit(TextRange(last.range.end, last.range.end), "\n$moved"))
        return if (StructureEdits.overlap(ordered[0], ordered[1])) null else DiagramChange(ordered)
    }

    /** Deletes the type [id] with every relation of another type that leads to it. */
    public fun delete(text: String, diagram: StructureDiagram, id: String): DiagramChange? {
        val node = diagram.node(id)?.takeIf { diagram.isTypes } ?: return null
        val relations = diagram.links.filter { it.target == id && it.source != id }
        return DiagramChange(listOf(TextBlocks.removal(text, node.range)) + relations.map { TextBlocks.removal(text, it.range) })
    }

    /** Deletes the relation behind the link [id]. */
    public fun deleteLink(text: String, diagram: StructureDiagram, id: String): DiagramChange? {
        val link = diagram.links.firstOrNull { it.id == id }?.takeIf { diagram.isTypes } ?: return null
        return DiagramChange(listOf(TextBlocks.removal(text, link.range)))
    }

    /** The inside of the string literal that holds the description of the type [id]. */
    private fun description(text: String, diagram: StructureDiagram, id: String): TextRange? {
        val node = diagram.node(id)?.takeIf { diagram.isTypes } ?: return null
        val body = bodyOf(text, node) ?: return null
        val header = node.nameRange.end + 1
        return StatementParts.stringsIn(text.substring(header, body.start), header).firstOrNull()
    }

    private fun word(kind: LinkKind): String = if (kind == LinkKind.CONTAINMENT) HAS else KNOWS

    /** True if the boxes of this diagram are types. */
    private val StructureDiagram.isTypes: Boolean
        get() = kind == StructureKind.TYPES
}
