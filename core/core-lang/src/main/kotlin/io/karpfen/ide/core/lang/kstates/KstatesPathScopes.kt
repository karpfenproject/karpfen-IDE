package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.parse.SyntaxIndex
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
import io.karpfen.ide.core.lang.symbols.TypeExpression
import io.karpfen.ide.core.lang.symbols.TypeOrigin
import io.karpfen.ide.core.text.TextRange

/**
 * The model-path scope at an offset of a KStates document (spec §5): one collaborator shared by KStates completion,
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
            bindings = path.filter { it.kind == SymbolKind.BINDING }
                .map { BindingFact(it.name, it.detail, source.location(it.selectionRange)) },
            macro = path.lastOrNull { it.kind == SymbolKind.MACRO }?.name,
        )
        return build(request, facts, MacroSignatures(listOf(index)))
    }

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
        val asIndex = construct.indexOfLast { it.kind == TokenKind.KEYWORD && it.text.uppercase() == AS }
        val name = construct.getOrNull(asIndex + 1)?.takeIf { asIndex >= 0 && it.kind == TokenKind.STRING } ?: return null
        val macro = construct.getOrNull(3)
            ?.takeIf { construct.getOrNull(1)?.text == KstatesSlots.MACRO && it.kind == TokenKind.STRING }
            ?.text
        val content = TextRange(name.range.start + 1, maxOf(name.range.start + 1, name.range.end - 1))
        return BindingFact(name.text, macro, Location(document.path, content, document.text))
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
        const val MACHINE = "STATEMACHINE"
        const val WITH = "WITH"
        const val AS = "AS"
        val TRANSITION_KINDS = setOf(SymbolKind.TRANSITION, SymbolKind.SPLIT, SymbolKind.JOIN)

        /** The type of a binding whose macro or RETURNS is unknown: resolution ends there. */
        val UNKNOWN = PathType("", isList = false, isPrimitive = false)
    }
}

/**
 * The RETURNS and TAKES types of the macros of a document, from its syntax indexes (priority order, name-based).
 */
internal class MacroSignatures(private val indexes: List<SyntaxIndex>) {

    /** The RETURNS type of [macro] (`reference(T)` is `T`), or null if unknown. */
    fun returns(macro: String): PathType? =
        indexes.firstNotNullOfOrNull { it.declarations(SymbolKind.MACRO, macro).firstOrNull() }?.typeName?.let(::pathTypeOf)

    /** The TAKES parameters of [macro] with their types, in declaration order. */
    fun parameters(macro: String): List<Pair<String, PathType>> =
        indexes.firstNotNullOfOrNull { index ->
            index.declarationsOf(SymbolKind.MACRO_PARAMETER)
                .filter { it.container == macro && it.name.isNotEmpty() }
                .takeIf { it.isNotEmpty() }
        }.orEmpty().map { it.name to pathTypeOf(it.typeName) }

    private fun pathTypeOf(written: String?): PathType =
        TypeExpression.parse(written).let { PathType(it.name, it.isList, it.isPrimitive) }
}
