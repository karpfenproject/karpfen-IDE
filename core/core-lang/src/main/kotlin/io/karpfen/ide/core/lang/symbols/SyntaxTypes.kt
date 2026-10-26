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
package io.karpfen.ide.core.lang.symbols

import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.SymbolDeclaration
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.syntax.KarpfenSyntax

/**
 * A type as written in karpfen sources (`SymbolDeclaration.typeName`): `T`, `list(T)` or `reference(T)`, read as the
 * element type [name] and whether it is a list. Unknown wrappers keep the inner name; a missing type has the empty name.
 */
internal data class TypeExpression(val name: String, val isList: Boolean) {

    /** True if [name] is a KMeta primitive. */
    val isPrimitive: Boolean get() = name in KarpfenSyntax.PRIMITIVE_TYPES

    companion object {
        /** A wrapper call `f(T)`: the function name and the inner type. */
        private val CALL = Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*(.*?)\s*\)\s*$""")

        /** Reads [written] (null: a missing type). */
        fun parse(written: String?): TypeExpression {
            val text = written?.trim().orEmpty()
            val call = CALL.matchEntire(text) ?: return TypeExpression(text, isList = false)
            val (function, inner) = call.destructured
            return TypeExpression(inner, isList = function == "list")
        }
    }
}

/**
 * Reads [TypeInfo]s from the KMeta syntax of parse outcomes (IDE-owned, no dsl-tools): TYPE declarations with their
 * description (the TYPE outline node's detail) and their member declarations (container = the type, type name as
 * written). Used for the document's own types, as the fallback for context files that were not read, and for what a
 * partial metamodel left out.
 */
internal object SyntaxTypes {

    /** The declaration kinds of members and the member kinds they declare. */
    private val MEMBER_KINDS: Map<SymbolKind, MemberKind> = mapOf(
        SymbolKind.SIMPLE_PROPERTY to MemberKind.PROPERTY,
        SymbolKind.HAS_RELATION to MemberKind.HAS,
        SymbolKind.KNOWS_RELATION to MemberKind.KNOWS,
    )

    /** The types of [outcomes] (priority order; a type name once, from the first outcome declaring it). */
    fun read(outcomes: List<LocatedOutcome>, origin: TypeOrigin): List<TypeInfo> =
        outcomes.flatMap { read(it, origin) }.distinctBy { it.name }

    /** The types of [source], restricted to [names] when given. */
    fun read(source: LocatedOutcome, origin: TypeOrigin, names: Set<String>? = null): List<TypeInfo> {
        val index = source.outcome.index
        val descriptions = source.outcome.outline.filter { it.kind == SymbolKind.TYPE }.associateDescriptions()
        val members = index.declarations.filter { it.kind in MEMBER_KINDS }.groupBy { it.container }
        return index.declarationsOf(SymbolKind.TYPE)
            .filter { it.name.isNotEmpty() && (names == null || it.name in names) }
            .distinctBy { it.name }
            .map { type ->
                TypeInfo(
                    name = type.name,
                    description = descriptions[type.name],
                    members = members[type.name].orEmpty()
                        .filter { it.name.isNotEmpty() }
                        .distinctBy { it.name }
                        .map { member(it, source) },
                    origin = origin,
                    declaration = source.location(type.nameRange),
                )
            }
    }

    /** The member declared by [declaration] (a SIMPLE_PROPERTY, HAS_RELATION or KNOWS_RELATION). */
    private fun member(declaration: SymbolDeclaration, source: LocatedOutcome): MemberInfo {
        val type = TypeExpression.parse(declaration.typeName)
        return MemberInfo(
            name = declaration.name,
            kind = MEMBER_KINDS.getValue(declaration.kind),
            typeName = type.name,
            isList = type.isList,
            declaration = source.location(declaration.nameRange),
        )
    }

    /** The member [kind] of a declaration kind, or null for other kinds. */
    fun memberKindOf(kind: SymbolKind): MemberKind? = MEMBER_KINDS[kind]

    /** The declaration kind of a member [kind]. */
    fun declarationKindOf(kind: MemberKind): SymbolKind = MEMBER_KINDS.entries.first { it.value == kind }.key

    /** The description (outline detail) of each TYPE node by name, the first node of a name winning. */
    private fun List<OutlineNode>.associateDescriptions(): Map<String, String?> {
        val result = HashMap<String, String?>()
        forEach { node -> if (node.name !in result) result[node.name] = node.detail }
        return result
    }
}
