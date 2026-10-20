package io.karpfen.ide.core.lang.kmeta

import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.NavigationProvider
import io.karpfen.ide.core.lang.symbols.TypeCatalog

/**
 * KMeta navigation (spec §5): type references → TYPE declaration (`TypeInfo.declaration` of [types]: in this file, or
 * in the domain metamodel for `EVENTS.kmeta`); references of a type across the context.
 */
internal class KmetaNavigationProvider(private val types: TypeCatalog) : NavigationProvider {
    override fun definition(request: FeatureRequest, offset: Int): List<Location> = TODO("WP-LANG: KMeta definition")

    override fun references(request: FeatureRequest, offset: Int): List<Location> = TODO("WP-LANG: KMeta references")
}
