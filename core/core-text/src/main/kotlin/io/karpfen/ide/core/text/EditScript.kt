package io.karpfen.ide.core.text

import java.util.Collections

/**
 * A normalized list of [TextEdit]s in the coordinates of one text: no no-op edits, sorted by range start and then by
 * range end (stable, so insertions at the same offset keep their order and an insertion at the start of a replaced
 * range goes before the replacement), no two edits overlapping and no insertion inside a replaced range.
 *
 * It is the one representation of "what a change did" inside core-text: [TextChange.edits] of every applied change,
 * the entries of the [EditLog] used for offset mapping, and the forward/backward edits of every undo step. Immutable
 * and therefore safe to share between threads.
 */
internal class EditScript private constructor(edits: List<TextEdit>) {

    /** The edits, sorted and non-overlapping (read-only view). */
    val edits: List<TextEdit> = Collections.unmodifiableList(edits)

    /** True if the script changes nothing. */
    val isEmpty: Boolean get() = edits.isEmpty()

    /** The index [mapOffset] searches, built on first use (most scripts are never mapped across). */
    private val mapper: OffsetMapper by lazy(LazyThreadSafetyMode.PUBLICATION) { OffsetMapper(edits) }

    /** Applies the script to [text], the text whose coordinates it uses. */
    fun applyTo(text: String): String {
        if (edits.isEmpty()) return text
        val result = StringBuilder(text.length + edits.sumOf { it.lengthDelta }.coerceAtLeast(0))
        var position = 0
        for (edit in edits) {
            result.append(text, position, edit.range.start).append(edit.newText)
            position = edit.range.end
        }
        return result.append(text, position, text.length).toString()
    }

    /**
     * The script that undoes this one: applied to the result of [applyTo] on [before], it restores [before] exactly.
     * Its edits are in the coordinates of that result.
     */
    fun inverted(before: String): EditScript {
        var delta = 0
        val inverse = edits.map { edit ->
            val start = edit.range.start + delta
            delta += edit.lengthDelta
            TextEdit(TextRange.ofLength(start, edit.newText.length), before.substring(edit.range.start, edit.range.end))
        }
        return EditScript(inverse)
    }

    /**
     * Maps [offset] (in the coordinates before the script) to the coordinates after it, with [bias] deciding at edit
     * points (see [Bias]). Offsets past the end of the text are shifted like every offset after the last edit.
     *
     * - [Bias.RIGHT] keeps the offset in front of the first character at or after it that survives the script, so it
     *   moves behind text inserted at the offset and to the end of a replacement it lies in.
     * - [Bias.LEFT] keeps the offset behind the last character before it that survives the script, so it stays in
     *   front of text inserted at the offset and moves to the start of a replacement it lies in.
     *
     * O(log n) in the number of edits ([OffsetMapper]).
     */
    fun mapOffset(offset: Int, bias: Bias): Int = when {
        edits.isEmpty() -> offset
        bias == Bias.RIGHT -> mapper.mapRight(offset)
        else -> mapper.mapLeft(offset)
    }

    override fun toString(): String = "EditScript$edits"

    companion object {
        /** The script that changes nothing. */
        val EMPTY: EditScript = EditScript(emptyList())

        private val BY_RANGE: Comparator<TextEdit> = compareBy<TextEdit> { it.range.start }.thenBy { it.range.end }

        /**
         * Normalizes [edits] given in the coordinates of a text of length [textLength]: drops no-op edits and sorts
         * the rest (see [EditScript]).
         *
         * @throws IllegalArgumentException if an edit reaches past [textLength], two edits overlap or an insertion
         *   lies strictly inside a replaced range.
         */
        fun of(edits: List<TextEdit>, textLength: Int): EditScript {
            val effective = edits.filterNot { it.isNoOp }
            for (edit in effective) {
                require(edit.range.end <= textLength) { "edit $edit is out of bounds of a text of length $textLength" }
            }
            val sorted = effective.sortedWith(BY_RANGE)
            for (index in 1 until sorted.size) {
                val previous = sorted[index - 1]
                val next = sorted[index]
                require(next.range.start >= previous.range.end) { "edits overlap: $previous and $next" }
            }
            return if (sorted.isEmpty()) EMPTY else EditScript(sorted)
        }
    }
}
