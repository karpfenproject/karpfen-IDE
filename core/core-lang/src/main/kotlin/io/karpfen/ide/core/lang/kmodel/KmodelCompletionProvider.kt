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

import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.lang.completion.Snippet
import io.karpfen.ide.core.lang.completion.nameItem
import io.karpfen.ide.core.lang.feature.CompletionProvider
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.symbols.DocumentDeclarations
import io.karpfen.ide.core.lang.symbols.MemberKind
import io.karpfen.ide.core.lang.symbols.TypeCatalog
import io.karpfen.ide.core.lang.symbols.TypeOrigin

/**
 * Completion in KModel.
 * - In an object body it offers the snippets `prop("…") -> "…"`, `has("…") -> make object "…":"…" { }` and
 *   `knows("…") -> "…"`.
 * - Inside `prop("` it offers the simple properties of the type of the enclosing object.
 * - Inside `has("` or `knows("` it offers the relation keys of that type. `has` offers only `has` relations, and
 *   `knows` offers only `knows` relations.
 * - At `make object "k":"` it offers types. For the value of a `has` relation the target type of the relation comes
 *   first.
 * - At `knows("r") -> "` it offers the keys of the objects in the same model whose type matches. Object types come
 *   from `SymbolDeclaration.typeName`, and members come from [types].
 * - Before the root object it offers the `make object` snippet.
 */
internal class KmodelCompletionProvider(
    private val slots: KmodelSlots,
    private val types: TypeCatalog,
) : CompletionProvider {

    override fun complete(request: FeatureRequest, offset: Int): List<CompletionItem> {
        val (context, slot) = slots.at(request.document, offset)
        return when (slot) {
            KmodelSlot.TopLevel -> listOf(ROOT_SNIPPET.item(context.replaceRange))
            is KmodelSlot.ObjectBody -> STATEMENT_SNIPPETS.map { it.item(context.replaceRange) }
            is KmodelSlot.Key -> keys(request, slot)
            is KmodelSlot.ObjectType -> objectTypes(request, slot)
            is KmodelSlot.ObjectReference -> objects(request, slot)
            is KmodelSlot.ObjectKey, KmodelSlot.None -> emptyList()
        }
    }

    /**
     * The members of the type of the enclosing object that have the kind of the slot. These are properties, `has`
     * relations or `knows` relations.
     */
    private fun keys(request: FeatureRequest, slot: KmodelSlot.Key): List<CompletionItem> {
        val type = slot.objectType?.let { types.type(request, it) } ?: return emptyList()
        return type.members.filter { it.kind == slot.kind }.map { member ->
            val kind = if (member.kind == MemberKind.PROPERTY) CompletionKind.PROPERTY else CompletionKind.RELATION
            val written = if (member.isList) "list(${member.typeName})" else member.typeName
            nameItem(member.name, kind, slot.range, detail = written, documentation = member.signature, quoted = !slot.inString)
        }
    }

    /** The domain types. The target type of the `has` relation whose value the object is comes first. */
    private fun objectTypes(request: FeatureRequest, slot: KmodelSlot.ObjectType): List<CompletionItem> {
        val preferred = slot.relation?.let { relation -> slot.ownerType?.let { types.type(request, it) }?.member(relation)?.typeName }
        return types.contextTypes(request).filter { it.origin == TypeOrigin.DOMAIN }.map { type ->
            nameItem(
                type.name,
                CompletionKind.TYPE,
                slot.range,
                detail = type.description,
                documentation = type.hoverText,
                rank = if (type.name == preferred) 0 else 1,
            )
        }
    }

    /**
     * The objects of the file whose type is the target type of the `knows` relation, or all of them if it is unknown.
     */
    private fun objects(request: FeatureRequest, slot: KmodelSlot.ObjectReference): List<CompletionItem> {
        val target = slot.ownerType?.let { types.type(request, it) }?.member(slot.relation)?.typeName?.takeIf { it.isNotEmpty() }
        return DocumentDeclarations(request.document).all(SymbolKind.OBJECT)
            .map { it.declaration }
            .filter { target == null || it.typeName == target }
            .distinctBy { it.name }
            .map { nameItem(it.name, CompletionKind.OBJECT, slot.range, detail = it.typeName) }
    }

    private companion object {
        val ROOT_SNIPPET = Snippet("make object \"…\":\"…\" { }", "make object \"\$0\":\"\" { }", detail = "root object")
        val STATEMENT_SNIPPETS = listOf(
            Snippet("prop(\"…\") -> \"…\"", "prop(\"\$0\") -> \"\"", detail = "property value"),
            Snippet(
                "has(\"…\") -> make object \"…\":\"…\" { }",
                "has(\"\$0\") -> make object \"\":\"\" { }",
                detail = "contained object",
            ),
            Snippet("knows(\"…\") -> \"…\"", "knows(\"\$0\") -> \"\"", detail = "object reference"),
        )
    }
}
