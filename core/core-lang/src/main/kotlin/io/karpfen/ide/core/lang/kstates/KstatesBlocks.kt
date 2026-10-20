package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.lang.context.BlockFrame
import io.karpfen.ide.core.lang.context.ContextToken
import io.karpfen.ide.core.text.TextRange

/** A KStates block: its introducing keyword [kind] (upper case), the header from that keyword on, its range. */
internal data class KstatesBlock(val kind: String?, val construct: List<ContextToken>, val range: TextRange)

/**
 * The KStates reading of a [BlockFrame] header: the keyword that introduces the block is the last block-introducing
 * keyword outside parentheses (the rule of the tokenizer's block tracker), so earlier actions or directives of the same
 * block in front of it do not count; `MACRO` introduces a block only as a definition (`MACRO "m" {`), not as a call
 * (`WITH MACRO(…) AS "x" {`). Pure functions.
 */
internal object KstatesBlocks {

    private val INTRODUCERS: Set<String> = setOf(
        "STATEMACHINE", "STATES", "STATE", "ENTRY", "DO", "IF", "WITH", "TRANSITIONS", "TRANSITION", "SPLIT", "JOIN",
        "CONDITION", "MACROS", KstatesSlots.MACRO, "DEFINITION", "EVAL",
    )

    /** The introducing keyword of [frame] (upper case: `STATE`, `WITH`, `MACRO`, …), or null if it has none. */
    fun kindOf(frame: BlockFrame): String? = constructOf(frame).firstOrNull()?.text?.uppercase()

    /** The name of the macro definition among [frames] (innermost), or null outside macros. */
    fun macroOf(frames: List<BlockFrame>): String? =
        frames.map(::of).lastOrNull { it.kind == KstatesSlots.MACRO }?.construct?.getOrNull(1)?.takeIf { it.kind == TokenKind.STRING }?.text

    /** The macro `m` of a `WITH MACRO("m", …) AS "x"` construct (its tokens from `WITH` on), or null. */
    fun boundMacroOf(construct: List<ContextToken>): String? =
        construct.getOrNull(3)?.takeIf { construct.getOrNull(1)?.text == KstatesSlots.MACRO && it.kind == TokenKind.STRING }?.text

    /** The name `x` of a `WITH … AS "x"` construct (its tokens from `WITH` on), or null while it has none. */
    fun bindingNameOf(construct: List<ContextToken>): ContextToken? {
        val asIndex = construct.indexOfLast { it.kind == TokenKind.KEYWORD && it.text.uppercase() == AS }
        return construct.getOrNull(asIndex + 1)?.takeIf { asIndex >= 0 && it.kind == TokenKind.STRING }
    }

    /** The tokens of the `WITH` construct at the end of [statement] (from `WITH` on), or empty if there is none. */
    fun withConstructOf(statement: List<ContextToken>): List<ContextToken> {
        val with = statement.indexOfLast { it.kind == TokenKind.KEYWORD && it.text.uppercase() == WITH }
        return if (with < 0) emptyList() else statement.subList(with, statement.size)
    }

    /** [frame] read as a KStates block. */
    fun of(frame: BlockFrame): KstatesBlock = constructOf(frame).let { KstatesBlock(it.firstOrNull()?.text?.uppercase(), it, frame.range) }

    /** The header tokens from the introducing keyword on (e.g. `WITH MACRO ( m , a ) AS x`); empty without one. */
    fun constructOf(frame: BlockFrame): List<ContextToken> {
        val header = frame.header
        var depth = 0
        var introducer = -1
        for ((index, token) in header.withIndex()) {
            if (token.kind == TokenKind.PUNCTUATION && token.text == "(") depth++
            if (token.kind == TokenKind.PUNCTUATION && token.text == ")") depth = maxOf(0, depth - 1)
            if (depth == 0 && token.kind == TokenKind.KEYWORD && token.text.uppercase() in INTRODUCERS && !isMacroCall(header, index)) {
                introducer = index
            }
        }
        return if (introducer < 0) emptyList() else header.subList(introducer, header.size)
    }

    private fun isMacroCall(header: List<ContextToken>, index: Int): Boolean =
        header[index].text == KstatesSlots.MACRO && header.getOrNull(index + 1)?.text == "("

    private const val WITH = "WITH"
    private const val AS = "AS"
}
