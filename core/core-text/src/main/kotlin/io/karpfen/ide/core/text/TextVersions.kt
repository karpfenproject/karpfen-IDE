package io.karpfen.ide.core.text

/**
 * The versions of one text: the current [TextSnapshot] and the bounded [EditLog] that maps offsets from older
 * versions to newer ones.
 *
 * [commit] is called by the single writer only. The current snapshot and its log are published together as one
 * immutable [Published] value through a volatile field, so [current] and [mapOffset] are thread-safe and every
 * mapping reads one consistent version of the history.
 */
internal class TextVersions(initial: TextSnapshot, historyLimit: Int) {

    /** A snapshot together with the log of the changes leading to it (`log.newestVersion == snapshot.version`). */
    private class Published(val snapshot: TextSnapshot, val log: EditLog)

    private val logWriter = EditLogWriter(historyLimit, initial.version)

    @Volatile
    private var published = Published(initial, logWriter.log)

    /** The newest committed snapshot. */
    val current: TextSnapshot get() = published.snapshot

    /**
     * Applies [edits] (coordinates of [current]) atomically as the next version and returns the change, or null if
     * nothing remains after dropping no-op edits. Nothing is changed if the edits are invalid.
     *
     * @throws IllegalArgumentException if an edit is out of bounds or edits overlap (see [EditScript.of]).
     */
    fun commit(edits: List<TextEdit>, origin: EditOrigin): TextChange? {
        val before = published.snapshot
        val script = EditScript.of(edits, before.length)
        if (script.isEmpty) return null
        val after = TextSnapshot(script.applyTo(before.text), before.version + 1, before.lineage)
        published = Published(after, logWriter.append(script))
        return TextChange(before, after, script.edits, origin)
    }

    /**
     * Maps [offset] from [fromVersion] to [toVersion] (see [EditableText.mapOffset]); null if [fromVersion] is older
     * than the retained history (a negative version is older than every history).
     *
     * @throws IllegalArgumentException if [offset] is negative or the versions are not ordered
     *   `fromVersion <= toVersion <= current version`.
     */
    fun mapOffset(offset: Int, fromVersion: Long, toVersion: Long, bias: Bias): Int? {
        require(offset >= 0) { "offset must be >= 0, was $offset" }
        return published.log.mapOffset(offset, fromVersion, toVersion, bias)
    }
}
