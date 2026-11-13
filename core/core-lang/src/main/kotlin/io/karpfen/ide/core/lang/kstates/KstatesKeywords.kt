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

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.lang.completion.Snippet

/**
 * The structural keywords of KStates by position, which are the snippets offered at each [KstatesPosition]. The
 * vocabulary comes from the grammar. The action operations come from [KarpfenSyntax], with one snippet per operation
 * shaped by its arguments. The `$0` in a template is where the caret goes. The snippets that write a construct of an
 * optional language feature, such as a history or a macro, are offered only while that feature is on. The object
 * holds constant data only.
 */
internal object KstatesKeywords {

    /** A state. */
    private val STATE = Snippet("STATE \"…\" { }", "STATE \"\$0\" { }", detail = "state")
    /** The initial state of a states block. */
    private val INITIAL_STATE = Snippet("INITIAL STATE \"…\" { }", "INITIAL STATE \"\$0\" { }", detail = "initial state")
    /** A state that remembers its directly nested state. */
    private val SHALLOW_HISTORY_STATE =
        Snippet("SHALLOW HISTORY STATE \"…\" { }", "SHALLOW HISTORY STATE \"\$0\" { }", detail = "state with a shallow history")
    /** A state that remembers its innermost state. */
    private val DEEP_HISTORY_STATE =
        Snippet("DEEP HISTORY STATE \"…\" { }", "DEEP HISTORY STATE \"\$0\" { }", detail = "state with a deep history")
    /** The breakpoints of a state or a transition. */
    private val BREAKPOINT = Snippet("BREAKPOINT { }", "BREAKPOINT { \$0 }", detail = "breakpoints")
    /** An event, emitted as an action or awaited in a condition. */
    private val EVENT = Snippet("EVENT(\"…\", \"…\")", "EVENT(\"\$0\", \"\")", detail = "event")
    /** An embedded Python body. */
    private val EVAL = Snippet("EVAL { }", "EVAL { \$0 }", detail = "embedded Python")
    /** A call of a macro as the value of an action. */
    private val MACRO_CALL = Snippet("MACRO(\"…\")", "MACRO(\"\$0\")", detail = "macro call")

    /** What each operation does, for the completion list. An operation without an entry is offered without a detail. */
    private val OPERATION_DETAILS: Map<String, String> = mapOf(
        "SET" to "set a property",
        "APPEND" to "append to a list property",
        "SETLIST" to "replace a list property",
        "DROPLIST" to "clear a list property",
        "SETOBJ" to "set a relation",
        "APPENDOBJ" to "append to a relation",
        "DROPOBJ" to "remove an object",
    )

    /**
     * The offers of an action block. These are every operation, an emitted event, the scope guard and the macro
     * binding, which needs macros.
     */
    private val ACTIONS: List<Offer> = KarpfenSyntax.KSTATES_OPERATIONS.map { Offer(operation(it)) } + listOf(
        Offer(EVENT.copy(detail = "emit an event")),
        Offer(Snippet("IF IN SCOPE(\"…\") { }", "IF IN SCOPE(\"\$0\") { }", detail = "guard on paths in scope")),
        Offer(Snippet("WITH … AS \"…\" { }", "WITH MACRO(\"\$0\") AS \"\" { }", detail = "bind a macro result"), KstatesFeature.MACROS),
    )

    /** The offers of each position. */
    private val BY_POSITION: Map<KstatesPosition, List<Offer>> = mapOf(
        KstatesPosition.TOP_LEVEL to offers(
            Snippet("STATEMACHINE ATTACHED TO \"…\" { }", "STATEMACHINE ATTACHED TO \"\$0\" { }", detail = "state machine"),
        ),
        KstatesPosition.MACHINE to offers(
            Snippet("STATES { }", "STATES { \$0 }", detail = "states block"),
            Snippet("TRANSITIONS { }", "TRANSITIONS { \$0 }", detail = "transitions block"),
        ) + offers(KstatesFeature.MACROS, Snippet("MACROS { }", "MACROS { \$0 }", detail = "macros block")),
        KstatesPosition.STATES to offers(STATE, INITIAL_STATE) + offers(KstatesFeature.HISTORY, SHALLOW_HISTORY_STATE, DEEP_HISTORY_STATE),
        KstatesPosition.STATE_BODY to
            offers(KstatesFeature.BREAKPOINTS, BREAKPOINT) +
            offers(KstatesFeature.TICK_LIMIT, Snippet("TICK LIMIT { }", "TICK LIMIT { \$0 }", detail = "delay and timeout")) +
            offers(
                Snippet("ENTRY { }", "ENTRY { \$0 }", detail = "entry actions"),
                Snippet("DO { }", "DO { \$0 }", detail = "do actions"),
                STATE,
                INITIAL_STATE,
            ) +
            offers(KstatesFeature.HISTORY, SHALLOW_HISTORY_STATE, DEEP_HISTORY_STATE),
        KstatesPosition.STATE_BREAKPOINTS to offers(
            KstatesFeature.BREAKPOINTS,
            Snippet.keyword("ENTRY", detail = "halt before the state is entered"),
            Snippet.keyword("EXIT", detail = "halt before the state is left"),
        ),
        KstatesPosition.TRANSITION_BREAKPOINTS to offers(
            KstatesFeature.BREAKPOINTS,
            Snippet.keyword("START", detail = "halt before the transition"),
            Snippet.keyword("END", detail = "halt after the transition"),
        ),
        KstatesPosition.TICK_LIMIT to offers(
            KstatesFeature.TICK_LIMIT,
            Snippet("DELAY: … TICKS", "DELAY: \$0 TICKS", detail = "least ticks in the state"),
            Snippet(
                "TIMEOUT: AFTER … TICKS TRANSITION TO \"…\"", "TIMEOUT: AFTER \$0 TICKS TRANSITION TO \"\"",
                detail = "leave the state after ticks",
            ),
        ),
        KstatesPosition.ACTIONS to ACTIONS,
        KstatesPosition.TRANSITIONS to
            offers(Snippet("TRANSITION \"…\" -> \"…\" { }", "TRANSITION \"\$0\" -> \"\" { }", detail = "transition")) +
            offers(
                KstatesFeature.PARALLEL,
                Snippet("SPLIT \"…\" -> \"…\", \"…\" { }", "SPLIT \"\$0\" -> \"\", \"\" { }", detail = "fork into parallel states"),
                Snippet("JOIN \"…\", \"…\" -> \"…\" { }", "JOIN \"\$0\", \"\" -> \"\" { }", detail = "join parallel states"),
            ),
        KstatesPosition.TRANSITION_HEADER to offers(Snippet.keyword("NOT LOOPING", detail = "no self-loop")),
        KstatesPosition.TRANSITION_BODY to
            offers(KstatesFeature.BREAKPOINTS, BREAKPOINT) + offers(Snippet("CONDITION { }", "CONDITION { \$0 }", detail = "guard")),
        KstatesPosition.CONDITION to offers(
            EVENT.copy(detail = "event clause"),
            EVAL.copy(detail = "Python guard"),
            Snippet("VALUE(\"…\")", "VALUE(\"\$0\")", detail = "boolean path guard"),
        ),
        KstatesPosition.MACROS to offers(KstatesFeature.MACROS, Snippet("MACRO \"…\" { }", "MACRO \"\$0\" { }", detail = "macro definition")),
        KstatesPosition.MACRO_BODY to offers(
            KstatesFeature.MACROS,
            Snippet("TAKES(\"…\", \"…\")", "TAKES(\"\$0\", \"\")", detail = "parameter"),
            Snippet("RETURNS(\"…\")", "RETURNS(\"\$0\")", detail = "return type"),
            Snippet("DEFINITION { EVAL { } }", "DEFINITION { EVAL { \$0 } }", detail = "macro body"),
        ),
        KstatesPosition.DEFINITION to offers(EVAL),
        KstatesPosition.RIGHT_SIDE to offers(KstatesFeature.MACROS, MACRO_CALL) + offers(EVAL),
    )

    /** The `list("…")` and `reference("…")` type wrappers of TAKES/RETURNS. */
    val WRAPPERS: Map<String, Snippet> = mapOf(
        KstatesSlots.LIST to Snippet("list(\"…\")", "list(\"\$0\")", detail = "list type"),
        KstatesSlots.REFERENCE to Snippet("reference(\"…\")", "reference(\"\$0\")", detail = "object reference"),
    )

    /**
     * The snippets offered at [position] while the optional language features [switchedOn] are on. A snippet of a
     * feature that is off is left out.
     */
    fun at(position: KstatesPosition, switchedOn: Set<KstatesFeature>): List<Snippet> =
        BY_POSITION[position].orEmpty().filter { it.feature == null || it.feature in switchedOn }.map { it.snippet }

    /** An offer of each of [snippets] that needs no optional language feature. */
    private fun offers(vararg snippets: Snippet): List<Offer> = snippets.map { Offer(it) }

    /** An offer of each of [snippets] that is made only while [feature] is on. */
    private fun offers(feature: KstatesFeature, vararg snippets: Snippet): List<Offer> = snippets.map { Offer(it, feature) }

    /**
     * A snippet as it is offered.
     *
     * @property snippet the snippet.
     * @property feature the optional language feature whose construct the snippet writes, or null if it needs none.
     */
    private class Offer(val snippet: Snippet, val feature: KstatesFeature? = null)

    /**
     * The snippet of the KStates operation [name] from [KarpfenSyntax.KSTATES_OPERATIONS], shaped by its arguments. A
     * unary operation takes a path only. A relation operation takes a path, a relation and a value. Every other
     * operation takes a path and a value.
     */
    private fun operation(name: String): Snippet = when (name) {
        in KarpfenSyntax.KSTATES_UNARY_OPERATIONS -> Snippet("$name(\"…\")", "$name(\"\$0\")", detail = OPERATION_DETAILS[name])
        in KarpfenSyntax.KSTATES_RELATION_OPERATIONS ->
            Snippet("$name(\"…\", \"…\", …)", "$name(\"\$0\", \"\", )", detail = OPERATION_DETAILS[name])
        else -> Snippet("$name(\"…\", …)", "$name(\"\$0\", )", detail = OPERATION_DETAILS[name])
    }
}
