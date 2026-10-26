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

import io.karpfen.ide.core.document.syntax.KstatesBlockHeader
import io.karpfen.ide.core.document.syntax.KstatesBlockKind
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.lang.context.BlockFrame
import io.karpfen.ide.core.lang.context.ContextToken
import io.karpfen.ide.core.text.TextRange

/** A KStates block: its introducing keyword [kind] (upper case), the header from that keyword on, its range. */
internal data class KstatesBlock(val kind: String?, val construct: List<ContextToken>, val range: TextRange)

/**
 * The KStates reading of a [BlockFrame] header: the keyword that introduces the block is the one of core-document's
 * [KstatesBlockHeader] (the rule the tokenizer classifies `EVENT` by), so earlier actions or directives of the same
 * block in front of it do not count, keywords inside parentheses never do, and `MACRO` introduces a block only as a
 * definition (`MACRO "m" {`), not as a call (`WITH MACRO(…) AS "x" {`). Pure functions.
 */
internal object KstatesBlocks {

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

    /** The kind of block [frame] opens (actions, a condition, anything else), by the same rule. */
    fun blockKindOf(frame: BlockFrame): KstatesBlockKind = headerOf(frame).kind

    /** The header tokens from the introducing keyword on (e.g. `WITH MACRO ( m , a ) AS x`); empty without one. */
    fun constructOf(frame: BlockFrame): List<ContextToken> {
        val introducer = headerOf(frame).introducerIndex
        return if (introducer < 0) emptyList() else frame.header.subList(introducer, frame.header.size)
    }

    /** [frame]'s header read by core-document's block rule. */
    private fun headerOf(frame: BlockFrame): KstatesBlockHeader = KstatesBlockHeader.of(frame.header.map(::wordOf))

    /** A header token as the block rule reads it: a keyword in upper case, a parenthesis, or null. */
    private fun wordOf(token: ContextToken): String? = when (token.kind) {
        TokenKind.KEYWORD -> token.text.uppercase()
        TokenKind.PUNCTUATION -> token.text.takeIf { it == KstatesBlockHeader.OPEN || it == KstatesBlockHeader.CLOSE }
        else -> null
    }

    /** The keyword of a binding construct. */
    private const val WITH = "WITH"

    /** The keyword before a binding's name. */
    private const val AS = "AS"
}
