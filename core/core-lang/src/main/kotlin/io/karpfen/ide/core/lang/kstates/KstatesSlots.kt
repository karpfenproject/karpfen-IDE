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

import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.document.syntax.KstatesBlockKind
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.context.BlockFrame
import io.karpfen.ide.core.lang.context.CallContext
import io.karpfen.ide.core.lang.context.CaretContext
import io.karpfen.ide.core.lang.context.CaretContextDetector
import io.karpfen.ide.core.lang.context.ContextToken
import io.karpfen.ide.core.lang.context.EmbeddedContext
import io.karpfen.ide.core.text.TextRange

/**
 * What the caret is on or in, in a KStates document. Completion, hover and navigation all use this one classification.
 */
internal sealed interface KstatesSlot {
    /** Between constructs, where the structural keywords of [position] go. */
    data class Keywords(val position: KstatesPosition) : KstatesSlot

    /**
     * A state name in a TRANSITION, SPLIT or JOIN header, or the name of `STATE "s"`. [isDeclaration] is true for the
     * latter.
     */
    data class StateName(val name: String, val range: TextRange, val isDeclaration: Boolean) : KstatesSlot

    /**
     * A macro name. `MACRO("m"` and `@m` are references, and `MACRO "m" {` is a declaration, for which [isDeclaration]
     * is true.
     */
    data class MacroName(val name: String, val range: TextRange, val isDeclaration: Boolean) : KstatesSlot

    /**
     * The name of an attachable state machine. The name of `STATEMACHINE "n" ATTACHABLE TO` is a declaration, for which
     * [isDeclaration] is true. The string after `ATTACH` and the second argument of `EXCHANGE` are references.
     */
    data class StateMachineName(val name: String, val range: TextRange, val isDeclaration: Boolean) : KstatesSlot

    /**
     * A type. This is `ATTACHED TO "T"`, a type after `ATTACHABLE TO`, a TAKES or RETURNS type, or `list(` or
     * `reference(` inside them.
     * [primitives] allows `number`, `string` and `boolean`. [inString] is false at the argument before its quotes, and
     * then [name] is empty. There the [wrappers] `list` and `reference` are offered as well.
     */
    data class TypeName(
        val name: String,
        val range: TextRange,
        val primitives: Boolean,
        val inString: Boolean = true,
        val wrappers: List<String> = emptyList(),
    ) : KstatesSlot

    /**
     * A model path written in [range]. It stands in the strings of actions, VALUE, IF IN SCOPE and macro arguments, or
     * in `$( … )` in EVAL.
     */
    data class ModelPath(val range: TextRange) : KstatesSlot

    /** The relation argument of `SETOBJ` or `APPENDOBJ`, which is a relation of the type of [sourcePath]. */
    data class RelationKey(val sourcePath: String, val name: String, val range: TextRange) : KstatesSlot

    /** The name argument of `EVENT("d", "n")` in a condition or action, in the domain [domain]. */
    data class EventName(val name: String, val range: TextRange, val domain: String?) : KstatesSlot

    /** The domain argument of `EVENT("d", …)`. */
    data class EventDomain(val name: String, val range: TextRange) : KstatesSlot

    /** The name of `WITH … AS "x"`, which is a declaration. */
    data class BindingName(val name: String, val range: TextRange) : KstatesSlot

    /** The name of `TAKES("p", …)`, which is a declaration. */
    data class ParameterName(val name: String, val range: TextRange) : KstatesSlot

    /** Anything else, such as values, Python code, comments and broken positions. */
    data object None : KstatesSlot
}

/** Where structural keywords are completed. Each position offers the keywords that may start a construct there. */
internal enum class KstatesPosition {
    /** Outside every block, where the state machine goes. */
    TOP_LEVEL,

    /** After `STATEMACHINE` at top level, where the rest of either header form goes. */
    MACHINE_HEADER,

    /** After `STATEMACHINE "name"` at top level, where the rest of the attachable header goes. */
    NAMED_MACHINE_HEADER,

    /** In the state machine block, where the states, transitions and macros blocks go. */
    MACHINE,

    /** In the states block, where states go. */
    STATES,

    /** In a state, where its breakpoints, its tick limit, entry and do blocks and nested states go. */
    STATE_BODY,

    /**
     * In an action block, where operations, events, guards and bindings go. Entry, do, a scope guard and a binding
     * open action blocks.
     */
    ACTIONS,

    /** In the transitions block, where transitions, splits and joins go. */
    TRANSITIONS,

    /** After the states of a transition header, where `NOT LOOPING` goes. */
    TRANSITION_HEADER,

    /** In a transition, where its breakpoints and its condition go. */
    TRANSITION_BODY,

    /** In the breakpoint block of a state, where `ENTRY` and `EXIT` go. */
    STATE_BREAKPOINTS,

    /** In the breakpoint block of a transition, where `START` and `END` go. */
    TRANSITION_BREAKPOINTS,

    /** In the tick limit block of a state, where its delay and its timeout go. */
    TICK_LIMIT,

    /** In a condition, where event clauses, Python guards and boolean path guards go. */
    CONDITION,

    /** In the macros block, where macro definitions go. */
    MACROS,

    /** In a macro definition, where the parameters, the return type and the definition go. */
    MACRO_BODY,

    /** In the definition block of a macro, where its EVAL body goes. */
    DEFINITION,

    /** At the value argument of an operation or after `ATTACH`, where a macro call or an EVAL body goes. */
    RIGHT_SIDE,

    /**
     * Right after the closing parenthesis of `SETOBJ` or `APPENDOBJ` in an action block, where an `ATTACH` clause or the
     * next action goes.
     */
    OBJECT_RULE_END,
}

/** Classifies the caret of a KStates document from its [CaretContext]. The class has no state. */
internal class KstatesSlots(private val caretContexts: CaretContextDetector) {

    /** The caret context at [offset] and its slot. */
    fun at(document: LanguageDocument, offset: Int): Pair<CaretContext, KstatesSlot> {
        val context = caretContexts.detect(document, offset)
        return context to classify(document, context)
    }

    /**
     * The slot of [context]. It tries embedded references, call arguments and header strings, and falls back to
     * keyword positions.
     */
    private fun classify(document: LanguageDocument, context: CaretContext): KstatesSlot {
        if (context.inComment) return KstatesSlot.None
        context.embedded?.let { return embedded(document, context, it) }
        val string = context.string ?: return outsideStrings(context)
        val range = if (string.closed) string.contentRange else context.replaceRange
        val name = document.text.substring(range)
        val call = context.call ?: return headerString(context.statement, name, range, context)
        return callArgument(call, name, range)
    }

    /**
     * The slot inside an EVAL body. It is the path of a `$( … )` reference, the macro name of an `@m` reference, or
     * nothing.
     */
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

    /**
     * The slot of a string argument of [call]. Such arguments belong to macro calls, events and operations. They are
     * also the paths of VALUE and IF IN SCOPE and the types of TAKES and RETURNS.
     */
    private fun callArgument(call: CallContext, name: String, range: TextRange): KstatesSlot {
        val index = call.argumentIndex
        val operation = call.name.uppercase()
        return when {
            call.name == MACRO && index == 0 -> KstatesSlot.MacroName(name, range, isDeclaration = false)
            call.name == MACRO -> KstatesSlot.ModelPath(range)
            call.name == EVENT && index == 0 -> KstatesSlot.EventDomain(name, range)
            call.name == EVENT && index == 1 -> KstatesSlot.EventName(name, range, call.arguments.firstOrNull())
            operation in KarpfenSyntax.KSTATES_OPERATIONS && index == 0 -> KstatesSlot.ModelPath(range)
            operation == EXCHANGE && index == 1 -> KstatesSlot.StateMachineName(name, range, isDeclaration = false)
            operation in KarpfenSyntax.KSTATES_RELATION_OPERATIONS && index == 1 ->
                KstatesSlot.RelationKey(call.arguments.firstOrNull().orEmpty(), name, range)
            call.name == VALUE || call.name == SCOPE -> KstatesSlot.ModelPath(range)
            call.name == TAKES && index == 0 -> KstatesSlot.ParameterName(name, range)
            call.name in TYPE_CALLS -> KstatesSlot.TypeName(name, range, primitives = call.name != REFERENCE)
            else -> KstatesSlot.None
        }
    }

    /**
     * The slot of a string that is no call argument. The statement before the string decides what it names. After
     * `TRANSITION TO` it is the state a timeout leads to. After `STATEMACHINE` it is the name of an attachable state
     * machine, after `ATTACHED TO`, `ATTACHABLE TO` and a comma of an attachable header it is a type, and after
     * `ATTACH` it names an attachable state machine.
     */
    private fun headerString(statement: List<ContextToken>, name: String, range: TextRange, context: CaretContext): KstatesSlot {
        val last = statement.lastOrNull()
        return when {
            last == null -> KstatesSlot.None
            isTransitionHeader(statement) && last.kind != TokenKind.STRING -> KstatesSlot.StateName(name, range, isDeclaration = false)
            last.isKeyword(STATE) -> KstatesSlot.StateName(name, range, isDeclaration = true)
            last.isKeyword(TO) && statement.getOrNull(statement.size - 2)?.isKeyword(TRANSITION) == true ->
                KstatesSlot.StateName(name, range, isDeclaration = false)
            last.kind == TokenKind.KEYWORD && last.text.uppercase() == AS -> KstatesSlot.BindingName(name, range)
            last.isKeyword(TO) && statement.getOrNull(statement.size - 2)?.let { it.isKeyword(ATTACHED) || it.isKeyword(ATTACHABLE) } == true ->
                KstatesSlot.TypeName(name, range, primitives = false)
            last.text == "," && isAttachableHeader(statement) -> KstatesSlot.TypeName(name, range, primitives = false)
            last.isKeyword(STATEMACHINE) && statement.size == 1 -> KstatesSlot.StateMachineName(name, range, isDeclaration = true)
            last.isAttach() -> KstatesSlot.StateMachineName(name, range, isDeclaration = false)
            last.isKeyword(MACRO) && context.innermostBlock?.let(KstatesBlocks::kindOf) == MACROS ->
                KstatesSlot.MacroName(name, range, isDeclaration = true)
            else -> KstatesSlot.None
        }
    }

    /**
     * The slot outside strings. It is the right side of an operation or of `ATTACH`, or the type arguments of TAKES and
     * RETURNS before their quotes. It is the `NOT LOOPING` position after a transition header, and the rest of the
     * header after `STATEMACHINE`. Where no statement is in progress, it is the keyword position of the enclosing
     * block, or the place for an `ATTACH` clause right after `SETOBJ` or `APPENDOBJ`.
     */
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
        val frame = context.innermostBlock
        val block = frame?.let(KstatesBlocks::kindOf)
        if (block == TRANSITIONS && isTransitionHeader(statement) && statement.lastOrNull()?.kind == TokenKind.STRING) {
            return KstatesSlot.Keywords(KstatesPosition.TRANSITION_HEADER)
        }
        if (statement.lastOrNull()?.isAttach() == true) return KstatesSlot.Keywords(KstatesPosition.RIGHT_SIDE)
        if (frame == null) headerPosition(statement)?.let { return KstatesSlot.Keywords(it) }
        if (statement.isNotEmpty() && statement.last().text != ")") return KstatesSlot.None
        val position = if (frame == null) KstatesPosition.TOP_LEVEL.takeIf { statement.isEmpty() } else positionIn(context.blocks, block)
        val atObjectRule = position == KstatesPosition.ACTIONS && endsWithObjectRule(statement)
        return (if (atObjectRule) KstatesPosition.OBJECT_RULE_END else position)?.let(KstatesSlot::Keywords) ?: KstatesSlot.None
    }

    /**
     * The keyword position inside the innermost of [frames], whose introducing keyword is [block]. It is found by the
     * kind of the block, or else by the keyword. A breakpoint block is positioned by the block around it, which is a
     * state or a transition.
     */
    private fun positionIn(frames: List<BlockFrame>, block: String?): KstatesPosition? = when (KstatesBlocks.blockKindOf(frames.last())) {
        KstatesBlockKind.ACTION -> KstatesPosition.ACTIONS
        KstatesBlockKind.CONDITION -> KstatesPosition.CONDITION
        KstatesBlockKind.OTHER -> if (block == KarpfenSyntax.KSTATES_BREAKPOINT_BLOCK) breakpointsIn(frames) else POSITIONS[block]
    }

    /** The position inside the breakpoint block that is the innermost of [frames], by the block around it. */
    private fun breakpointsIn(frames: List<BlockFrame>): KstatesPosition? =
        when (frames.getOrNull(frames.size - 2)?.let(KstatesBlocks::kindOf)) {
            STATE -> KstatesPosition.STATE_BREAKPOINTS
            in TRANSITION_KEYWORDS -> KstatesPosition.TRANSITION_BREAKPOINTS
            else -> null
        }

    /**
     * True if the statement ends with `TRANSITION`, `SPLIT` or `JOIN` followed only by state strings, commas and
     * arrows.
     */
    private fun isTransitionHeader(statement: List<ContextToken>): Boolean {
        val start = statement.indexOfLast { it.kind == TokenKind.KEYWORD && it.text in TRANSITION_KEYWORDS }
        return start >= 0 &&
            statement.subList(start + 1, statement.size).all { it.kind == TokenKind.STRING || it.text == "," || it.text == "->" }
    }

    /** True if this token is the keyword [text]. */
    private fun ContextToken.isKeyword(text: String): Boolean = kind == TokenKind.KEYWORD && this.text == text

    /** True if this token is the keyword `ATTACH`, which is also written `attach`. */
    private fun ContextToken.isAttach(): Boolean = kind == TokenKind.KEYWORD && text.uppercase() == ATTACH

    /**
     * The keyword position of a header in progress at top level: after `STATEMACHINE`, or after `STATEMACHINE "name"`.
     * Null for any other statement.
     */
    private fun headerPosition(statement: List<ContextToken>): KstatesPosition? {
        if (statement.firstOrNull()?.isKeyword(STATEMACHINE) != true) return null
        return when {
            statement.size == 1 -> KstatesPosition.MACHINE_HEADER
            statement.size == 2 && statement[1].kind == TokenKind.STRING -> KstatesPosition.NAMED_MACHINE_HEADER
            else -> null
        }
    }

    /** True if [statement] is the header of an attachable state machine up to its list of types. */
    private fun isAttachableHeader(statement: List<ContextToken>): Boolean {
        val to = statement.indexOfFirst { it.isKeyword(TO) }
        return statement.firstOrNull()?.isKeyword(STATEMACHINE) == true && to > 0 && statement[to - 1].isKeyword(ATTACHABLE)
    }

    /**
     * True if [statement] ends with a whole `SETOBJ(…)` or `APPENDOBJ(…)`: its last operation is one of the two, and
     * the parenthesis after it closes with the last token.
     */
    private fun endsWithObjectRule(statement: List<ContextToken>): Boolean {
        val operation = statement.indexOfLast { it.kind == TokenKind.OPERATION }
        if (operation < 0 || statement[operation].text.uppercase() !in KarpfenSyntax.KSTATES_RELATION_OPERATIONS) return false
        var depth = 0
        for (index in operation + 1 until statement.size) {
            when (statement[index].text) {
                "(" -> depth++
                ")" -> if (--depth == 0) return index == statement.size - 1
            }
        }
        return false
    }

    /** The keywords and call names that the classification compares tokens with. */
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
        const val ATTACHABLE = "ATTACHABLE"
        const val ATTACH = "ATTACH"
        const val EXCHANGE = "EXCHANGE"
        const val STATEMACHINE = "STATEMACHINE"
        const val TRANSITIONS = "TRANSITIONS"
        const val TRANSITION = "TRANSITION"

        /** The keywords that start a transition. */
        val TRANSITION_KEYWORDS: Set<String> = setOf("TRANSITION", "SPLIT", "JOIN")

        /** The calls whose string arguments are types. For TAKES this starts at its second argument. */
        val TYPE_CALLS: Set<String> = setOf(TAKES, RETURNS, LIST, REFERENCE)

        /**
         * The keyword position inside a structural block, by the keyword that introduces the block, as
         * [KstatesBlocks.kindOf] finds it. Action and condition blocks are positioned by their kind instead, as
         * [KstatesBlocks.blockKindOf] finds it.
         */
        val POSITIONS: Map<String, KstatesPosition> = mapOf(
            "STATEMACHINE" to KstatesPosition.MACHINE,
            "STATES" to KstatesPosition.STATES,
            "STATE" to KstatesPosition.STATE_BODY,
            TRANSITIONS to KstatesPosition.TRANSITIONS,
            "TRANSITION" to KstatesPosition.TRANSITION_BODY,
            "SPLIT" to KstatesPosition.TRANSITION_BODY,
            "JOIN" to KstatesPosition.TRANSITION_BODY,
            MACROS to KstatesPosition.MACROS,
            MACRO to KstatesPosition.MACRO_BODY,
            "DEFINITION" to KstatesPosition.DEFINITION,
            KarpfenSyntax.KSTATES_TICK_LIMIT_BLOCK to KstatesPosition.TICK_LIMIT,
        )
    }
}
