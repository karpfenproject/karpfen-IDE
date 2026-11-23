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
import io.karpfen.ide.core.lang.completion.templateItem
import io.karpfen.ide.core.lang.feature.CompletionProvider
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.symbols.DocumentDeclarations
import io.karpfen.ide.core.lang.symbols.MemberKind
import io.karpfen.ide.core.lang.symbols.TypeCatalog
import io.karpfen.ide.core.lang.symbols.TypeInfo
import io.karpfen.ide.core.lang.symbols.TypeOrigin
import io.karpfen.ide.core.text.TextRange

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
 * - Before the root object it offers the `make object` snippet. After `has("k") ->` it offers the same snippet for the
 *   contained object, once a blank or a line break follows the arrow, as [KmodelSlot.HasValue] says.
 *
 * Next to these one-line snippets it offers templates of whole objects with placeholders, written by
 * [KmodelObjectTemplates]. Before the root object and after `has("k") ->` there is one template per domain type, and
 * after `has("k") ->` the target type of the relation comes first. In the body of an object of a known type there is
 * one template per `has` relation of the type, each a `has` statement with its whole contained object, leaving out the
 * relations the object has already unless they are lists. Where no type is known there is one generic template of an
 * object with one property. The templates rank after the one-line snippets, and their keys avoid the keys the document
 * uses, which [KmodelKeyScan] reads from the tokens.
 */
internal class KmodelCompletionProvider(
    private val slots: KmodelSlots,
    private val types: TypeCatalog,
) : CompletionProvider {

    override fun complete(request: FeatureRequest, offset: Int): List<CompletionItem> {
        val (context, slot) = slots.at(request.document, offset)
        val replace = context.replaceRange
        return when (slot) {
            KmodelSlot.TopLevel -> listOf(ROOT_SNIPPET.item(replace)) + topLevelTemplates(request, replace)
            is KmodelSlot.ObjectBody ->
                STATEMENT_SNIPPETS.map { it.item(replace) } + hasTemplates(request, context.innermostBlock?.range, slot, replace)
            is KmodelSlot.HasValue -> listOf(OBJECT_SNIPPET.item(replace)) + containedTemplates(request, slot, replace)
            is KmodelSlot.Key -> keys(request, slot)
            is KmodelSlot.ObjectType -> objectTypes(request, slot)
            is KmodelSlot.ObjectReference -> objects(request, slot)
            is KmodelSlot.ObjectKey, KmodelSlot.None -> emptyList()
        }
    }

    /** One template per domain type, each a whole top-level object of its type, or the generic one without types. */
    private fun topLevelTemplates(request: FeatureRequest, replace: TextRange): List<CompletionItem> {
        val domain = domainTypes(request)
        if (domain.isEmpty()) return listOf(GENERIC_OBJECT.item(replace))
        val templates = templates(request, domain)
        return domain.map { itemOf(templates.topLevelObject(it), replace, it, Snippet.TEMPLATE_RANK) }
    }

    /**
     * One template per domain type for the object that a `has` statement contains, keyed by the relation. The target
     * type of the relation comes first. Without types it is the generic object.
     */
    private fun containedTemplates(request: FeatureRequest, slot: KmodelSlot.HasValue, replace: TextRange): List<CompletionItem> {
        val domain = domainTypes(request)
        if (domain.isEmpty()) return listOf(GENERIC_OBJECT.item(replace))
        val target = slot.ownerType?.let { types.type(request, it) }?.member(slot.relation)?.typeName
        val templates = templates(request, domain)
        return domain.map { type ->
            val rank = if (type.name == target) Snippet.TEMPLATE_RANK else Snippet.TEMPLATE_RANK + 1
            itemOf(templates.containedObject(slot.relation, type.name), replace, type, rank)
        }
    }

    /**
     * One template per `has` relation of the type of the object whose body [body] is, each a `has` statement with its
     * whole contained object. A relation that the object has already is left out unless it is a list, which takes
     * another element. Where the type is unknown it is the generic `has` statement.
     */
    private fun hasTemplates(
        request: FeatureRequest,
        body: TextRange?,
        slot: KmodelSlot.ObjectBody,
        replace: TextRange,
    ): List<CompletionItem> {
        val type = slot.objectType?.let { types.type(request, it) } ?: return listOf(GENERIC_HAS.item(replace))
        val present = body?.let { KmodelKeyScan(request.document).hasKeysIn(it) }.orEmpty()
        val templates = templates(request, domainTypes(request))
        return type.members.filter { it.kind == MemberKind.HAS && (it.isList || it.name !in present) }.map { relation ->
            itemOf(templates.hasStatement(relation), replace, types.type(request, relation.typeName), Snippet.TEMPLATE_RANK)
        }
    }

    /** The domain types of the context. */
    private fun domainTypes(request: FeatureRequest): List<TypeInfo> =
        types.contextTypes(request).filter { it.origin == TypeOrigin.DOMAIN }

    /** The writer of object templates over [domain], which avoids the object keys the document uses. */
    private fun templates(request: FeatureRequest, domain: List<TypeInfo>): KmodelObjectTemplates =
        KmodelObjectTemplates(domain.associateBy { it.name }, KmodelKeyScan(request.document).objectKeys())

    /** The item of [template] at [rank]. Its documentation is the hover text of [type], the type of its object. */
    private fun itemOf(template: KmodelTemplate, replace: TextRange, type: TypeInfo?, rank: Int): CompletionItem =
        templateItem(template.label, CompletionKind.SNIPPET, template.text, replace, Snippet.TEMPLATE_DETAIL, type?.hoverText, rank)

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
        val OBJECT_SNIPPET = ROOT_SNIPPET.copy(detail = "contained object")
        val GENERIC_OBJECT = Snippet.template(
            "make object \"name\":\"Type\" { prop }",
            "make object \"\${1:name}\":\"\${2:Type}\" {",
            "    prop(\"\${3:key}\") -> \"\${4:}\"",
            "}",
        )
        val GENERIC_HAS = Snippet.template(
            "has(\"key\") -> make object \"name\":\"Type\" { prop }",
            "has(\"\${1:key}\") -> make object \"\${2:name}\":\"\${3:Type}\" {",
            "    prop(\"\${4:key}\") -> \"\${5:}\"",
            "}",
        )
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
