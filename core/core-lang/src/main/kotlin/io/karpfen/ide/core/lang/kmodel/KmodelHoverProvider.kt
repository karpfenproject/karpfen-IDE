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
package io.karpfen.ide.core.lang.kmodel

import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.HoverProvider
import io.karpfen.ide.core.lang.symbols.DocumentDeclarations
import io.karpfen.ide.core.lang.symbols.TypeCatalog
import io.karpfen.ide.core.text.TextRange

/**
 * KModel hover: object type → `TypeInfo.hoverText`; property/relation key → `MemberInfo.signature` of the
 * enclosing object's type (`prop x: number`, …), looked up in [types]; an object key or reference → `object k: T`
 * (from the object's declaration).
 */
internal class KmodelHoverProvider(private val slots: KmodelSlots, private val types: TypeCatalog) : HoverProvider {

    override fun hover(request: FeatureRequest, offset: Int): Hover? =
        when (val slot = slots.at(request.document, offset).second) {
            is KmodelSlot.ObjectType -> types.type(request, slot.name)?.let { Hover(slot.range, it.hoverText) }
            is KmodelSlot.Key -> if (slot.inString) {
                slot.objectType?.let { types.type(request, it) }?.member(slot.name)?.let { Hover(slot.range, it.signature) }
            } else {
                null
            }
            is KmodelSlot.ObjectKey -> objectHover(request, slot.name, slot.range)
            is KmodelSlot.ObjectReference -> objectHover(request, slot.name, slot.range)
            KmodelSlot.TopLevel, is KmodelSlot.ObjectBody, KmodelSlot.None -> null
        }

    /** `object k: T` for the object [name] declared in the file, or null if there is none. */
    private fun objectHover(request: FeatureRequest, name: String, range: TextRange): Hover? {
        if (name.isBlank()) return null
        val declaration = DocumentDeclarations(request.document).named(SymbolKind.OBJECT, name).firstOrNull()?.declaration ?: return null
        return Hover(range, "object $name: ${declaration.typeName.orEmpty()}")
    }
}
