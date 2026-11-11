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

import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.NavigationProvider
import io.karpfen.ide.core.lang.navigation.ContextReferences
import io.karpfen.ide.core.lang.navigation.ContextSymbol
import io.karpfen.ide.core.lang.symbols.DocumentDeclarations
import io.karpfen.ide.core.lang.symbols.TypeCatalog

/**
 * Navigation in KModel. Object references lead to the object declarations in the file. Object types lead to the TYPE
 * declaration in the metamodel of the context. Property and relation keys lead to their declaration in the metamodel.
 * Types and keys are both found through [types]. References of objects are searched within the file, in the
 * declarations of the latest parse by the staleness rule. References of types and keys are searched across the
 * modeling context through [references].
 */
internal class KmodelNavigationProvider(
    private val slots: KmodelSlots,
    private val types: TypeCatalog,
    private val references: ContextReferences,
) : NavigationProvider {

    override fun definition(request: FeatureRequest, offset: Int): List<Location> =
        when (val slot = slots.at(request.document, offset).second) {
            is KmodelSlot.ObjectReference -> objectDeclarations(request, slot.name)
            is KmodelSlot.ObjectKey -> objectDeclarations(request, slot.name)
            else -> symbolOf(slot)?.let { declarationOf(request, it) }?.let(::listOf).orEmpty()
        }

    override fun references(request: FeatureRequest, offset: Int): List<Location> {
        val slot = slots.at(request.document, offset).second
        val objectName = (slot as? KmodelSlot.ObjectReference)?.name ?: (slot as? KmodelSlot.ObjectKey)?.name
        if (objectName != null) return (objectDeclarations(request, objectName) + objectReferences(request, objectName)).distinct()
        val symbol = symbolOf(slot) ?: return emptyList()
        return (listOfNotNull(declarationOf(request, symbol)) + references.find(symbol, request)).distinct()
    }

    /** The type or member a slot names, or null for other slots. */
    private fun symbolOf(slot: KmodelSlot): ContextSymbol? = when (slot) {
        is KmodelSlot.ObjectType -> ContextSymbol.Type(slot.name)
        is KmodelSlot.Key -> slot.objectType?.takeIf { slot.inString }?.let { ContextSymbol.Member(it, slot.name) }
        else -> null
    }?.takeIf { it.name.isNotBlank() }

    /** The declaration of [symbol] in the type catalog, or null if the type or member is unknown. */
    private fun declarationOf(request: FeatureRequest, symbol: ContextSymbol): Location? = when (symbol) {
        is ContextSymbol.Type -> types.type(request, symbol.name)?.declaration
        is ContextSymbol.Member -> types.type(request, symbol.owner)?.member(symbol.name)?.declaration
    }

    /** The declarations of the object [name] in the file. */
    private fun objectDeclarations(request: FeatureRequest, name: String): List<Location> =
        if (name.isBlank()) emptyList() else DocumentDeclarations(request.document).named(SymbolKind.OBJECT, name).map { it.location }

    /** The references to the object [name] in the latest parse of the file. */
    private fun objectReferences(request: FeatureRequest, name: String): List<Location> {
        val source = DocumentDeclarations(request.document).latest() ?: return emptyList()
        return source.outcome.index.references(ReferenceKind.OBJECT, name).map { source.location(it.range) }
    }
}
