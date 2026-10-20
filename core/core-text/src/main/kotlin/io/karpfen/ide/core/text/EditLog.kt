package io.karpfen.ide.core.text

/**
 * The bounded history of one text's changes as an immutable value: the [EditScript] of every change that produced
 * the versions `(oldestVersion, newestVersion]`. Offsets valid in any version from [oldestVersion] on can be mapped to
 * any later version up to [newestVersion].
 *
 * A log is published together with the snapshot of [newestVersion] (see [TextVersions]), so mapping is thread-safe
 * while the single writer keeps appending: the writer never changes the part of the backing array a published log
 * covers (see [EditLogWriter]).
 */
internal class EditLog(
    private val entries: Array<EditScript?>,
    private val firstIndex: Int,
    /** The oldest version offsets can be mapped from. */
    val oldestVersion: Long,
    /** The version produced by the last logged change (the version of the snapshot published with this log). */
    val newestVersion: Long,
) {

    /**
     * Maps [offset] from [fromVersion] to [toVersion] through every logged change in between, or returns null if
     * [fromVersion] is older than [oldestVersion]. Requires `fromVersion <= toVersion <= newestVersion`.
     */
    fun mapOffset(offset: Int, fromVersion: Long, toVersion: Long, bias: Bias): Int? {
        require(fromVersion <= toVersion && toVersion <= newestVersion) {
            "versions must satisfy fromVersion <= toVersion <= $newestVersion, were $fromVersion and $toVersion"
        }
        if (fromVersion < oldestVersion) return null
        var mapped = offset
        for (version in fromVersion + 1..toVersion) {
            mapped = scriptProducing(version).mapOffset(mapped, bias)
        }
        return mapped
    }

    override fun toString(): String = "EditLog(oldestVersion=$oldestVersion, newestVersion=$newestVersion)"

    private fun scriptProducing(version: Long): EditScript =
        checkNotNull(entries[firstIndex + (version - oldestVersion - 1).toInt()]) { "missing log entry for $version" }
}

/**
 * The single writer's side of the [EditLog]s of one text: appends one [EditScript] per version, keeps the newest
 * [limit] of them and hands out immutable [EditLog] views.
 *
 * The scripts live in an append-only backing array that published views share: an append writes a slot no published
 * view covers, eviction only moves the start of the next view, and when the array is full the retained entries are
 * copied into a new array (the old one stays untouched for the views that still use it). Appending is amortized O(1)
 * and at most `2 * limit` scripts are referenced by the newest view's array. Not thread-safe; only the writer calls it.
 */
internal class EditLogWriter(private val limit: Int, baseVersion: Long) {

    init {
        require(limit >= 1) { "limit must be >= 1, was $limit" }
    }

    private var entries: Array<EditScript?> = arrayOfNulls(minOf(INITIAL_CAPACITY, maxCapacity()))
    private var firstIndex = 0
    private var size = 0
    private var oldestVersion = baseVersion

    /** The log of everything appended so far (empty before the first append). */
    var log: EditLog = view()
        private set

    /** Logs [script] as the change producing the next version and returns the log including it. */
    fun append(script: EditScript): EditLog {
        if (firstIndex + size == entries.size) moveToNewArray()
        entries[firstIndex + size] = script
        size++
        if (size > limit) {
            firstIndex++
            size--
            oldestVersion++
        }
        log = view()
        return log
    }

    private fun view(): EditLog = EditLog(entries, firstIndex, oldestVersion, oldestVersion + size)

    /** Copies the retained entries to the front of a fresh array with room to append (old views keep the old one). */
    private fun moveToNewArray() {
        val capacity = maxOf(size + 1, minOf(maxOf(2 * size, INITIAL_CAPACITY), maxCapacity()))
        val fresh = arrayOfNulls<EditScript>(capacity)
        System.arraycopy(entries, firstIndex, fresh, 0, size)
        entries = fresh
        firstIndex = 0
    }

    /** Twice the limit (room for `limit` appends between copies), without overflowing for huge limits. */
    private fun maxCapacity(): Int = minOf(2L * limit, MAX_ARRAY_SIZE.toLong()).toInt()

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val MAX_ARRAY_SIZE = Int.MAX_VALUE - 8
    }
}
