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

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.TimeSource

/**
 * The mutable text of one document: applies edits atomically, publishes immutable [TextSnapshot]s, records undo
 * history and maps offsets across versions.
 *
 * The text is mutated from one thread only. That is the UI thread in the app and the test thread in tests. Snapshots
 * and changes are immutable and may be handed to any thread. Reading [snapshot] and mapping with [mapOffset] or
 * [mapRange] are thread-safe.
 *
 * Create instances with the [EditableText] factory function.
 */
public interface EditableText {

    /** The current snapshot. Starts at version 0 with the initial text. */
    public val snapshot: StateFlow<TextSnapshot>

    /** The lineage shared by every snapshot of this text. See [TextLineage]. */
    public val lineage: TextLineage get() = snapshot.value.lineage

    /**
     * Every applied change, exactly once and in order, emitted synchronously from [apply]. There is no replay.
     * Emission never suspends or blocks the writer, and slow collectors are buffered.
     */
    public val changes: SharedFlow<TextChange>

    /** The undo history of this text. */
    public val undo: UndoManager

    /**
     * Applies [edits] atomically and produces exactly one new version (`version + 1`).
     *
     * Edits are given in the coordinates of the current snapshot, sorted or unsorted, and must not overlap. Two
     * insertions at the same offset are allowed and are applied in list order. No-op edits are dropped. If nothing
     * remains, for example for an empty list, nothing happens and the function returns null.
     *
     * @param caretsBefore carets to restore when this change is undone, in the coordinates of the current snapshot.
     * @param caretsAfter carets to restore when this change is redone, in the coordinates of the new snapshot.
     * @throws IllegalArgumentException if an edit is out of bounds or edits overlap. Both are programming errors.
     */
    public fun apply(
        edits: List<TextEdit>,
        origin: EditOrigin,
        caretsBefore: List<CaretMemento>? = null,
        caretsAfter: List<CaretMemento>? = null,
    ): TextChange?

    /**
     * Replaces the whole text by [newText] as one change. The change is expressed as the minimal edit. The common
     * prefix and suffix are kept, so offsets outside the changed region map exactly. Returns null if the text is
     * unchanged.
     */
    public fun replaceAll(newText: String, origin: EditOrigin): TextChange?

    /**
     * Maps [offset], valid in version [fromVersion], to version [toVersion]. The versions must satisfy
     * `fromVersion <= toVersion <= current`. At edit points [bias] decides, as described at [Bias]. Returns null if
     * [fromVersion] is older than the retained history. The history holds the last `historyLimit` versions, 1000 by
     * default.
     *
     * This is a point mapping within this text's own versions, used for carets and tracked pairs. Ranges are mapped
     * with [mapRange] only, never with two calls of this function, because the one rule for bias and collapse lives
     * there. That holds for diagnostics, highlight spans, navigation targets and anything else that carries a version
     * and a lineage.
     *
     * Unlike mutation, this function is thread-safe and may be called from any thread. It reads the history as it was
     * published together with the snapshots, so a background pipeline such as diagnostics merging or highlighting may
     * map while the writer edits.
     *
     * @throws IllegalArgumentException if the versions are not ordered as above or [offset] is negative.
     */
    public fun mapOffset(
        offset: Int,
        fromVersion: Long,
        toVersion: Long = snapshot.value.version,
        bias: Bias = Bias.RIGHT,
    ): Int?

    /**
     * Maps [range], valid in version [fromVersion] of the text with lineage [fromLineage], to version [toVersion] of
     * this text. Never throws.
     *
     * This is the one range-mapping rule of the IDE. The session's diagnostics merger, the editor's decorations and
     * highlight spans, and navigation all map ranges through it. Nobody maps the two bounds of a range separately, so
     * every consumer agrees on where a range went.
     *
     * Returns null in these cases: [fromLineage] is not this text's [lineage], [fromVersion] is negative or newer than
     * [toVersion], [toVersion] is newer than the current version, or [fromVersion] is older than the retained history.
     * A foreign lineage means that the range belongs to another text, for example to a document that was closed and
     * reopened since. [TextLineage] explains this. On null the caller drops the range, or clamps it when it must
     * navigate somewhere.
     *
     * Decorations shrink rather than grow. A non-empty range maps its start with [Bias.RIGHT] and its end with
     * [Bias.LEFT], so text inserted at either bound stays outside. If the mapped start lies after the mapped end, the
     * range sat inside a replaced or deleted region, and the result is the empty range at the mapped end. An empty
     * range stays empty and maps as one offset with [Bias.RIGHT], so a "missing `}`" marker stays behind typed text. A
     * range that reaches past the text of [fromVersion] is the caller's error. Its bounds are mapped like offsets after
     * every edit.
     *
     * The function is thread-safe like [mapOffset], because the diagnostics merger and the editor's highlight pipeline
     * map on background dispatchers while the writer edits. It is implemented here once on top of [mapOffset], and
     * implementations do not override it.
     */
    public fun mapRange(
        range: TextRange,
        fromVersion: Long,
        fromLineage: TextLineage,
        toVersion: Long = snapshot.value.version,
    ): TextRange? {
        if (fromLineage !== lineage || fromVersion < 0 || fromVersion > toVersion || toVersion > snapshot.value.version) {
            return null
        }
        if (range.isEmpty) {
            return mapOffset(range.start, fromVersion, toVersion, Bias.RIGHT)?.let(TextRange::empty)
        }
        val start = mapOffset(range.start, fromVersion, toVersion, Bias.RIGHT) ?: return null
        val end = mapOffset(range.end, fromVersion, toVersion, Bias.LEFT) ?: return null
        return if (start <= end) TextRange(start, end) else TextRange.empty(end)
    }

    /** [mapRange] for a [range] that is valid in the snapshot [from], with its version and lineage. Never throws. */
    public fun mapRange(range: TextRange, from: TextSnapshot, toVersion: Long = snapshot.value.version): TextRange? =
        mapRange(range, from.version, from.lineage, toVersion)

    /** Constants of [EditableText]. */
    public companion object {
        /** Default number of versions kept for [mapOffset] and [mapRange]. */
        public const val DEFAULT_HISTORY_LIMIT: Int = 1000

        /** Maximum pause between two typed characters that still coalesce into one undo step, in milliseconds. */
        public const val COALESCE_WINDOW_MS: Long = 1000
    }
}

/**
 * Creates an [EditableText] with [initialText] at version 0, a new [TextLineage] and an empty undo history.
 *
 * @param initialText the initial text. It must already be normalized to `\n` separators.
 * @param timeSource clock for undo coalescing. Tests pass a `kotlin.time.TestTimeSource`.
 * @param historyLimit number of versions retained for [EditableText.mapOffset] and [EditableText.mapRange]. It must be
 *   at least 1.
 */
public fun EditableText(
    initialText: String = "",
    timeSource: TimeSource = TimeSource.Monotonic,
    historyLimit: Int = EditableText.DEFAULT_HISTORY_LIMIT,
): EditableText {
    require(historyLimit >= 1) { "historyLimit must be >= 1, was $historyLimit" }
    return DefaultEditableText(initialText, timeSource, historyLimit)
}
