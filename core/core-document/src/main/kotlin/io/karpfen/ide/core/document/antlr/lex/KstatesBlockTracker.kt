package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.syntax.TokenKind
import kstates.KstatesLexer

/**
 * Follows the block structure of a KStates document on its brace tokens to classify `EVENT` (spec §3.1): an action
 * ([TokenKind.OPERATION]) directly inside an action block, a [TokenKind.KEYWORD] in a CONDITION block and anywhere
 * else (also in broken text, where the block is unknown).
 *
 * A block's kind is decided by the last block-introducing keyword before its `{` since the previous brace:
 * `CONDITION` opens a condition block; `ENTRY`, `DO`, `IF` (`IF IN SCOPE(…)`) and `WITH` (`WITH MACRO(…) AS "x"`) open
 * action blocks; the structural keywords (`STATEMACHINE`, `STATES`, `INITIAL`, `STATE`, `TRANSITIONS`, `TRANSITION`,
 * `SPLIT`, `JOIN`, `MACROS`, `DEFINITION`, `EVAL`) and a macro definition (`MACRO "m" {`) open other blocks. `MACRO`
 * counts only when no other keyword is pending, so the macro call of `WITH MACRO(…) AS "x" {` keeps the action block.
 *
 * One instance per tokenization; fed with the default-channel tokens outside EVAL bodies in document order (the
 * body's own `{` and `}` included, so its inner braces, which the tokenizer skips, cannot unbalance the stack).
 */
internal class KstatesBlockTracker {

    private enum class Block { ACTION, CONDITION, OTHER }

    private val open = ArrayDeque<Block>()
    private var introducer: Block? = null

    /** Feeds the next default-channel token of type [tokenType]. */
    fun accept(tokenType: Int) {
        when (tokenType) {
            KstatesLexer.LBRACE -> {
                open.addLast(introducer ?: Block.OTHER)
                introducer = null
            }
            KstatesLexer.RBRACE -> {
                open.removeLastOrNull()
                introducer = null
            }
            KstatesLexer.CONDITION -> introducer = Block.CONDITION
            in ACTION_INTRODUCERS -> introducer = Block.ACTION
            in STRUCTURAL_INTRODUCERS -> introducer = Block.OTHER
            KstatesLexer.MACRO -> if (introducer == null) introducer = Block.OTHER
        }
    }

    /** The kind of an `EVENT` token at the current position. */
    fun eventKind(): TokenKind = if (open.lastOrNull() == Block.ACTION) TokenKind.OPERATION else TokenKind.KEYWORD

    private companion object {
        val ACTION_INTRODUCERS: Set<Int> = setOf(KstatesLexer.ENTRY, KstatesLexer.DO, KstatesLexer.IF, KstatesLexer.WITH)

        val STRUCTURAL_INTRODUCERS: Set<Int> = setOf(
            KstatesLexer.STATEMACHINE, KstatesLexer.STATES, KstatesLexer.INITIAL, KstatesLexer.STATE,
            KstatesLexer.TRANSITIONS, KstatesLexer.TRANSITION, KstatesLexer.SPLIT, KstatesLexer.JOIN,
            KstatesLexer.MACROS, KstatesLexer.DEFINITION, KstatesLexer.EVAL,
        )
    }
}
