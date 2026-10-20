package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.HoverProvider
import io.karpfen.ide.core.lang.modelpath.ModelPathResolver
import io.karpfen.ide.core.lang.symbols.TypeCatalog

/**
 * KStates hover (spec §5): state → its hierarchy path; macro → its TAKES/RETURNS signature; model path → the resolved
 * type of the path ([paths]); type → `TypeInfo.hoverText` ([types]).
 */
internal class KstatesHoverProvider(
    private val paths: ModelPathResolver,
    private val types: TypeCatalog,
) : HoverProvider {
    override fun hover(request: FeatureRequest, offset: Int): Hover? = TODO("WP-LANG: KStates hover")
}
