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

import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.parse.pathAt
import io.karpfen.ide.core.document.syntax.SyntaxToken
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.context.CaretContext
import io.karpfen.ide.core.lang.context.ContextToken
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.modelpath.PathScope
import io.karpfen.ide.core.lang.modelpath.PathType
import io.karpfen.ide.core.lang.symbols.DocumentDeclarations
import io.karpfen.ide.core.lang.symbols.LocatedOutcome
import io.karpfen.ide.core.lang.symbols.TypeCatalog
import io.karpfen.ide.core.lang.symbols.TypeOrigin
import io.karpfen.ide.core.text.TextRange

/**
 * The model-path scope at an offset of a KStates document: one collaborator shared by KStates completion,
 * hover and navigation instead of three copies.
 *
 * - `types`: `TypeCatalog.contextTypes(request)` (domain and event types);
 * - `rootType`: the `ATTACHED TO` type of the STATEMACHINE;
 * - `eventType`: the event type named by the enclosing TRANSITION's `EVENT(…)` clause when determinable, else null;
 * - `bindings`: `WITH MACRO(…) AS "x"` bindings in scope (typed by the macro's RETURNS type) and, inside a MACRO
 *   definition, its TAKES parameters (typed by their declared types).
 *
 * At the caret it works on the document's tokens ([scopeAt]: block headers of the [CaretContext], always current); in a
 * parse outcome of the context (references in other files) on its outline ([scopeIn]). Macro signatures are name-based
 * knowledge from the parse outcomes (staleness rule of `LanguageDocument`). Never throws.
 */
internal interface KstatesPathScopes {
    /** The scope for a model path at the caret of `request.document` described by [context]. */
    fun scopeAt(request: FeatureRequest, context: CaretContext): KstatesScope

    /** The scope for a model path at [offset] of [source], a parse outcome of a `.kstates` file of the context. */
    fun scopeIn(request: FeatureRequest, source: LocatedOutcome, offset: Int): KstatesScope
}

/** A path scope and the facts it was built from (where bindings are declared, the enclosing macro). */
internal class KstatesScope(val path: PathScope, val facts: ScopeFacts)

/**
 * What surrounds a path in a KStates document: the `ATTACHED TO` type, the event named by the enclosing transition's
 * `EVENT(…)` clause, the enclosing `WITH … AS` bindings (outermost first) and the enclosing macro definition.
 */
internal data class ScopeFacts(
    val attachedType: String?,
    val eventName: String?,
    val bindings: List<BindingFact>,
    val macro: String?,
)

/** A `WITH MACRO("m", …) AS "x"` binding: [name] `x`, [macro] `m`, and where `x` is declared. */
internal data class BindingFact(val name: String, val macro: String?, val declaration: Location?)

/** Default [KstatesPathScopes] over the caret context, the outline and [types]. */
internal class DefaultKstatesPathScopes(private val types: TypeCatalog) : KstatesPathScopes {

    override fun scopeAt(request: FeatureRequest, context: CaretContext): KstatesScope {
        val document = request.document
        val declarations = DocumentDeclarations(document)
        val blocks = context.blocks.map(KstatesBlocks::of)
        val facts = ScopeFacts(
            attachedType = blocks.firstOrNull { it.kind == MACHINE }?.construct?.lastOrNull { it.kind == TokenKind.STRING }?.text
                ?: declarations.all(SymbolKind.STATEMACHINE).firstOrNull()?.declaration?.typeName,
            eventName = blocks.lastOrNull { it.kind in KstatesSlots.TRANSITION_KEYWORDS }?.let { eventIn(document, it.range) },
            bindings = blocks.filter { it.kind == WITH }.mapNotNull { bindingOf(document, it.construct) },
            macro = KstatesBlocks.macroOf(context.blocks),
        )
        return build(request, facts, MacroSignatures(declarations.located().map { it.outcome.index }))
    }

    override fun scopeIn(request: FeatureRequest, source: LocatedOutcome, offset: Int): KstatesScope {
        val index = source.outcome.index
        val path = source.outcome.outline.pathAt(offset)
        val transition = path.lastOrNull { it.kind in TRANSITION_KINDS }
        val facts = ScopeFacts(
            attachedType = index.declarationsOf(SymbolKind.STATEMACHINE).firstOrNull()?.typeName,
            eventName = transition?.let { node ->
                index.referencesOf(ReferenceKind.EVENT_NAME).firstOrNull { it.range in node.range }?.name
            },
            bindings = bindingsAt(path, offset, source),
            macro = path.lastOrNull { it.kind == SymbolKind.MACRO }?.name,
        )
        return build(request, facts, MacroSignatures(listOf(index)))
    }

    /**
     * The `WITH … AS "x"` bindings among the outline nodes [path] around [offset] of [source]: a binding is in scope in
     * its block after its `AS "x"`, not in its own macro arguments.
     */
    private fun bindingsAt(path: List<OutlineNode>, offset: Int, source: LocatedOutcome): List<BindingFact> =
        path.filter { it.kind == SymbolKind.BINDING && offset > it.selectionRange.end }
            .map { BindingFact(it.name, it.detail, source.location(it.selectionRange)) }

    /**
     * The scope of [facts]: the context's types, the macro's parameters and then the `WITH` bindings typed by
     * [signatures] (a later binding of the same name wins), and the event type if the events file declares it.
     */
    private fun build(request: FeatureRequest, facts: ScopeFacts, signatures: MacroSignatures): KstatesScope {
        val visible = types.contextTypes(request)
        val bindings = LinkedHashMap<String, PathType>()
        facts.macro?.let { macro -> signatures.parameters(macro).forEach { (name, type) -> bindings[name] = type } }
        facts.bindings.forEach { binding -> bindings[binding.name] = binding.macro?.let(signatures::returns) ?: UNKNOWN }
        val eventType = facts.eventName?.takeIf { name -> visible.any { it.origin == TypeOrigin.EVENTS && it.name == name } }
        return KstatesScope(PathScope(visible, facts.attachedType, eventType, bindings), facts)
    }

    /** The binding of a `WITH MACRO("m", …) AS "x"` block construct, or null if it has no name yet. */
    private fun bindingOf(document: LanguageDocument, construct: List<ContextToken>): BindingFact? {
        val name = KstatesBlocks.bindingNameOf(construct) ?: return null
        val content = TextRange(name.range.start + 1, maxOf(name.range.start + 1, name.range.end - 1))
        return BindingFact(name.text, KstatesBlocks.boundMacroOf(construct), Location(document.path, content, document.text))
    }

    /** The event name of the first `EVENT("d", "n")` clause in [range] of the document's tokens, or null. */
    private fun eventIn(document: LanguageDocument, range: TextRange): String? {
        val tokens = document.tokens.tokensIn(range)
        val text = document.text.text
        fun textOf(token: SyntaxToken) = text.substring(token.range.start, token.range.end)
        for (i in 0 until tokens.size - 4) {
            if (textOf(tokens[i]) != KstatesSlots.EVENT || textOf(tokens[i + 1]) != "(" || textOf(tokens[i + 3]) != ",") continue
            val name = tokens[i + 4].takeIf { it.kind == TokenKind.STRING } ?: continue
            return textOf(name).removeSurrounding("\"").removeSurrounding("'")
        }
        return null
    }

    private companion object {
        /** The keyword of the state machine block. */
        const val MACHINE = "STATEMACHINE"

        /** The keyword of a binding block. */
        const val WITH = "WITH"

        /** The outline kinds of transitions, whose `EVENT(…)` clause names the event type. */
        val TRANSITION_KINDS = setOf(SymbolKind.TRANSITION, SymbolKind.SPLIT, SymbolKind.JOIN)

        /** The type of a binding whose macro or RETURNS is unknown: resolution ends there. */
        val UNKNOWN = PathType("", isList = false, isPrimitive = false)
    }
}
