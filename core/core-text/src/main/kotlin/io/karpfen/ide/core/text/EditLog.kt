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
package io.karpfen.ide.core.text

/**
 * The bounded history of one text's changes as an immutable value. It holds the [EditScript] of every change that
 * produced the versions `(oldestVersion, newestVersion]`. Offsets valid in any version from [oldestVersion] on can be
 * mapped to any later version up to [newestVersion].
 *
 * A log is published together with the snapshot of [newestVersion] by [TextVersions]. Mapping is thread-safe while the
 * single writer keeps appending, because the writer never changes the part of the backing array that a published log
 * covers. [EditLogWriter] describes how it achieves that.
 */
internal class EditLog(
    private val entries: Array<EditScript?>,
    private val firstIndex: Int,
    /** The oldest version offsets can be mapped from. */
    val oldestVersion: Long,
    /** The version produced by the last logged change. It is the version of the snapshot published with this log. */
    val newestVersion: Long,
) {

    /**
     * Maps [offset] from [fromVersion] to [toVersion] through every logged change in between. Returns null if
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
 * The single writer's side of the [EditLog]s of one text. It appends one [EditScript] per version, keeps the newest
 * [limit] of them and hands out immutable [EditLog] views.
 *
 * The scripts live in an append-only backing array that the published views share. An append writes a slot that no
 * published view covers. Eviction only moves the start of the next view. When the array is full, the retained entries
 * are copied into a new array, and the old array stays untouched for the views that still use it. Appending is
 * amortized O(1), and the newest view's array references at most `2 * limit` scripts.
 *
 * The class is not thread-safe. Only the writer calls it.
 */
internal class EditLogWriter(private val limit: Int, baseVersion: Long) {

    init {
        require(limit >= 1) { "limit must be >= 1, was $limit" }
    }

    private var entries: Array<EditScript?> = arrayOfNulls(minOf(INITIAL_CAPACITY, maxCapacity()))
    private var firstIndex = 0
    private var size = 0
    private var oldestVersion = baseVersion

    /** The log of everything appended so far. It is empty before the first append. */
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

    /**
     * Copies the retained entries to the front of a fresh array with room to append. Old views keep using the old
     * array.
     */
    private fun moveToNewArray() {
        val capacity = maxOf(size + 1, minOf(maxOf(2 * size, INITIAL_CAPACITY), maxCapacity()))
        val fresh = arrayOfNulls<EditScript>(capacity)
        System.arraycopy(entries, firstIndex, fresh, 0, size)
        entries = fresh
        firstIndex = 0
    }

    /**
     * Twice the limit, which leaves room for `limit` appends between two copies. The computation does not overflow for
     * huge limits.
     */
    private fun maxCapacity(): Int = minOf(2L * limit, MAX_ARRAY_SIZE.toLong()).toInt()

    private companion object {
        /** The size of the first backing array. For small limits the first array has twice the limit instead. */
        const val INITIAL_CAPACITY = 16

        /** The largest array size that every JVM can allocate. Some JVMs reserve header words in the array. */
        const val MAX_ARRAY_SIZE = Int.MAX_VALUE - 8
    }
}
