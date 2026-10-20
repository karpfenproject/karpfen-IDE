package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.lang.completion.nameItem
import io.karpfen.ide.core.lang.context.CaretContext
import io.karpfen.ide.core.lang.feature.CompletionProvider
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.modelpath.PathMember
import io.karpfen.ide.core.lang.modelpath.PathOrigin
import io.karpfen.ide.core.lang.symbols.DocumentDeclarations
import io.karpfen.ide.core.lang.symbols.MemberInfo
import io.karpfen.ide.core.lang.symbols.MemberKind
import io.karpfen.ide.core.lang.symbols.TypeCatalog
import io.karpfen.ide.core.lang.symbols.TypeOrigin
import io.karpfen.ide.core.text.TextRange

/**
 * KStates completion (spec §5): structural keywords by position (STATE, INITIAL STATE, ENTRY, DO, TRANSITION, SPLIT,
 * JOIN, NOT LOOPING, CONDITION, EVENT, EVAL, VALUE, MACRO, TAKES, RETURNS, DEFINITION, action operations, IF IN
 * SCOPE, WITH … AS; [KstatesKeywords]); state names inside transition strings; macro names inside `MACRO("` and after
 * `@` in EVAL; `ATTACHED TO "` → domain types ([types]); TAKES/RETURNS → domain types and primitives; model paths in
 * action/VALUE/IF IN SCOPE/macro-argument strings and after `$(` in EVAL (via [paths], the segment under the caret
 * replaced); the relation argument of SETOBJ/APPENDOBJ → relations of the source path's type; event names in the second
 * argument of `EVENT(` from `EVENTS.kmeta`; event domains used in the file.
 */
internal class KstatesCompletionProvider(
    private val slots: KstatesSlots,
    private val paths: KstatesPaths,
    private val types: TypeCatalog,
) : CompletionProvider {

    override fun complete(request: FeatureRequest, offset: Int): List<CompletionItem> {
        val (context, slot) = slots.at(request.document, offset)
        return when (slot) {
            is KstatesSlot.Keywords -> KstatesKeywords.at(slot.position).map { it.item(context.replaceRange) }
            is KstatesSlot.StateName -> if (slot.isDeclaration) emptyList() else states(request, slot.range)
            is KstatesSlot.MacroName -> if (slot.isDeclaration) emptyList() else macros(request, slot.range)
            is KstatesSlot.TypeName -> typeNames(request, slot)
            is KstatesSlot.ModelPath -> pathSegments(request, context, slot.range)
            is KstatesSlot.RelationKey -> relations(request, context, slot)
            is KstatesSlot.EventName -> eventNames(request, slot.range)
            is KstatesSlot.EventDomain -> eventDomains(request, slot.range)
            is KstatesSlot.BindingName, is KstatesSlot.ParameterName, KstatesSlot.None -> emptyList()
        }
    }

    private fun states(request: FeatureRequest, range: TextRange): List<CompletionItem> =
        DocumentDeclarations(request.document).all(SymbolKind.STATE).map { it.declaration }.distinctBy { it.name }.map {
            nameItem(it.name, CompletionKind.STATE, range, detail = it.container?.let { parent -> "in $parent" })
        }

    private fun macros(request: FeatureRequest, range: TextRange): List<CompletionItem> {
        val declarations = DocumentDeclarations(request.document)
        return declarations.all(SymbolKind.MACRO).distinctBy { it.declaration.name }.map {
            nameItem(it.declaration.name, CompletionKind.MACRO, range, detail = KstatesSignatures.macro(declarations, it.declaration.name))
        }
    }

    private fun typeNames(request: FeatureRequest, slot: KstatesSlot.TypeName): List<CompletionItem> {
        val domain = types.contextTypes(request).filter { it.origin == TypeOrigin.DOMAIN }.map { type ->
            nameItem(type.name, CompletionKind.TYPE, slot.range, type.description, type.hoverText, rank = 0, quoted = !slot.inString)
        }
        val primitives = if (slot.primitives) {
            MemberInfo.PRIMITIVE_TYPES.sorted().map {
                nameItem(it, CompletionKind.PRIMITIVE_TYPE, slot.range, rank = 1, quoted = !slot.inString)
            }
        } else {
            emptyList()
        }
        val wrappers = slot.wrappers.mapNotNull { KstatesKeywords.WRAPPERS[it]?.item(slot.range, rank = 2) }
        return domain + primitives + wrappers
    }

    private fun pathSegments(request: FeatureRequest, context: CaretContext, pathRange: TextRange): List<CompletionItem> {
        val caret = context.offset.coerceIn(pathRange.start, pathRange.end)
        val prefix = request.document.text.text.substring(pathRange.start, caret)
        val replace = TextRange(maxOf(context.wordRange.start, pathRange.start), maxOf(caret, minOf(context.wordRange.end, pathRange.end)))
        return paths.completions(request, context, prefix).map { segment(it, replace) }
    }

    private fun segment(member: PathMember, replace: TextRange): CompletionItem {
        val kind = if (member.origin == PathOrigin.BINDING) CompletionKind.BINDING else CompletionKind.PATH_SEGMENT
        val rank = when (member.origin) {
            PathOrigin.BINDING -> 0
            PathOrigin.MEMBER -> 1
            PathOrigin.ROOT -> 2
        }
        val detail = KstatesSignatures.typeText(member.type)
        return nameItem(member.name, kind, replace, detail = detail, documentation = member.member?.signature, rank = rank)
    }

    private fun relations(request: FeatureRequest, context: CaretContext, slot: KstatesSlot.RelationKey): List<CompletionItem> {
        val source = paths.typeOf(request, context, slot.sourcePath) ?: return emptyList()
        return source.members.filter { it.kind != MemberKind.PROPERTY }.map {
            nameItem(it.name, CompletionKind.RELATION, slot.range, detail = KstatesSignatures.typeText(it), documentation = it.signature)
        }
    }

    private fun eventNames(request: FeatureRequest, range: TextRange): List<CompletionItem> =
        types.contextTypes(request).filter { it.origin == TypeOrigin.EVENTS }.map {
            nameItem(it.name, CompletionKind.EVENT, range, detail = it.description, documentation = it.hoverText)
        }

    private fun eventDomains(request: FeatureRequest, range: TextRange): List<CompletionItem> {
        val source = DocumentDeclarations(request.document).latest() ?: return emptyList()
        return source.outcome.index.referencesOf(ReferenceKind.EVENT_DOMAIN).map { it.name }.filter { it.isNotBlank() }.distinct()
            .map { nameItem(it, CompletionKind.EVENT, range, detail = "event domain") }
    }
}
