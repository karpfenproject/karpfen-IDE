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
package io.karpfen.ide.core.lang.kmodel

import io.karpfen.dsl.meta.SimplePropertyType
import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.lang.completion.TemplateText
import io.karpfen.ide.core.lang.completion.TemplateTextBuilder
import io.karpfen.ide.core.lang.symbols.MemberInfo
import io.karpfen.ide.core.lang.symbols.MemberKind
import io.karpfen.ide.core.lang.symbols.TypeInfo

/**
 * A KModel template and the one line the completion list shows for it.
 *
 * @property label the line of the list, such as `make object "lamp":"Lamp" { … }`.
 * @property text the inserted text with its placeholders.
 */
internal data class KmodelTemplate(val label: String, val text: TemplateText)

/**
 * Writes the KModel templates of whole objects. An object of a type holds one statement for each member of the type,
 * in the order the type declares them, four spaces deeper per level:
 * - a property as `prop("key") -> "default"`, the default being `0` for a number, `false` for a boolean and empty
 *   otherwise;
 * - a `has` relation as `has("key") -> make object "key":"Target" { … }` with the members of the target type inside;
 * - a `knows` relation as `knows("key") -> ""`.
 *
 * A list member gets exactly one statement, which is one element, as KModel writes a list with one statement per
 * element. Each template opens a type at most once on its way down, and it writes the members of objects at most
 * [depthLimit] levels deep. A contained object whose type is already open, whose type is unknown, or that lies one
 * level deeper than that, has an empty body, so every member of a written object is there.
 *
 * The placeholders are the object keys and the values of the properties and `knows` relations, in the order they are
 * written. A top-level object is keyed by its type name with a small first letter, and a contained object by its
 * relation. A key that [usedKeys] or the template itself already holds gets the first free number from 2 on, so no two
 * objects share a key once the template is inserted.
 *
 * The same writer gives an object as plain text without placeholders, which [KmodelObjectWriter] hands to the object
 * diagram, so the completion of the text editor and the diagram write one and the same object for a type.
 *
 * The class is immutable. Each template is written by a writer of its own, so the keys one template takes do not
 * affect another.
 *
 * @param types the types by name that objects may have.
 * @param usedKeys the object keys the document uses already.
 * @param depthLimit the most levels of objects whose members a template writes, the outermost one included.
 */
internal class KmodelObjectTemplates(
    private val types: Map<String, TypeInfo>,
    private val usedKeys: Set<String>,
    private val depthLimit: Int = DEPTH_LIMIT,
) {
    init {
        require(depthLimit >= 1) { "a template has at least one level, not $depthLimit" }
    }

    /** The template of a top-level object of [type]. */
    fun topLevelObject(type: TypeInfo): KmodelTemplate = Writer().run { objectTemplate(rootKeyOf(type.name), type.name) }

    /** The template of an object of [typeName] that is the value of the `has` relation [relation]. */
    fun containedObject(relation: String, typeName: String): KmodelTemplate = Writer().run { objectTemplate(relation, typeName) }

    /**
     * The object [key] of [typeName] as plain text, the same text the template of [containedObject] inserts, with the
     * key it got. The text starts with `make object` and ends with the closing brace of the object.
     */
    fun plainObject(key: String, typeName: String): KmodelObjectText = Writer().run {
        val written = obj(key, typeName, open = emptySet(), level = 1, indent = "")
        KmodelObjectText(written, builder.build().text)
    }

    /**
     * The `has` statement of the relation [relation] with the object of [typeName] as plain text, the object keyed by
     * the relation as in [plainObject]. The relation and the key are escaped alike, so they stay equal in the text for
     * any name. The text starts with `has(` and ends with the closing brace of the object.
     */
    fun plainHas(relation: String, typeName: String): KmodelObjectText = Writer().run {
        builder.literal("has(${quote(relation)}) -> ")
        val written = obj(relation, typeName, open = emptySet(), level = 1, indent = "")
        KmodelObjectText(written, builder.build().text)
    }

    /** The template of the `has` statement of [relation] with its whole contained object. */
    fun hasStatement(relation: MemberInfo): KmodelTemplate = Writer().run {
        builder.literal("has(${quote(relation.name)}) -> ")
        val key = obj(relation.name, relation.typeName, open = emptySet(), level = 1, indent = "")
        KmodelTemplate("has(${quote(relation.name)}) -> ${objectLabel(key, relation.typeName)}", builder.build())
    }

    /**
     * Writes the templates of one insertion. It holds the builder and the keys the template has taken so far, and it is
     * confined to one call of a template function.
     */
    private inner class Writer {
        val builder = TemplateTextBuilder()
        private val keys = HashSet(usedKeys)

        /** The template of the object [key] of [typeName], with its label. */
        fun objectTemplate(key: String, typeName: String): KmodelTemplate {
            val written = obj(key, typeName, open = emptySet(), level = 1, indent = "")
            return KmodelTemplate(objectLabel(written, typeName), builder.build())
        }

        /**
         * Writes the object [key] of [typeName] at [level], whose closing brace is indented by [indent]. [open] holds the
         * types of the objects around it. Returns the key it wrote.
         */
        fun obj(key: String, typeName: String, open: Set<String>, level: Int, indent: String): String {
            val written = freeKey(key)
            builder.literal("make object \"").placeholder(escape(written)).literal("\":${quote(typeName)} {")
            val type = types[typeName]?.takeIf { typeName !in open && level <= depthLimit }
            if (type == null || type.members.isEmpty()) {
                builder.literal(" }")
                return written
            }
            val inner = indent + INDENT
            for (member in type.members) {
                builder.literal("\n$inner")
                member(member, open + typeName, level, inner)
            }
            builder.literal("\n$indent}")
            return written
        }

        /** Writes the statement of [member] in an object at [level] whose statements are indented by [indent]. */
        private fun member(member: MemberInfo, open: Set<String>, level: Int, indent: String) {
            when (member.kind) {
                MemberKind.PROPERTY ->
                    builder.literal("prop(${quote(member.name)}) -> \"").placeholder(defaultOf(member)).literal("\"")
                MemberKind.KNOWS -> builder.literal("knows(${quote(member.name)}) -> \"").placeholder("").literal("\"")
                MemberKind.HAS -> {
                    builder.literal("has(${quote(member.name)}) -> ")
                    obj(member.name, member.typeName, open, level + 1, indent)
                }
            }
        }

        /**
         * [key] if neither the document nor this template uses it, or else [key] with the first free number from 2. The
         * empty key stays empty, since it marks an object without an id, of which a model may hold several.
         */
        private fun freeKey(key: String): String {
            if (key.isEmpty()) return key
            val free = if (key !in keys) key else generateSequence(2) { it + 1 }.map { "$key$it" }.first { it !in keys }
            keys += free
            return free
        }
    }

    /** Constants and the texts the templates share. */
    companion object {
        /**
         * The most levels of objects whose members a template writes. The payload templates of the runtime session write
         * the members of as many levels.
         */
        const val DEPTH_LIMIT: Int = 4

        /** The indentation of one level. */
        private const val INDENT = "    "

        /** The key of a top-level object of [typeName]: the type name with a small first letter. */
        fun rootKeyOf(typeName: String): String = typeName.replaceFirstChar(Char::lowercaseChar)

        /** The label of an object [key] of [typeName]. */
        fun objectLabel(key: String, typeName: String): String = "make object ${quote(key)}:${quote(typeName)} { … }"

        /**
         * The default value of the property [member] as KModel writes it, without quotes. The primitive type is the one
         * of dsl-tools that [KarpfenSyntax.primitiveType] names, so a type dsl-tools adds or renames is never written
         * with the default of another type.
         */
        private fun defaultOf(member: MemberInfo): String = when (KarpfenSyntax.primitiveType(member.typeName)) {
            SimplePropertyType.NUMBER -> "0"
            SimplePropertyType.BOOLEAN -> "false"
            SimplePropertyType.STRING, null -> ""
        }

        /** [text] in double quotes, with quotes and backslashes escaped. */
        private fun quote(text: String): String = "\"${escape(text)}\""

        /** [text] with quotes and backslashes escaped for a KModel string. */
        private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
