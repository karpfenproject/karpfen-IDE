package io.karpfen.ide.core.lang.kmodel

import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.lang.feature.DocumentContext
import io.karpfen.ide.core.lang.feature.HoverProvider

/**
 * KModel hover (spec §5): object type → description and members; property/relation key → its declaration (`prop x:
 * number`, …).
 */
internal class KmodelHoverProvider : HoverProvider {
    override fun hover(context: DocumentContext, offset: Int): Hover? = TODO("WP-LANG: KModel hover")
}
