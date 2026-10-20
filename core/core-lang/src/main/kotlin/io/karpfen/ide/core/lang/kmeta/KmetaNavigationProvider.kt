package io.karpfen.ide.core.lang.kmeta

import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.DocumentContext
import io.karpfen.ide.core.lang.feature.NavigationProvider

/**
 * KMeta navigation (spec §5): type references → TYPE declaration in this file, or in the domain metamodel for
 * `EVENTS.kmeta`; references of a type across the context.
 */
internal class KmetaNavigationProvider : NavigationProvider {
    override fun definition(context: DocumentContext, offset: Int): List<Location> = TODO("WP-LANG: KMeta definition")

    override fun references(context: DocumentContext, offset: Int): List<Location> = TODO("WP-LANG: KMeta references")
}
