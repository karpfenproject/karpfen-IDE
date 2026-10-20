package io.karpfen.ide.core.text

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.TimeSource

/**
 * The mutable text of one document: applies edits atomically, publishes immutable [TextSnapshot]s, records undo
 * history and maps offsets across versions (spec §2).
 *
 * Threading: mutated from one thread only (the UI thread in the app, the test thread in tests). Snapshots and
 * changes are immutable and may be handed to any thread.
 *
 * Create instances with the [EditableText] factory function.
 */
public interface EditableText {

    /** The current snapshot. Starts at version 0 with the initial text. */
    public val snapshot: StateFlow<TextSnapshot>

    /** The lineage shared by every snapshot of this text (see [TextLineage]). */
    public val lineage: TextLineage get() = snapshot.value.lineage

    /**
     * Every applied change, exactly once and in order, emitted synchronously from [apply] (no replay; emission never
     * suspends or blocks the writer, slow collectors are buffered).
     */
    public val changes: SharedFlow<TextChange>

    /** The undo history of this text. */
    public val undo: UndoManager

    /**
     * Applies [edits] atomically and produces exactly one new version (`version + 1`).
     *
     * Edits are given in the coordinates of the current snapshot, sorted or unsorted, and must not overlap; two
     * insertions at the same offset are allowed and applied in list order. No-op edits are dropped; if nothing
     * remains (e.g. an empty list), nothing happens and null is returned.
     *
     * @param caretsBefore carets to restore when this change is undone (coordinates of the current snapshot).
     * @param caretsAfter carets to restore when this change is redone (coordinates of the new snapshot).
     * @throws IllegalArgumentException if an edit is out of bounds or edits overlap (programming error).
     */
    public fun apply(
        edits: List<TextEdit>,
        origin: EditOrigin,
        caretsBefore: List<CaretMemento>? = null,
        caretsAfter: List<CaretMemento>? = null,
    ): TextChange?

    /**
     * Replaces the whole text by [newText] as one change, expressed as the minimal edit (common prefix and suffix are
     * kept, so offsets outside the changed region map exactly). Returns null if the text is unchanged.
     */
    public fun replaceAll(newText: String, origin: EditOrigin): TextChange?

    /**
     * Maps [offset], valid in version [fromVersion], to version [toVersion] (`fromVersion <= toVersion <= current`),
     * using [bias] at edit points (see [Bias]). Returns null if [fromVersion] is older than the retained history
     * (at least the last 1000 versions are kept).
     *
     * Versions are versions of *this* text: a caller holding a range of another snapshot first checks that the
     * snapshot's [TextSnapshot.lineage] is [lineage], and clamps instead of mapping otherwise (see [TextLineage]).
     *
     * @throws IllegalArgumentException if the versions are not ordered as above or [offset] is negative.
     */
    public fun mapOffset(
        offset: Int,
        fromVersion: Long,
        toVersion: Long = snapshot.value.version,
        bias: Bias = Bias.RIGHT,
    ): Int?

    /** Constants of [EditableText]. */
    public companion object {
        /** Default number of versions kept for [mapOffset]. */
        public const val DEFAULT_HISTORY_LIMIT: Int = 1000

        /** Maximum pause between two typed characters that still coalesce into one undo step, in milliseconds. */
        public const val COALESCE_WINDOW_MS: Long = 1000
    }
}

/**
 * Creates an [EditableText] with [initialText] at version 0, a new [TextLineage] and an empty undo history.
 *
 * @param initialText the initial text; must already be normalized to `\n` separators.
 * @param timeSource clock for undo coalescing; tests pass a `kotlin.time.TestTimeSource`.
 * @param historyLimit number of versions retained for [EditableText.mapOffset] (`>= 1`).
 */
public fun EditableText(
    initialText: String = "",
    timeSource: TimeSource = TimeSource.Monotonic,
    historyLimit: Int = EditableText.DEFAULT_HISTORY_LIMIT,
): EditableText {
    require(historyLimit >= 1) { "historyLimit must be >= 1, was $historyLimit" }
    return DefaultEditableText(initialText, timeSource, historyLimit)
}
