package io.karpfen.ide.core.lang.kmeta

import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.NavigationProvider
import io.karpfen.ide.core.lang.navigation.ContextReferences
import io.karpfen.ide.core.lang.navigation.ContextSymbol
import io.karpfen.ide.core.lang.symbols.TypeCatalog

/**
 * KMeta navigation (spec §5): type references and type names → TYPE declaration (`TypeInfo.declaration` of [types]: in
 * this file, or in the domain metamodel for `EVENTS.kmeta`); member keys → their own declaration. References of a type
 * or a member across the modeling context come from [references].
 */
internal class KmetaNavigationProvider(
    private val slots: KmetaSlots,
    private val types: TypeCatalog,
    private val references: ContextReferences,
) : NavigationProvider {

    override fun definition(request: FeatureRequest, offset: Int): List<Location> =
        symbolAt(request, offset)?.let { declarationOf(request, it) }?.let(::listOf).orEmpty()

    override fun references(request: FeatureRequest, offset: Int): List<Location> {
        val symbol = symbolAt(request, offset) ?: return emptyList()
        return (listOfNotNull(declarationOf(request, symbol)) + references.find(symbol, request)).distinct()
    }

    private fun symbolAt(request: FeatureRequest, offset: Int): ContextSymbol? =
        when (val slot = slots.at(request.document, offset).second) {
            is KmetaSlot.TypeName -> ContextSymbol.Type(slot.name)
            is KmetaSlot.TypeArgument -> ContextSymbol.Type(slot.name).takeIf { !slot.primitive && slot.inString }
            is KmetaSlot.MemberName -> slot.owner?.let { ContextSymbol.Member(it, slot.name) }
            KmetaSlot.TopLevel, is KmetaSlot.TypeBody, KmetaSlot.None -> null
        }?.takeIf { it.name.isNotBlank() }

    private fun declarationOf(request: FeatureRequest, symbol: ContextSymbol): Location? = when (symbol) {
        is ContextSymbol.Type -> types.type(request, symbol.name)?.declaration
        is ContextSymbol.Member -> types.type(request, symbol.owner)?.member(symbol.name)?.declaration
    }
}
