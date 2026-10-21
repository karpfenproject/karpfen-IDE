package io.karpfen.ide.core.document.syntax

/** What a KStates brace block holds, by the keyword that introduces it (see [KstatesBlockHeader]). */
public enum class KstatesBlockKind {
    /** Actions: `ENTRY`, `DO`, `IF IN SCOPE(…)`, `WITH MACRO(…) AS "x"` ([KarpfenSyntax.KSTATES_ACTION_BLOCKS]). */
    ACTION,

    /** A `CONDITION` block. */
    CONDITION,

    /** Any other block, also one whose header has no introducing keyword (broken text). */
    OTHER,
}

/**
 * The KStates block rule, the one answer to "which keyword introduces this `{` block" (decision 53): the last
 * block-introducing keyword of the block's header outside parentheses, where `MACRO` introduces a block only as a
 * definition (`MACRO "m" {`), never as a call (`MACRO` directly followed by `(`, as in `WITH MACRO(…) AS "x" {`). The
 * keywords and the kinds they open are [KarpfenSyntax]'s. The KStates tokenizer classifies `EVENT` by it and the
 * language services (core-lang) read blocks by it, so highlighting and completion agree on every block, broken text
 * included.
 *
 * A header is a block's tokens before its `{`, back to the previous brace (or to the start of the call argument the
 * block is), fed in order to [accept]; [introducer], [introducerIndex] and [kind] then describe the block. One instance
 * per header; not thread-safe.
 */
public class KstatesBlockHeader {

    private var accepted = 0
    private var depth = 0
    private var keyword: String? = null
    private var keywordIndex = -1
    private var macroIndex = -1

    /**
     * Feeds the next token of the header: [word] is its keyword in upper case, `(` or `)` for a parenthesis, and null
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

    /** The keyword that introduces the block (upper case), or null if the header has none. */
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

    private fun found(word: String, index: Int) {
        keyword = word
        keywordIndex = index
    }

    public companion object {
        /** The word of an opening parenthesis for [accept]. */
        public const val OPEN: String = "("

        /** The word of a closing parenthesis for [accept]. */
        public const val CLOSE: String = ")"

        private const val MACRO = "MACRO"

        /** Every keyword that may introduce a block (upper case), for callers that map their tokens to words. */
        public val INTRODUCERS: Set<String> =
            KarpfenSyntax.KSTATES_ACTION_BLOCKS + KarpfenSyntax.KSTATES_CONDITION_BLOCK + KarpfenSyntax.KSTATES_STRUCTURE_BLOCKS

        /** The header of [words] (each as [accept] takes it). */
        public fun of(words: List<String?>): KstatesBlockHeader = KstatesBlockHeader().apply { words.forEach(::accept) }
    }
}
