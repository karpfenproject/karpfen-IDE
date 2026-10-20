package io.karpfen.ide.core.lang.kmodel

import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.HoverProvider
import io.karpfen.ide.core.lang.symbols.TypeCatalog

/**
 * KModel hover (spec §5): object type → `TypeInfo.hoverText`; property/relation key → `MemberInfo.signature` of the
 * enclosing object's type (`prop x: number`, …), looked up in [types].
 */
internal class KmodelHoverProvider(private val types: TypeCatalog) : HoverProvider {
    override fun hover(request: FeatureRequest, offset: Int): Hover? = TODO("WP-LANG: KModel hover")
}
