package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.DocumentContext
import io.karpfen.ide.core.lang.feature.NavigationProvider
import io.karpfen.ide.core.lang.modelpath.ModelPathResolver

/**
 * KStates navigation (spec §5): state references ↔ state declarations; macro references ↔ macro declarations;
 * `ATTACHED TO` and TAKES/RETURNS types → TYPE declaration in the context's metamodel; model-path segments → member
 * declarations in the metamodel.
 */
internal class KstatesNavigationProvider(private val paths: ModelPathResolver) : NavigationProvider {
    override fun definition(context: DocumentContext, offset: Int): List<Location> = TODO("WP-LANG: KStates definition")

    override fun references(context: DocumentContext, offset: Int): List<Location> = TODO("WP-LANG: KStates references")
}
