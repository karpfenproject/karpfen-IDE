package io.karpfen.ide.core.lang.kmeta

import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.HoverProvider
import io.karpfen.ide.core.lang.symbols.TypeCatalog

/**
 * KMeta hover (spec §5): type → `TypeInfo.hoverText`; property/relation → `MemberInfo.signature` (`prop x: number`,
 * `has position: Vector`, `knows obstacles: list(Obstacle)`), both looked up in [types].
 */
internal class KmetaHoverProvider(private val slots: KmetaSlots, private val types: TypeCatalog) : HoverProvider {

    override fun hover(request: FeatureRequest, offset: Int): Hover? =
        when (val slot = slots.at(request.document, offset).second) {
            is KmetaSlot.TypeName -> types.type(request, slot.name)?.let { Hover(slot.range, it.hoverText) }
            is KmetaSlot.TypeArgument -> if (slot.inString && !slot.primitive) {
                types.type(request, slot.name)?.let { Hover(slot.range, it.hoverText) }
            } else {
                null
            }
            is KmetaSlot.MemberName -> slot.owner?.let { types.type(request, it) }?.member(slot.name)?.let { Hover(slot.range, it.signature) }
            KmetaSlot.TopLevel, is KmetaSlot.TypeBody, KmetaSlot.None -> null
        }
}
