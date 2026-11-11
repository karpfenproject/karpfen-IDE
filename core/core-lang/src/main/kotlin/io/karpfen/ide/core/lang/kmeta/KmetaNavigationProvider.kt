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

import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.NavigationProvider
import io.karpfen.ide.core.lang.navigation.ContextReferences
import io.karpfen.ide.core.lang.navigation.ContextSymbol
import io.karpfen.ide.core.lang.symbols.TypeCatalog

/**
 * Navigation in KMeta. Type references and type names lead to the TYPE declaration, which is the
 * `TypeInfo.declaration` of [types]. It lies in this file, or in the domain metamodel for `EVENTS.kmeta`. Member keys
 * lead to their own declaration. The references of a type or a member across the modeling context come from
 * [references].
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

    /** The type or member the caret is on, or null. */
    private fun symbolAt(request: FeatureRequest, offset: Int): ContextSymbol? =
        when (val slot = slots.at(request.document, offset).second) {
            is KmetaSlot.TypeName -> ContextSymbol.Type(slot.name)
            is KmetaSlot.TypeArgument -> ContextSymbol.Type(slot.name).takeIf { !slot.primitive && slot.inString }
            is KmetaSlot.MemberName -> slot.owner?.let { ContextSymbol.Member(it, slot.name) }
            KmetaSlot.TopLevel, is KmetaSlot.TypeBody, KmetaSlot.None -> null
        }?.takeIf { it.name.isNotBlank() }

    /** The declaration of [symbol] in the type catalog, or null if the type or member is unknown. */
    private fun declarationOf(request: FeatureRequest, symbol: ContextSymbol): Location? = when (symbol) {
        is ContextSymbol.Type -> types.type(request, symbol.name)?.declaration
        is ContextSymbol.Member -> types.type(request, symbol.owner)?.member(symbol.name)?.declaration
    }
}
