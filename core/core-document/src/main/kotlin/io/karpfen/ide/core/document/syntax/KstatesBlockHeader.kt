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
package io.karpfen.ide.core.document.syntax

/** What a KStates brace block holds, by the keyword that introduces it. [KstatesBlockHeader] holds that rule. */
public enum class KstatesBlockKind {
    /**
     * Actions. The blocks of `ENTRY`, `DO`, `IF IN SCOPE(STATE)` and `WITH MACRO("m") AS "x"` hold them. The
     * introducing keywords are [KarpfenSyntax.KSTATES_ACTION_BLOCKS].
     */
    ACTION,

    /** A `CONDITION` block. */
    CONDITION,

    /** Any other block, also one whose header has no introducing keyword in broken text. */
    OTHER,
}

/**
 * The KStates block rule. It is the one answer to which keyword introduces a `{` block. The introducer is the last
 * block-introducing keyword of the block's header outside parentheses. `MACRO` introduces a block only as a definition,
 * as in `MACRO "m" {`. It never does as a call, where `MACRO` is directly followed by `(`, as in
 * `WITH MACRO("m") AS "x" {`. The keywords and the kinds they open come from [KarpfenSyntax].
 *
 * The KStates tokenizer classifies `EVENT` by this rule, and the language services in core-lang read blocks by it.
 * Highlighting of `EVENT` as an operation and the action snippets of completion therefore agree on every block. That
 * holds in broken text too, such as `WITH MACRO {` or `IF IN SCOPE(STATE) {`.
 *
 * A header is the block's tokens before its `{`, back to the previous brace or to the start of the call argument that
 * the block is. Feed them in order to [accept]. [introducer], [introducerIndex] and [kind] then describe the block. Use
 * one instance per header. The class is not thread-safe.
 */
public class KstatesBlockHeader {

    /** The number of tokens accepted so far. */
    private var accepted = 0

    /** The parenthesis depth at the current token. Keywords inside parentheses introduce nothing. */
    private var depth = 0

    /** The last introducing keyword found so far, or null. */
    private var keyword: String? = null

    /** The index of [keyword] among the accepted tokens, or -1. */
    private var keywordIndex = -1

    /**
     * The index of a `MACRO` whose role the next token decides, or -1. If the next token is `(`, the `MACRO` is a call,
     * otherwise it is a definition. While it is pending, a header that ends here counts it as the introducer.
     */
    private var macroIndex = -1

    /**
     * Feeds the next token of the header. [word] is its keyword in upper case, `(` or `)` for a parenthesis, and null
     * for any other token.
     */
    public fun accept(word: String?) {
        if (macroIndex >= 0 && word != OPEN) found(MACRO, macroIndex)
        macroIndex = -1
        when {
            word == OPEN -> depth++
            word == CLOSE -> depth = maxOf(0, depth - 1)
            depth > 0 || word == null -> Unit
            word == MACRO -> macroIndex = accepted
            word in KarpfenSyntax.KSTATES_ACTION_BLOCKS || word == KarpfenSyntax.KSTATES_CONDITION_BLOCK ||
                word in KarpfenSyntax.KSTATES_STRUCTURE_BLOCKS -> found(word, accepted)
        }
        accepted++
    }

    /** The keyword in upper case that introduces the block, or null if the header has none. */
    public val introducer: String? get() = if (macroIndex >= 0) MACRO else keyword

    /** The index of [introducer] among the accepted tokens, or -1 if there is none. */
    public val introducerIndex: Int get() = if (macroIndex >= 0) macroIndex else keywordIndex

    /** The kind of block the header opens. */
    public val kind: KstatesBlockKind
        get() = when (introducer) {
            in KarpfenSyntax.KSTATES_ACTION_BLOCKS -> KstatesBlockKind.ACTION
            KarpfenSyntax.KSTATES_CONDITION_BLOCK -> KstatesBlockKind.CONDITION
            else -> KstatesBlockKind.OTHER
        }

    /** Records [word] at [index] as the introducer found last. */
    private fun found(word: String, index: Int) {
        keyword = word
        keywordIndex = index
    }

    public companion object {
        /** The word of an opening parenthesis for [accept]. */
        public const val OPEN: String = "("

        /** The word of a closing parenthesis for [accept]. */
        public const val CLOSE: String = ")"

        /** The keyword that introduces a block as a definition and none as a call. */
        private const val MACRO = "MACRO"

        /** Every keyword in upper case that may introduce a block, for callers that map their tokens to words. */
        public val INTRODUCERS: Set<String> =
            KarpfenSyntax.KSTATES_ACTION_BLOCKS + KarpfenSyntax.KSTATES_CONDITION_BLOCK + KarpfenSyntax.KSTATES_STRUCTURE_BLOCKS

        /** The header of [words], each given as [accept] takes it. */
        public fun of(words: List<String?>): KstatesBlockHeader = KstatesBlockHeader().apply { words.forEach(::accept) }
    }
}
