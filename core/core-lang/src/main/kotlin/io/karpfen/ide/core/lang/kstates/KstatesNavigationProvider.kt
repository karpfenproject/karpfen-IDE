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

import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.context.CaretContext
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.NavigationProvider
import io.karpfen.ide.core.lang.modelpath.PathOrigin
import io.karpfen.ide.core.lang.navigation.ContextReferences
import io.karpfen.ide.core.lang.navigation.ContextSymbol
import io.karpfen.ide.core.lang.symbols.AttachableMachines
import io.karpfen.ide.core.lang.symbols.DocumentDeclarations
import io.karpfen.ide.core.lang.symbols.TypeCatalog
import io.karpfen.ide.core.lang.symbols.TypeOrigin

/**
 * Navigation in KStates.
 * - State references and state declarations lead to each other, and so do macro references and macro declarations.
 * - `ATTACHED TO`, TAKES and RETURNS types lead to the TYPE declaration in the metamodel of the context, through
 *   [types].
 * - Event names lead to the event type.
 * - The name after `ATTACH` and the second argument of `EXCHANGE` lead to the attachable state machine of the context
 *   with that name, and its declaration finds them across the context.
 * - Model path segments lead to the member declarations in the metamodel, through [paths] and
 *   `PathMember.declaration`. WITH bindings and macro parameters lead to their declarations, and `self` and `event`
 *   lead to the type.
 *
 * Declarations that only the last good parse knows are returned with the version of that parse and the lineage of the
 * document, as `Location` describes. References of types and members span the modeling context through [references].
 * The others stay within the file through [KstatesLocalReferences].
 */
internal class KstatesNavigationProvider(
    private val slots: KstatesSlots,
    private val paths: KstatesPaths,
    private val types: TypeCatalog,
    private val references: ContextReferences,
) : NavigationProvider {

    /** What navigation does with the symbol at the caret. */
    private sealed interface Target {
        /** A state or macro of this file. */
        data class Local(val declaration: SymbolKind, val reference: ReferenceKind, val name: String) : Target

        /** A WITH binding or macro parameter named [name], declared at [declaration]. */
        data class Binding(val name: String, val declaration: Location?) : Target

        /** A type or member of the modeling context, declared at [declaration]. */
        data class Shared(val symbol: ContextSymbol, val declaration: Location?) : Target
    }

    override fun definition(request: FeatureRequest, offset: Int): List<Location> =
        when (val target = targetAt(request, offset)) {
            is Target.Local -> DocumentDeclarations(request.document).named(target.declaration, target.name).map { it.location }
            is Target.Binding -> listOfNotNull(target.declaration)
            is Target.Shared -> listOfNotNull(target.declaration)
            null -> emptyList()
        }

    override fun references(request: FeatureRequest, offset: Int): List<Location> {
        val local = KstatesLocalReferences(request.document)
        val found = when (val target = targetAt(request, offset)) {
            is Target.Local -> local.of(target.declaration, target.reference, target.name)
            is Target.Binding -> listOfNotNull(target.declaration) + local.ofBinding(target.name, offset)
            is Target.Shared -> listOfNotNull(target.declaration) + references.find(target.symbol, request)
            null -> emptyList()
        }
        return found.distinct()
    }

    /** The navigation target of the symbol at [offset], or null if the caret is on no named symbol. */
    private fun targetAt(request: FeatureRequest, offset: Int): Target? {
        val (context, slot) = slots.at(request.document, offset)
        val target = when (slot) {
            is KstatesSlot.StateName -> Target.Local(SymbolKind.STATE, ReferenceKind.STATE, slot.name)
            is KstatesSlot.MacroName -> Target.Local(SymbolKind.MACRO, ReferenceKind.MACRO, slot.name)
            is KstatesSlot.BindingName -> Target.Binding(slot.name, Location(request.document.path, slot.range, request.document.text))
            is KstatesSlot.ParameterName -> Target.Binding(slot.name, Location(request.document.path, slot.range, request.document.text))
            is KstatesSlot.TypeName -> if (slot.inString) typeTarget(request, slot.name) else null
            is KstatesSlot.StateMachineName -> Target.Shared(
                ContextSymbol.StateMachine(slot.name),
                AttachableMachines(request).named(slot.name).firstOrNull()?.location,
            )
            is KstatesSlot.EventName -> Target.Shared(
                ContextSymbol.Type(slot.name),
                types.contextTypes(request).firstOrNull { it.origin == TypeOrigin.EVENTS && it.name == slot.name }?.declaration,
            )
            is KstatesSlot.RelationKey -> paths.typeOf(request, context, slot.sourcePath)?.let { owner ->
                Target.Shared(ContextSymbol.Member(owner.name, slot.name), owner.member(slot.name)?.declaration)
            }
            is KstatesSlot.ModelPath -> pathTarget(request, context, slot)
            is KstatesSlot.Keywords, is KstatesSlot.EventDomain, KstatesSlot.None -> null
        }
        return target?.takeIf { nameOf(it).isNotBlank() }
    }

    /**
     * The target of the path segment under the caret. It is a member of the type so far, the type of a root, or a
     * binding. An `event` root whose type cannot be determined has no target.
     */
    private fun pathTarget(request: FeatureRequest, context: CaretContext, slot: KstatesSlot.ModelPath): Target? {
        val hit = paths.segmentAt(request, context, slot.range) ?: return null
        val member = hit.member ?: return null
        return when (member.origin) {
            PathOrigin.MEMBER -> hit.resolved?.owner?.let { Target.Shared(ContextSymbol.Member(it.name, member.name), member.declaration) }
            PathOrigin.ROOT -> if (hit.isUndeterminedEvent) null else typeTarget(request, member.type.name)
            PathOrigin.BINDING -> Target.Binding(member.name, bindingDeclaration(request, hit))
        }
    }

    /** The type [name] of the modeling context with its declaration, if known. */
    private fun typeTarget(request: FeatureRequest, name: String): Target =
        Target.Shared(ContextSymbol.Type(name), types.type(request, name)?.declaration)

    /**
     * Where the binding or macro parameter of [hit] is declared. This is the enclosing `WITH … AS`, or else the TAKES
     * of the macro.
     */
    private fun bindingDeclaration(request: FeatureRequest, hit: PathHit): Location? {
        val name = hit.segment.name
        return hit.scope.facts.bindings.lastOrNull { it.name == name }?.declaration
            ?: DocumentDeclarations(request.document).all(SymbolKind.MACRO_PARAMETER)
                .firstOrNull { it.declaration.name == name && it.declaration.container == hit.scope.facts.macro }?.location
    }

    /** The name a target is about. */
    private fun nameOf(target: Target): String = when (target) {
        is Target.Local -> target.name
        is Target.Binding -> target.name
        is Target.Shared -> target.symbol.name
    }
}
