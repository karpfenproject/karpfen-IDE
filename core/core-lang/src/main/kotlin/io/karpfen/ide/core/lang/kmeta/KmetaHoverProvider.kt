/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.karpfen.ide.core.lang.kmeta

import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.HoverProvider
import io.karpfen.ide.core.lang.symbols.TypeCatalog

/**
 * Hover in KMeta. On a type it shows `TypeInfo.hoverText`. On a property or relation it shows `MemberInfo.signature`,
 * such as `prop x: number`, `has position: Vector` or `knows obstacles: list(Obstacle)`. Both are looked up in
 * [types].
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
            is KmetaSlot.MemberName -> slot.owner?.let { types.type(request, it) }?.member(slot.name)
                ?.let { Hover(slot.range, it.signature) }
            KmetaSlot.TopLevel, is KmetaSlot.TypeBody, KmetaSlot.None -> null
        }
}
