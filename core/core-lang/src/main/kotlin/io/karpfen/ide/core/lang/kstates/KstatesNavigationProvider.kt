package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.context.CaretContext
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.NavigationProvider
import io.karpfen.ide.core.lang.modelpath.PathOrigin
import io.karpfen.ide.core.lang.navigation.ContextReferences
import io.karpfen.ide.core.lang.navigation.ContextSymbol
import io.karpfen.ide.core.lang.symbols.DocumentDeclarations
import io.karpfen.ide.core.lang.symbols.TypeCatalog
import io.karpfen.ide.core.lang.symbols.TypeOrigin

/**
 * KStates navigation (spec §5): state references ↔ state declarations; macro references ↔ macro declarations;
 * `ATTACHED TO` and TAKES/RETURNS types → TYPE declaration in the context's metamodel ([types]); event names → the event
 * type; model-path segments → member declarations in the metamodel ([paths], `PathMember.declaration`), WITH bindings
 * and macro parameters → their declarations, `self`/`event` → the type. Declarations known only from the last good
 * parse are returned with that parse's version and the document's lineage (see `Location`); references of types and
 * members span the modeling context ([references]), the others are file-local ([KstatesLocalReferences]).
 */
internal class KstatesNavigationProvider(
    private val slots: KstatesSlots,
    private val paths: KstatesPaths,
    private val types: TypeCatalog,
    private val references: ContextReferences,
) : NavigationProvider {

    /** What navigation does with the symbol at the caret. */
    private sealed interface Target {
        /** A state or macro of this file. */
        data class Local(val declaration: SymbolKind, val reference: ReferenceKind, val name: String) : Target

        /** A WITH binding or macro parameter named [name], declared at [declaration]. */
        data class Binding(val name: String, val declaration: Location?) : Target

        /** A type or member of the modeling context, declared at [declaration]. */
        data class Shared(val symbol: ContextSymbol, val declaration: Location?) : Target
    }

    override fun definition(request: FeatureRequest, offset: Int): List<Location> =
        when (val target = targetAt(request, offset)) {
            is Target.Local -> DocumentDeclarations(request.document).named(target.declaration, target.name).map { it.location }
            is Target.Binding -> listOfNotNull(target.declaration)
            is Target.Shared -> listOfNotNull(target.declaration)
            null -> emptyList()
        }

    override fun references(request: FeatureRequest, offset: Int): List<Location> {
        val local = KstatesLocalReferences(request.document)
        val found = when (val target = targetAt(request, offset)) {
            is Target.Local -> local.of(target.declaration, target.reference, target.name)
            is Target.Binding -> listOfNotNull(target.declaration) + local.ofBinding(target.name, offset)
            is Target.Shared -> listOfNotNull(target.declaration) + references.find(target.symbol, request)
            null -> emptyList()
        }
        return found.distinct()
    }

    private fun targetAt(request: FeatureRequest, offset: Int): Target? {
        val (context, slot) = slots.at(request.document, offset)
        val target = when (slot) {
            is KstatesSlot.StateName -> Target.Local(SymbolKind.STATE, ReferenceKind.STATE, slot.name)
            is KstatesSlot.MacroName -> Target.Local(SymbolKind.MACRO, ReferenceKind.MACRO, slot.name)
            is KstatesSlot.BindingName -> Target.Binding(slot.name, Location(request.document.path, slot.range, request.document.text))
            is KstatesSlot.ParameterName -> Target.Binding(slot.name, Location(request.document.path, slot.range, request.document.text))
            is KstatesSlot.TypeName -> if (slot.inString) Target.Shared(ContextSymbol.Type(slot.name), types.type(request, slot.name)?.declaration) else null
            is KstatesSlot.EventName -> Target.Shared(
                ContextSymbol.Type(slot.name),
                types.contextTypes(request).firstOrNull { it.origin == TypeOrigin.EVENTS && it.name == slot.name }?.declaration,
            )
            is KstatesSlot.RelationKey -> paths.typeOf(request, context, slot.sourcePath)?.let { owner ->
                Target.Shared(ContextSymbol.Member(owner.name, slot.name), owner.member(slot.name)?.declaration)
            }
            is KstatesSlot.ModelPath -> pathTarget(request, context, slot)
            is KstatesSlot.Keywords, is KstatesSlot.EventDomain, KstatesSlot.None -> null
        }
        return target?.takeIf { nameOf(it).isNotBlank() }
    }

    private fun pathTarget(request: FeatureRequest, context: CaretContext, slot: KstatesSlot.ModelPath): Target? {
        val hit = paths.segmentAt(request, context, slot.range) ?: return null
        val member = hit.member ?: return null
        return when (member.origin) {
            PathOrigin.MEMBER -> hit.resolved?.owner?.let { Target.Shared(ContextSymbol.Member(it.name, member.name), member.declaration) }
            PathOrigin.ROOT -> if (hit.isUndeterminedEvent) null else Target.Shared(ContextSymbol.Type(member.type.name), types.type(request, member.type.name)?.declaration)
            PathOrigin.BINDING -> Target.Binding(member.name, bindingDeclaration(request, hit))
        }
    }

    /** Where the binding or macro parameter of [hit] is declared: the enclosing `WITH … AS`, else the macro's TAKES. */
    private fun bindingDeclaration(request: FeatureRequest, hit: PathHit): Location? {
        val name = hit.segment.name
        return hit.scope.facts.bindings.lastOrNull { it.name == name }?.declaration
            ?: DocumentDeclarations(request.document).all(SymbolKind.MACRO_PARAMETER)
                .firstOrNull { it.declaration.name == name && it.declaration.container == hit.scope.facts.macro }?.location
    }

    private fun nameOf(target: Target): String = when (target) {
        is Target.Local -> target.name
        is Target.Binding -> target.name
        is Target.Shared -> target.symbol.name
    }
}
