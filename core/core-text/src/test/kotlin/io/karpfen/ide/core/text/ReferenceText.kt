package io.karpfen.ide.core.text

/**
 * Test oracle for text edits, written independently of the production algorithms: a `StringBuilder` whose edits are
 * applied one by one from the end of the text to its start, so earlier offsets stay valid, and a parallel list of
 * character identities that defines offset mapping by which characters survive an edit.
 *
 * Mapping rule of the oracle (the meaning of [Bias]): [Bias.RIGHT] keeps an offset in front of the first character at
 * or after it that survives the change, [Bias.LEFT] keeps it behind the last character before it that survives.
 */
class ReferenceText(initial: String) {

    private val builder = StringBuilder(initial)
    private var identities = IntArray(initial.length) { it }
    private var nextIdentity = initial.length
    private val trackers = ArrayList<Tracker>()

    /** The current text. */
    val text: String get() = builder.toString()

    /** A point mapping followed through every change since [fromVersion]. */
    class Tracker(val fromVersion: Long, val original: Int, val bias: Bias) {
        /** The offset mapped to the current version. */
        var offset: Int = original
            internal set
    }

    /** Starts following [offset] of the current text (valid in [version]) with [bias]. */
    fun track(version: Long, offset: Int, bias: Bias): Tracker = Tracker(version, offset, bias).also { trackers += it }

    /** Stops following trackers that [predicate] selects. */
    fun untrack(predicate: (Tracker) -> Boolean) {
        trackers.removeAll(predicate)
    }

    /** The trackers followed so far. */
    val tracked: List<Tracker> get() = trackers

    /**
     * Applies [edits] given in the coordinates of the current text as one change: insertions at the same offset in
     * list order, an insertion at the start of a replaced range before the replacement.
     */
    fun apply(edits: List<TextEdit>) {
        val order = edits.indices.sortedWith(
            compareByDescending<Int> { edits[it].range.start }.thenByDescending { edits[it].range.end }.thenByDescending { it },
        )
        val before = identities
        val newIdentities = identities.toMutableList()
        for (index in order) {
            val edit = edits[index]
            builder.replace(edit.range.start, edit.range.end, edit.newText)
            repeat(edit.range.length) { newIdentities.removeAt(edit.range.start) }
            newIdentities.addAll(edit.range.start, List(edit.newText.length) { nextIdentity++ })
        }
        identities = newIdentities.toIntArray()
        if (trackers.isNotEmpty()) {
            val positions = HashMap<Int, Int>(identities.size * 2)
            identities.forEachIndexed { index, identity -> positions[identity] = index }
            for (tracker in trackers) tracker.offset = mapByIdentity(before, positions, identities.size, tracker.offset, tracker.bias)
        }
    }

    /** Replaces the whole text by [newText] with one edit covering everything (only used where mapping is not checked). */
    fun replaceAll(newText: String) {
        if (newText != text) apply(listOf(TextEdit(TextRange(0, builder.length), newText)))
    }

    private fun mapByIdentity(before: IntArray, positions: Map<Int, Int>, newLength: Int, offset: Int, bias: Bias): Int =
        when (bias) {
            Bias.RIGHT -> (offset until before.size).firstNotNullOfOrNull { positions[before[it]] } ?: newLength
            Bias.LEFT -> (offset - 1 downTo 0).firstNotNullOfOrNull { positions[before[it]] }?.plus(1) ?: 0
        }

    companion object {
        /** [edits] applied to [text] by the oracle. */
        fun applied(text: String, edits: List<TextEdit>): String = ReferenceText(text).apply { apply(edits) }.text
    }
}
