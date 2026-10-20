package io.karpfen.ide.core.lang.kmeta

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.lang.completion.Snippet
import io.karpfen.ide.core.lang.completion.nameItem
import io.karpfen.ide.core.lang.feature.CompletionProvider
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.symbols.MemberInfo
import io.karpfen.ide.core.lang.symbols.TypeCatalog
import io.karpfen.ide.core.lang.symbols.TypeOrigin

/**
 * KMeta completion (spec §5): top level → `type "Name" "Description" { }` snippet; in a type body → `prop`, `has`,
 * `knows`, `list` snippets; second argument of `prop(` → `number string boolean`; type argument of
 * `has(`/`knows(`/`list(` → the types of [types] (declared in the file plus, when editing `EVENTS.kmeta`, the
 * context's domain types). Outside quotes, names are inserted quoted and `list("…")` is offered as well.
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

    private fun primitives(slot: KmetaSlot.TypeArgument): List<CompletionItem> =
        MemberInfo.PRIMITIVE_TYPES.sorted().map { nameItem(it, CompletionKind.PRIMITIVE_TYPE, slot.range, quoted = !slot.inString) }

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
