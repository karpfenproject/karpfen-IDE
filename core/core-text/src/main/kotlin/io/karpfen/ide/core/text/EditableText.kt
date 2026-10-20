package io.karpfen.ide.core.text

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.TimeSource

/**
 * The mutable text of one document: applies edits atomically, publishes immutable [TextSnapshot]s, records undo
 * history and maps offsets across versions (spec §2).
 *
 * Threading: mutated from one thread only (the UI thread in the app, the test thread in tests). Snapshots and
 * changes are immutable and may be handed to any thread; reading [snapshot] and mapping ([mapOffset], [mapRange]) are
 * thread-safe.
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
     * A point mapping within this text's own versions (carets, tracked pairs). Ranges — diagnostics, highlight spans,
     * navigation targets, anything carrying a version and a lineage — are mapped with [mapRange] only, never with two
     * calls of this function (the one bias and collapse rule lives there).
     *
     * Thread-safe (unlike mutation): may be called from any thread; it reads the history as published together with
     * the snapshots, so a background pipeline (diagnostics merging, highlighting) may map while the writer edits.
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
     * this text: the one range-mapping rule of the IDE (decision 35 of the work-package plan). Never throws.
     *
     * Returns null — the caller drops the range, or clamps it when it must navigate somewhere — if [fromLineage] is not
     * this text's [lineage] (a range of another text, e.g. of a document closed and reopened since, see [TextLineage]),
     * if [fromVersion] is newer than [toVersion], if [toVersion] is newer than the current version, or if [fromVersion]
     * is older than the retained history.
     *
     * Bias rule (decorations shrink rather than grow): a non-empty range maps its start with [Bias.RIGHT] and its end
     * with [Bias.LEFT], so text inserted at either bound stays outside; if the mapped start lies after the mapped end
     * (the range sat inside a replaced or deleted region), the result is the empty range at the mapped end. An empty
     * range stays empty and maps as one offset with [Bias.RIGHT] (a "missing `}`" marker stays behind typed text).
     * A range reaching past the text of [fromVersion] is the caller's error; its bounds are mapped like offsets after
     * every edit.
     *
     * Thread-safe like [mapOffset]. Implemented here once on top of [mapOffset].
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

    /** [mapRange] for a [range] valid in [from] (its version and lineage). Never throws. */
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
 * @param initialText the initial text; must already be normalized to `\n` separators.
 * @param timeSource clock for undo coalescing; tests pass a `kotlin.time.TestTimeSource`.
 * @param historyLimit number of versions retained for [EditableText.mapOffset] and [EditableText.mapRange] (`>= 1`).
 */
public fun EditableText(
    initialText: String = "",
    timeSource: TimeSource = TimeSource.Monotonic,
    historyLimit: Int = EditableText.DEFAULT_HISTORY_LIMIT,
): EditableText {
    require(historyLimit >= 1) { "historyLimit must be >= 1, was $historyLimit" }
    return DefaultEditableText(initialText, timeSource, historyLimit)
}
