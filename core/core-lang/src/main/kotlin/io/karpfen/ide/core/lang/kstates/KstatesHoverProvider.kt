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
package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.lang.context.CaretContext
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.HoverProvider
import io.karpfen.ide.core.lang.modelpath.PathOrigin
import io.karpfen.ide.core.lang.symbols.DocumentDeclarations
import io.karpfen.ide.core.lang.symbols.TypeCatalog
import io.karpfen.ide.core.lang.symbols.TypeOrigin

/**
 * KStates hover: state → its hierarchy path (`state observe > react to obstacle`); macro → its TAKES/RETURNS
 * signature (`macro m(p: Vector): number`); model path → the resolved type of the path up to the segment under the
 * caret (`boundingBox->position: Vector`, followed by the member's signature, [paths]); type and event name →
 * `TypeInfo.hoverText` ([types]); SETOBJ relation → the member's signature; WITH binding / TAKES parameter → its type.
 */
internal class KstatesHoverProvider(
    private val slots: KstatesSlots,
    private val paths: KstatesPaths,
    private val types: TypeCatalog,
) : HoverProvider {

    override fun hover(request: FeatureRequest, offset: Int): Hover? {
        val (context, slot) = slots.at(request.document, offset)
        val declarations = DocumentDeclarations(request.document)
        return when (slot) {
            is KstatesSlot.StateName -> slot.name.takeIf { declarations.named(SymbolKind.STATE, it).isNotEmpty() }
                ?.let { Hover(slot.range, KstatesTexts.state(declarations, it)) }
            is KstatesSlot.MacroName -> slot.name.takeIf { declarations.named(SymbolKind.MACRO, it).isNotEmpty() }
                ?.let { Hover(slot.range, KstatesTexts.macro(declarations, it)) }
            is KstatesSlot.TypeName -> if (slot.inString) types.type(request, slot.name)?.let { Hover(slot.range, it.hoverText) } else null
            is KstatesSlot.EventName -> types.contextTypes(request).firstOrNull { it.origin == TypeOrigin.EVENTS && it.name == slot.name }
                ?.let { Hover(slot.range, it.hoverText) }
            is KstatesSlot.ModelPath -> pathHover(request, context, slot)
            is KstatesSlot.RelationKey -> paths.typeOf(request, context, slot.sourcePath)?.member(slot.name)
                ?.let { Hover(slot.range, it.signature) }
            is KstatesSlot.BindingName -> paths.declaredBindingType(request, context)
                ?.let { Hover(slot.range, "binding ${slot.name}: ${KstatesTexts.typeText(it)}") }
            is KstatesSlot.ParameterName -> declarations.all(SymbolKind.MACRO_PARAMETER)
                .firstOrNull { it.declaration.name == slot.name && it.declaration.container == KstatesBlocks.macroOf(context.blocks) }
                ?.let { Hover(slot.range, "parameter ${slot.name}: ${it.declaration.typeName.orEmpty()}") }
            is KstatesSlot.Keywords, is KstatesSlot.EventDomain, KstatesSlot.None -> null
        }
    }

    /**
     * The type of the path up to the segment under the caret, with the member's signature or the kind of binding; an
     * `event` root whose type is not determinable lists every event type.
     */
    private fun pathHover(request: FeatureRequest, context: CaretContext, slot: KstatesSlot.ModelPath): Hover? {
        val hit = paths.segmentAt(request, context, slot.range) ?: return null
        val member = hit.member ?: return null
        val type = if (hit.isUndeterminedEvent) {
            "any event (" + hit.scope.path.eventTypes.joinToString(", ") { it.name } + ")"
        } else {
            KstatesTexts.typeText(member.type)
        }
        val detail = when (member.origin) {
            PathOrigin.MEMBER -> member.member?.signature
            PathOrigin.BINDING -> if (hit.scope.facts.bindings.any { it.name == member.name }) "WITH binding" else "macro parameter"
            PathOrigin.ROOT -> null
        }
        return Hover(hit.segment.range, "${hit.path}: $type" + (detail?.let { "\n$it" } ?: ""))
    }
}
