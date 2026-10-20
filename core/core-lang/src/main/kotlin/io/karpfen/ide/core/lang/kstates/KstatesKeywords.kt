package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.lang.completion.Snippet

/**
 * The structural keywords of KStates by position (spec §5): the snippets offered at each [KstatesPosition]. The
 * vocabulary is the grammar's; a template's `$0` is where the caret goes. Constant data.
 */
internal object KstatesKeywords {

    private val STATE = Snippet("STATE \"…\" { }", "STATE \"\$0\" { }", detail = "state")
    private val INITIAL_STATE = Snippet("INITIAL STATE \"…\" { }", "INITIAL STATE \"\$0\" { }", detail = "initial state")
    private val EVENT = Snippet("EVENT(\"…\", \"…\")", "EVENT(\"\$0\", \"\")", detail = "event")
    private val EVAL = Snippet("EVAL { }", "EVAL { \$0 }", detail = "embedded Python")
    private val MACRO_CALL = Snippet("MACRO(\"…\")", "MACRO(\"\$0\")", detail = "macro call")

    private val ACTIONS = listOf(
        Snippet("SET(\"…\", …)", "SET(\"\$0\", )", detail = "set a property"),
        Snippet("APPEND(\"…\", …)", "APPEND(\"\$0\", )", detail = "append to a list property"),
        Snippet("SETLIST(\"…\", …)", "SETLIST(\"\$0\", )", detail = "replace a list property"),
        Snippet("DROPLIST(\"…\")", "DROPLIST(\"\$0\")", detail = "clear a list property"),
        Snippet("SETOBJ(\"…\", \"…\", …)", "SETOBJ(\"\$0\", \"\", )", detail = "set a relation"),
        Snippet("APPENDOBJ(\"…\", \"…\", …)", "APPENDOBJ(\"\$0\", \"\", )", detail = "append to a relation"),
        Snippet("DROPOBJ(\"…\")", "DROPOBJ(\"\$0\")", detail = "remove an object"),
        EVENT.copy(detail = "emit an event"),
        Snippet("IF IN SCOPE(\"…\") { }", "IF IN SCOPE(\"\$0\") { }", detail = "guard on paths in scope"),
        Snippet("WITH … AS \"…\" { }", "WITH MACRO(\"\$0\") AS \"\" { }", detail = "bind a macro result"),
    )

    private val BY_POSITION: Map<KstatesPosition, List<Snippet>> = mapOf(
        KstatesPosition.TOP_LEVEL to listOf(
            Snippet("STATEMACHINE ATTACHED TO \"…\" { }", "STATEMACHINE ATTACHED TO \"\$0\" { }", detail = "state machine"),
        ),
        KstatesPosition.MACHINE to listOf(
            Snippet("STATES { }", "STATES { \$0 }", detail = "states block"),
            Snippet("TRANSITIONS { }", "TRANSITIONS { \$0 }", detail = "transitions block"),
            Snippet("MACROS { }", "MACROS { \$0 }", detail = "macros block"),
        ),
        KstatesPosition.STATES to listOf(STATE, INITIAL_STATE),
        KstatesPosition.STATE_BODY to listOf(
            Snippet("ENTRY { }", "ENTRY { \$0 }", detail = "entry actions"),
            Snippet("DO { }", "DO { \$0 }", detail = "do actions"),
            STATE,
            INITIAL_STATE,
        ),
        KstatesPosition.ACTIONS to ACTIONS,
        KstatesPosition.TRANSITIONS to listOf(
            Snippet("TRANSITION \"…\" -> \"…\" { }", "TRANSITION \"\$0\" -> \"\" { }", detail = "transition"),
            Snippet("SPLIT \"…\" -> \"…\", \"…\" { }", "SPLIT \"\$0\" -> \"\", \"\" { }", detail = "fork into parallel states"),
            Snippet("JOIN \"…\", \"…\" -> \"…\" { }", "JOIN \"\$0\", \"\" -> \"\" { }", detail = "join parallel states"),
        ),
        KstatesPosition.TRANSITION_HEADER to listOf(Snippet.keyword("NOT LOOPING", detail = "no self-loop")),
        KstatesPosition.TRANSITION_BODY to listOf(Snippet("CONDITION { }", "CONDITION { \$0 }", detail = "guard")),
        KstatesPosition.CONDITION to listOf(
            EVENT.copy(detail = "event clause"),
            EVAL.copy(detail = "Python guard"),
            Snippet("VALUE(\"…\")", "VALUE(\"\$0\")", detail = "boolean path guard"),
        ),
        KstatesPosition.MACROS to listOf(Snippet("MACRO \"…\" { }", "MACRO \"\$0\" { }", detail = "macro definition")),
        KstatesPosition.MACRO_BODY to listOf(
            Snippet("TAKES(\"…\", \"…\")", "TAKES(\"\$0\", \"\")", detail = "parameter"),
            Snippet("RETURNS(\"…\")", "RETURNS(\"\$0\")", detail = "return type"),
            Snippet("DEFINITION { EVAL { } }", "DEFINITION { EVAL { \$0 } }", detail = "macro body"),
        ),
        KstatesPosition.DEFINITION to listOf(EVAL),
        KstatesPosition.RIGHT_SIDE to listOf(MACRO_CALL, EVAL),
    )

    /** The `list("…")` and `reference("…")` type wrappers of TAKES/RETURNS. */
    val WRAPPERS: Map<String, Snippet> = mapOf(
        KstatesSlots.LIST to Snippet("list(\"…\")", "list(\"\$0\")", detail = "list type"),
        KstatesSlots.REFERENCE to Snippet("reference(\"…\")", "reference(\"\$0\")", detail = "object reference"),
    )

    /** The snippets offered at [position]. */
    fun at(position: KstatesPosition): List<Snippet> = BY_POSITION[position].orEmpty()
}
