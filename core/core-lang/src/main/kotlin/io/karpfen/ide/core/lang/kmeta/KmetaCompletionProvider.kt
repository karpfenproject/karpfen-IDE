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
package io.karpfen.ide.core.lang.kmeta

import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.lang.completion.Snippet
import io.karpfen.ide.core.lang.completion.nameItem
import io.karpfen.ide.core.lang.feature.CompletionProvider
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.symbols.TypeCatalog
import io.karpfen.ide.core.lang.symbols.TypeOrigin

/**
 * Completion in KMeta. At top level it offers the `type "Name" "Description" { }` snippet. In a type body it offers the
 * `prop`, `has`, `knows` and `list` snippets. At the second argument of `prop(` it offers `number`, `string` and
 * `boolean`. At the type argument of `has(`, `knows(` or `list(` it offers the types of [types]. These are the types
 * declared in the file, plus the domain types of the context when the file is `EVENTS.kmeta`. Outside quotes, names
 * are inserted with quotes, and `list("…")` is offered as well.
 */
internal class KmetaCompletionProvider(
    private val slots: KmetaSlots,
    private val types: TypeCatalog,
) : CompletionProvider {

    override fun complete(request: FeatureRequest, offset: Int): List<CompletionItem> {
        val (context, slot) = slots.at(request.document, offset)
        val replace = context.replaceRange
        return when (slot) {
            KmetaSlot.TopLevel -> listOf(TYPE_SNIPPET.item(replace))
            is KmetaSlot.TypeBody -> RULE_SNIPPETS.map { it.item(replace) }
            is KmetaSlot.TypeArgument -> {
                val names = if (slot.primitive) primitives(slot) else typeNames(request, slot)
                names + if (slot.listAllowed && !slot.inString) listOf(LIST_SNIPPET.item(replace, rank = 2)) else emptyList()
            }
            is KmetaSlot.TypeName, is KmetaSlot.MemberName, KmetaSlot.None -> emptyList()
        }
    }

    /** The primitive types for a `prop` type argument. */
    private fun primitives(slot: KmetaSlot.TypeArgument): List<CompletionItem> =
        KarpfenSyntax.PRIMITIVE_TYPES.sorted().map { nameItem(it, CompletionKind.PRIMITIVE_TYPE, slot.range, quoted = !slot.inString) }

    /** The types of the document, plus the domain types in an events document. The document's own types come first. */
    private fun typeNames(request: FeatureRequest, slot: KmetaSlot.TypeArgument): List<CompletionItem> {
        val origins = if (request.isEventsDocument) setOf(TypeOrigin.DOCUMENT, TypeOrigin.DOMAIN) else setOf(TypeOrigin.DOCUMENT)
        return types.types(request).filter { it.origin in origins }.map { type ->
            nameItem(
                type.name,
                CompletionKind.TYPE,
                slot.range,
                detail = type.description,
                documentation = type.hoverText,
                rank = if (type.origin == TypeOrigin.DOCUMENT) 0 else 1,
                quoted = !slot.inString,
            )
        }
    }

    private companion object {
        val TYPE_SNIPPET = Snippet("type \"Name\" \"Description\" { }", "type \"\$0\" \"\" { }", detail = "type definition")
        val LIST_SNIPPET = Snippet("list(\"…\")", "list(\"\$0\")", detail = "list of a type")
        val RULE_SNIPPETS = listOf(
            Snippet("prop(\"…\", \"…\")", "prop(\"\$0\", \"\")", detail = "simple property"),
            Snippet("has(\"…\", \"…\")", "has(\"\$0\", \"\")", detail = "containment relation"),
            Snippet("knows(\"…\", \"…\")", "knows(\"\$0\", \"\")", detail = "reference relation"),
            LIST_SNIPPET,
        )
    }
}
