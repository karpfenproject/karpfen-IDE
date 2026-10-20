package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.lang.feature.DocumentContext
import io.karpfen.ide.core.lang.feature.HoverProvider
import io.karpfen.ide.core.lang.modelpath.ModelPathResolver

/**
 * KStates hover (spec §5): state → its hierarchy path; macro → its TAKES/RETURNS signature; model path → the resolved
 * type of the path; type → description and members.
 */
internal class KstatesHoverProvider(private val paths: ModelPathResolver) : HoverProvider {
    override fun hover(context: DocumentContext, offset: Int): Hover? = TODO("WP-LANG: KStates hover")
}
