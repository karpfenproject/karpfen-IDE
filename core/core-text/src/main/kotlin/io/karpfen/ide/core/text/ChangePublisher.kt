package io.karpfen.ide.core.text

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Publishes the committed changes of one text to its observers: the [snapshot] state and the [changes] events.
 *
 * Publication is two-phase so observers only ever see a consistent text: the writer [enqueue]s a change right after
 * committing it and [flush]es once its own bookkeeping (the undo history) is done. Collectors may run inline while a
 * flow is updated (immediate or unconfined dispatchers) and may even apply further edits from there; such reentrant
 * changes are queued behind the current one and published by the outermost flush, so snapshots and change events
 * always arrive in version order. Only the writer thread calls [enqueue] and [flush].
 */
internal class ChangePublisher(initial: TextSnapshot) {

    private val snapshotState = MutableStateFlow(initial)

    /**
     * The change events, with an unlimited buffer: `tryEmit` never fails and never suspends the writer, and slow
     * collectors are buffered.
     */
    private val changeEvents = MutableSharedFlow<TextChange>(extraBufferCapacity = Int.MAX_VALUE)

    private val pending = ArrayDeque<TextChange>()
    private var flushing = false

    /** The newest published snapshot. */
    val snapshot: StateFlow<TextSnapshot> = snapshotState.asStateFlow()

    /** Every published change, exactly once and in version order (no replay). */
    val changes: SharedFlow<TextChange> = changeEvents.asSharedFlow()

    /** Queues a committed [change]; it becomes visible with the next [flush]. */
    fun enqueue(change: TextChange) {
        pending.addLast(change)
    }

    /** Publishes every queued change in order. A reentrant call returns at once; the outer call publishes its changes. */
    fun flush() {
        if (flushing) return
        flushing = true
        try {
            while (true) {
                val change = pending.removeFirstOrNull() ?: break
                snapshotState.value = change.after
                changeEvents.tryEmit(change)
            }
        } finally {
            flushing = false
        }
    }
}
