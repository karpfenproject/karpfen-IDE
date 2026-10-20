package io.karpfen.ide.core.text

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.TimeSource

/**
 * Default [EditableText]: applies edits to a `String` (files are small, spec §1), keeps a bounded edit log for
 * [mapOffset] (published together with each snapshot as an immutable value, so mapping is thread-safe while the single
 * writer edits) and delegates history to a [DefaultUndoManager]. Every snapshot it publishes carries the one
 * [TextLineage] created with the instance. `mapRange` is the interface's own implementation on top of [mapOffset].
 */
internal class DefaultEditableText(
    private val initialText: String,
    private val timeSource: TimeSource,
    private val historyLimit: Int,
) : EditableText {

    override val snapshot: StateFlow<TextSnapshot>
        get() = TODO("WP-TEXT: snapshot flow starting at version 0")

    override val changes: SharedFlow<TextChange>
        get() = TODO("WP-TEXT: change flow")

    override val undo: UndoManager
        get() = TODO("WP-TEXT: undo manager")

    override fun apply(
        edits: List<TextEdit>,
        origin: EditOrigin,
        caretsBefore: List<CaretMemento>?,
        caretsAfter: List<CaretMemento>?,
    ): TextChange? = TODO("WP-TEXT: atomic apply")

    override fun replaceAll(newText: String, origin: EditOrigin): TextChange? = TODO("WP-TEXT: minimal replace-all")

    override fun mapOffset(offset: Int, fromVersion: Long, toVersion: Long, bias: Bias): Int? =
        TODO("WP-TEXT: map offset through the bounded edit log")
}
