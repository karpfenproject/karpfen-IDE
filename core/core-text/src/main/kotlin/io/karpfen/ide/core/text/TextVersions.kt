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
 * The versions of one text. It holds the current [TextSnapshot] and the bounded [EditLog] that maps offsets from older
 * versions to newer ones.
 *
 * Only the single writer calls [commit]. The current snapshot and its log are published together as one immutable
 * [Published] value through a volatile field. [current] and [mapOffset] are therefore thread-safe, and every mapping
 * reads one consistent version of the history.
 */
internal class TextVersions(initial: TextSnapshot, historyLimit: Int) {

    /** A snapshot together with the log of the changes that led to it, so `log.newestVersion == snapshot.version`. */
    private class Published(val snapshot: TextSnapshot, val log: EditLog)

    private val logWriter = EditLogWriter(historyLimit, initial.version)

    @Volatile
    private var published = Published(initial, logWriter.log)

    /** The newest committed snapshot. */
    val current: TextSnapshot get() = published.snapshot

    /**
     * Applies [edits], given in the coordinates of [current], atomically as the next version and returns the change.
     * Returns null if nothing remains after the no-op edits are dropped. Nothing changes if the edits are invalid.
     *
     * @throws IllegalArgumentException if an edit is out of bounds or edits overlap, as checked by [EditScript.of].
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
     * Maps [offset] from [fromVersion] to [toVersion] as described at [EditableText.mapOffset]. Returns null if
     * [fromVersion] is older than the retained history. A negative version is older than every history.
     *
     * @throws IllegalArgumentException if [offset] is negative or the versions are not ordered
     *   `fromVersion <= toVersion <= current version`.
     */
    fun mapOffset(offset: Int, fromVersion: Long, toVersion: Long, bias: Bias): Int? {
        require(offset >= 0) { "offset must be >= 0, was $offset" }
        return published.log.mapOffset(offset, fromVersion, toVersion, bias)
    }
}
