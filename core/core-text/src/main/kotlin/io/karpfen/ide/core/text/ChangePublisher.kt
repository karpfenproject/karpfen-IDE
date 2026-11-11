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

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Publishes the committed changes of one text to its observers: the [snapshot] state and the [changes] events.
 *
 * Publication happens in two steps, so observers only ever see a consistent text. The writer calls [enqueue] right
 * after it commits a change, and it calls [flush] once its own bookkeeping, the undo history, is done.
 *
 * Collectors may run inline while a flow is updated, for example on an immediate or unconfined dispatcher. They may
 * even apply further edits from there. Such reentrant changes are queued behind the current one and published by the
 * outermost flush, so snapshots and change events always arrive in version order.
 *
 * Only the writer thread calls [enqueue] and [flush].
 */
internal class ChangePublisher(initial: TextSnapshot) {

    private val snapshotState = MutableStateFlow(initial)

    /**
     * The change events. The buffer is unlimited, so `tryEmit` never fails and never suspends the writer, and slow
     * collectors are buffered.
     */
    private val changeEvents = MutableSharedFlow<TextChange>(extraBufferCapacity = Int.MAX_VALUE)

    private val pending = ArrayDeque<TextChange>()
    private var flushing = false

    /** The newest published snapshot. */
    val snapshot: StateFlow<TextSnapshot> = snapshotState.asStateFlow()

    /** Every published change, exactly once and in version order. There is no replay. */
    val changes: SharedFlow<TextChange> = changeEvents.asSharedFlow()

    /** Queues a committed [change]. It becomes visible with the next [flush]. */
    fun enqueue(change: TextChange) {
        pending.addLast(change)
    }

    /**
     * Publishes every queued change in order. A reentrant call returns at once, and the outer call publishes its
     * changes.
     */
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
