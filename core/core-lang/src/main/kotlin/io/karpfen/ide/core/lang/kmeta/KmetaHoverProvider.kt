package io.karpfen.ide.core.lang.kmeta

import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.lang.feature.DocumentContext
import io.karpfen.ide.core.lang.feature.HoverProvider

/**
 * KMeta hover (spec §5): type → its description and members; property/relation → `prop x: number`, `has position:
 * Vector`, `knows obstacles: list(Obstacle)`.
 */
internal class KmetaHoverProvider : HoverProvider {
    override fun hover(context: DocumentContext, offset: Int): Hover? = TODO("WP-LANG: KMeta hover")
}
