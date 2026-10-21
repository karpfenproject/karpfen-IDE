package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.context.CallContext
import io.karpfen.ide.core.lang.context.CaretContext
import io.karpfen.ide.core.lang.context.CaretContextDetector
import io.karpfen.ide.core.lang.context.ContextToken
import io.karpfen.ide.core.lang.context.EmbeddedContext
import io.karpfen.ide.core.text.TextRange

/** What the caret is on or in, in a KStates document (spec §5); the one classification of completion, hover, navigation. */
internal sealed interface KstatesSlot {
    /** Between constructs: the structural keywords of [position]. */
    data class Keywords(val position: KstatesPosition) : KstatesSlot

    /** A state name: in a TRANSITION/SPLIT/JOIN header, or the name of `STATE "s"` ([isDeclaration]). */
    data class StateName(val name: String, val range: TextRange, val isDeclaration: Boolean) : KstatesSlot

    /** A macro name: `MACRO("m"` / `@m` (references) or `MACRO "m" {` ([isDeclaration]). */
    data class MacroName(val name: String, val range: TextRange, val isDeclaration: Boolean) : KstatesSlot

    /**
     * A type: `ATTACHED TO "T"`, TAKES/RETURNS types and `list(`/`reference(` inside them. [primitives] allows
     * `number string boolean`; [inString] is false at the argument before its quotes ([name] is then empty), where
     * [wrappers] (`list`, `reference`) are offered as well.
     */
    data class TypeName(
        val name: String,
        val range: TextRange,
        val primitives: Boolean,
        val inString: Boolean = true,
        val wrappers: List<String> = emptyList(),
    ) : KstatesSlot

    /** A model path written in [range]: action, VALUE, IF IN SCOPE and macro-argument strings, `$( … )` in EVAL. */
    data class ModelPath(val range: TextRange) : KstatesSlot

    /** The relation argument of `SETOBJ`/`APPENDOBJ`: a relation of the type of [sourcePath]. */
    data class RelationKey(val sourcePath: String, val name: String, val range: TextRange) : KstatesSlot

    /** The name argument of `EVENT("d", "n")` (condition or action) of the domain [domain]. */
    data class EventName(val name: String, val range: TextRange, val domain: String?) : KstatesSlot

    /** The domain argument of `EVENT("d", …)`. */
    data class EventDomain(val name: String, val range: TextRange) : KstatesSlot

    /** The name of `WITH … AS "x"` (a declaration). */
    data class BindingName(val name: String, val range: TextRange) : KstatesSlot

    /** The name of `TAKES("p", …)` (a declaration). */
    data class ParameterName(val name: String, val range: TextRange) : KstatesSlot

    /** Anything else (values, Python code, comments, broken positions). */
    data object None : KstatesSlot
}

/** Where structural keywords are completed (spec §5, "structural keywords by position"). */
internal enum class KstatesPosition {
    TOP_LEVEL, MACHINE, STATES, STATE_BODY, ACTIONS, TRANSITIONS, TRANSITION_HEADER, TRANSITION_BODY, CONDITION, MACROS,
    MACRO_BODY, DEFINITION, RIGHT_SIDE,
}

/** Classifies the caret of a KStates document from its [CaretContext]. Stateless. */
internal class KstatesSlots(private val caretContexts: CaretContextDetector) {

    /** The caret context at [offset] and its slot. */
    fun at(document: LanguageDocument, offset: Int): Pair<CaretContext, KstatesSlot> {
        val context = caretContexts.detect(document, offset)
        return context to classify(document, context)
    }

    private fun classify(document: LanguageDocument, context: CaretContext): KstatesSlot {
        if (context.inComment) return KstatesSlot.None
        context.embedded?.let { return embedded(document, context, it) }
        val string = context.string ?: return outsideStrings(context)
        val range = if (string.closed) string.contentRange else context.replaceRange
        val name = document.text.substring(range)
        val call = context.call ?: return headerString(context.statement, name, range, context)
        return callArgument(call, name, range)
    }

    private fun embedded(document: LanguageDocument, context: CaretContext, embedded: EmbeddedContext): KstatesSlot {
        val tokens = document.tokens
        val candidates = listOfNotNull(tokens.tokenAt(context.offset), tokens.tokenBefore(context.offset))
        if (embedded.pathPrefix != null) {
            val token = candidates.firstOrNull { it.kind == TokenKind.PATH_REFERENCE } ?: return KstatesSlot.None
            val closed = token.range.length > 2 && document.text.text.getOrNull(token.range.end - 1) == ')'
            return KstatesSlot.ModelPath(TextRange(token.range.start + 2, if (closed) token.range.end - 1 else token.range.end))
        }
        if (embedded.macroPrefix != null) {
            val token = candidates.firstOrNull { it.kind == TokenKind.MACRO_REFERENCE }
            val range = token?.let { TextRange(it.range.start + 1, it.range.end) } ?: TextRange.empty(context.offset)
            return KstatesSlot.MacroName(document.text.substring(range), range, isDeclaration = false)
        }
        return KstatesSlot.None
    }

    /** A string argument of [call]: macro calls, events, operations, VALUE/IF IN SCOPE paths, TAKES/RETURNS types. */
    private fun callArgument(call: CallContext, name: String, range: TextRange): KstatesSlot {
        val index = call.argumentIndex
        val operation = call.name.uppercase()
        return when {
            call.name == MACRO && index == 0 -> KstatesSlot.MacroName(name, range, isDeclaration = false)
            call.name == MACRO -> KstatesSlot.ModelPath(range)
            call.name == EVENT && index == 0 -> KstatesSlot.EventDomain(name, range)
            call.name == EVENT && index == 1 -> KstatesSlot.EventName(name, range, call.arguments.firstOrNull())
            operation in KarpfenSyntax.KSTATES_OPERATIONS && index == 0 -> KstatesSlot.ModelPath(range)
            operation in KarpfenSyntax.KSTATES_RELATION_OPERATIONS && index == 1 ->
                KstatesSlot.RelationKey(call.arguments.firstOrNull().orEmpty(), name, range)
            call.name == VALUE || call.name == SCOPE -> KstatesSlot.ModelPath(range)
            call.name == TAKES && index == 0 -> KstatesSlot.ParameterName(name, range)
            call.name in TYPE_CALLS -> KstatesSlot.TypeName(name, range, primitives = call.name != REFERENCE)
            else -> KstatesSlot.None
        }
    }

    /** A string that is no call argument: named by the statement before it. */
    private fun headerString(statement: List<ContextToken>, name: String, range: TextRange, context: CaretContext): KstatesSlot {
        val last = statement.lastOrNull()
        return when {
            last == null -> KstatesSlot.None
            isTransitionHeader(statement) && last.kind != TokenKind.STRING -> KstatesSlot.StateName(name, range, isDeclaration = false)
            last.isKeyword(STATE) -> KstatesSlot.StateName(name, range, isDeclaration = true)
            last.kind == TokenKind.KEYWORD && last.text.uppercase() == AS -> KstatesSlot.BindingName(name, range)
            last.isKeyword(TO) && statement.getOrNull(statement.size - 2)?.isKeyword(ATTACHED) == true ->
                KstatesSlot.TypeName(name, range, primitives = false)
            last.isKeyword(MACRO) && context.innermostBlock?.let(KstatesBlocks::kindOf) == MACROS ->
                KstatesSlot.MacroName(name, range, isDeclaration = true)
            else -> KstatesSlot.None
        }
    }

    private fun outsideStrings(context: CaretContext): KstatesSlot {
        val call = context.call
        if (call != null) {
            val operation = call.name.uppercase()
            return when {
                operation in KarpfenSyntax.KSTATES_OPERATIONS && call.argumentIndex >= 1 &&
                    !(operation in KarpfenSyntax.KSTATES_RELATION_OPERATIONS && call.argumentIndex == 1) ->
                    KstatesSlot.Keywords(KstatesPosition.RIGHT_SIDE)
                call.name == TAKES && call.argumentIndex == 1 ->
                    KstatesSlot.TypeName("", context.replaceRange, primitives = true, inString = false, wrappers = listOf(LIST))
                call.name == RETURNS && call.argumentIndex == 0 ->
                    KstatesSlot.TypeName("", context.replaceRange, primitives = true, inString = false, wrappers = listOf(LIST, REFERENCE))
                else -> KstatesSlot.None
            }
        }
        val statement = context.statement
        val block = context.innermostBlock?.let(KstatesBlocks::kindOf)
        if (block == TRANSITIONS && isTransitionHeader(statement) && statement.lastOrNull()?.kind == TokenKind.STRING) {
            return KstatesSlot.Keywords(KstatesPosition.TRANSITION_HEADER)
        }
        if (statement.isNotEmpty() && statement.last().text != ")") return KstatesSlot.None
        val position = if (context.blocks.isEmpty()) KstatesPosition.TOP_LEVEL.takeIf { statement.isEmpty() } else POSITIONS[block]
        return position?.let(KstatesSlot::Keywords) ?: KstatesSlot.None
    }

    /** True if the statement ends with `TRANSITION|SPLIT|JOIN` followed only by state strings, commas and arrows. */
    private fun isTransitionHeader(statement: List<ContextToken>): Boolean {
        val start = statement.indexOfLast { it.kind == TokenKind.KEYWORD && it.text in TRANSITION_KEYWORDS }
        return start >= 0 &&
            statement.subList(start + 1, statement.size).all { it.kind == TokenKind.STRING || it.text == "," || it.text == "->" }
    }

    private fun ContextToken.isKeyword(text: String): Boolean = kind == TokenKind.KEYWORD && this.text == text

    internal companion object {
        const val MACRO = "MACRO"
        const val MACROS = "MACROS"
        const val EVENT = "EVENT"
        const val VALUE = "VALUE"
        const val SCOPE = "SCOPE"
        const val TAKES = "TAKES"
        const val RETURNS = "RETURNS"
        const val LIST = "list"
        const val REFERENCE = "reference"
        const val STATE = "STATE"
        const val AS = "AS"
        const val TO = "TO"
        const val ATTACHED = "ATTACHED"
        const val TRANSITIONS = "TRANSITIONS"

        /** The keywords that start a transition. */
        val TRANSITION_KEYWORDS: Set<String> = setOf("TRANSITION", "SPLIT", "JOIN")

        /** The calls whose string arguments are types (TAKES from its second argument on). */
        val TYPE_CALLS: Set<String> = setOf(TAKES, RETURNS, LIST, REFERENCE)

        /** The keyword position inside a block, by the block's introducing keyword ([KstatesBlocks.kindOf]). */
        val POSITIONS: Map<String, KstatesPosition> = mapOf(
            "STATEMACHINE" to KstatesPosition.MACHINE,
            "STATES" to KstatesPosition.STATES,
            "STATE" to KstatesPosition.STATE_BODY,
            "ENTRY" to KstatesPosition.ACTIONS,
            "DO" to KstatesPosition.ACTIONS,
            "IF" to KstatesPosition.ACTIONS,
            "WITH" to KstatesPosition.ACTIONS,
            TRANSITIONS to KstatesPosition.TRANSITIONS,
            "TRANSITION" to KstatesPosition.TRANSITION_BODY,
            "SPLIT" to KstatesPosition.TRANSITION_BODY,
            "JOIN" to KstatesPosition.TRANSITION_BODY,
            "CONDITION" to KstatesPosition.CONDITION,
            MACROS to KstatesPosition.MACROS,
            MACRO to KstatesPosition.MACRO_BODY,
            "DEFINITION" to KstatesPosition.DEFINITION,
        )
    }
}
