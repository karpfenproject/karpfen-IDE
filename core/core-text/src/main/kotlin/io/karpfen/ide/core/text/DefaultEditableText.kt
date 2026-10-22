package io.karpfen.ide.core.text

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.TimeSource

/**
 * Default [EditableText]: applies edits to a `String` (karpfen files are small, so a piece table or rope would not pay
 * off), keeps a bounded edit log for [mapOffset] (published together with each snapshot as an immutable value, so
 * mapping is thread-safe while the single writer edits) and delegates history to a [DefaultUndoManager]. Every snapshot
 * it publishes carries the one [TextLineage] created with the instance. `mapRange` is the interface's own
 * implementation on top of [mapOffset].
 *
 * A facade over three collaborators: [TextVersions] (current version, edit log, mapping), [DefaultUndoManager]
 * (history) and [ChangePublisher] (flows). An [apply] commits the edits, records them in the history and only then
 * publishes the change, so observers always see a text whose history is up to date.
 */
internal class DefaultEditableText(
    initialText: String,
    timeSource: TimeSource,
    historyLimit: Int,
) : EditableText {

    private val versions = TextVersions(TextSnapshot(initialText, 0, TextLineage()), historyLimit)
    private val publisher = ChangePublisher(versions.current)
    private val history = DefaultUndoManager(PublishingTarget(versions, publisher), TypingCoalescer(timeSource))

    override val snapshot: StateFlow<TextSnapshot> get() = publisher.snapshot

    override val lineage: TextLineage = versions.current.lineage

    override val changes: SharedFlow<TextChange> get() = publisher.changes

    override val undo: UndoManager get() = history

    override fun apply(
        edits: List<TextEdit>,
        origin: EditOrigin,
        caretsBefore: List<CaretMemento>?,
        caretsAfter: List<CaretMemento>?,
    ): TextChange? {
        val change = versions.commit(edits, origin) ?: return null
        publisher.enqueue(change)
        try {
            history.record(change, caretsBefore, caretsAfter)
        } finally {
            publisher.flush()
        }
        return change
    }

    override fun replaceAll(newText: String, origin: EditOrigin): TextChange? {
        val edit = minimalReplacement(versions.current.text, newText) ?: return null
        return apply(listOf(edit), origin)
    }

    override fun mapOffset(offset: Int, fromVersion: Long, toVersion: Long, bias: Bias): Int? =
        versions.mapOffset(offset, fromVersion, toVersion, bias)

    override fun toString(): String = "EditableText(${versions.current})"

    /** The undo manager's access to the text: commits without recording and publishes through the same queue. */
    private class PublishingTarget(
        private val versions: TextVersions,
        private val publisher: ChangePublisher,
    ) : EditTarget {
        override fun commit(edits: List<TextEdit>, origin: EditOrigin): TextChange? =
            versions.commit(edits, origin)?.also(publisher::enqueue)

        override fun publishPending() = publisher.flush()
    }
}
