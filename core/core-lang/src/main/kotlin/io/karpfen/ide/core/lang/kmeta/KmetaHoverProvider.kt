package io.karpfen.ide.core.lang.kmeta

import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.HoverProvider
import io.karpfen.ide.core.lang.symbols.TypeCatalog

/**
 * KMeta hover (spec §5): type → `TypeInfo.hoverText`; property/relation → `MemberInfo.signature` (`prop x: number`,
 * `has position: Vector`, `knows obstacles: list(Obstacle)`), both looked up in [types].
 */
internal class KmetaHoverProvider(private val types: TypeCatalog) : HoverProvider {
    override fun hover(request: FeatureRequest, offset: Int): Hover? = TODO("WP-LANG: KMeta hover")
}
