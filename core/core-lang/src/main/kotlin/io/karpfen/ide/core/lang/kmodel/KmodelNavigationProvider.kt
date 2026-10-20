package io.karpfen.ide.core.lang.kmodel

import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.DocumentContext
import io.karpfen.ide.core.lang.feature.NavigationProvider

/**
 * KModel navigation (spec §5): object references → object declarations in the file; object types → TYPE declaration
 * in the context's metamodel; property/relation keys → their declaration in the metamodel.
 */
internal class KmodelNavigationProvider : NavigationProvider {
    override fun definition(context: DocumentContext, offset: Int): List<Location> = TODO("WP-LANG: KModel definition")

    override fun references(context: DocumentContext, offset: Int): List<Location> = TODO("WP-LANG: KModel references")
}
