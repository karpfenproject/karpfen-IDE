package io.karpfen.ide.core.lang.kmodel

import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.NavigationProvider
import io.karpfen.ide.core.lang.symbols.TypeCatalog

/**
 * KModel navigation (spec §5): object references → object declarations in the file; object types → TYPE declaration
 * in the context's metamodel; property/relation keys → their declaration in the metamodel (both through [types]).
 */
internal class KmodelNavigationProvider(private val types: TypeCatalog) : NavigationProvider {
    override fun definition(request: FeatureRequest, offset: Int): List<Location> = TODO("WP-LANG: KModel definition")

    override fun references(request: FeatureRequest, offset: Int): List<Location> = TODO("WP-LANG: KModel references")
}
