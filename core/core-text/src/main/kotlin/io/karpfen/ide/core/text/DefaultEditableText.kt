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
 * Default [EditableText]. It applies edits to a `String`. Karpfen files are small, so a piece table or a rope would not
 * pay off. It keeps a bounded edit log for [mapOffset]. The log is published together with each snapshot as an
 * immutable value, so mapping is thread-safe while the single writer edits. The history is delegated to a
 * [DefaultUndoManager]. Every snapshot it publishes carries the one [TextLineage] created with the instance.
 * `mapRange` is the interface's own implementation on top of [mapOffset].
 *
 * The class is a facade over three collaborators. [TextVersions] holds the current version and the edit log and does
 * the mapping. [DefaultUndoManager] keeps the history. [ChangePublisher] feeds the flows. An [apply] commits the edits,
 * records them in the history and only then publishes the change, so observers always see a text whose history is up
 * to date.
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

    /**
     * The undo manager's access to the text. It commits without recording history and publishes through the same queue
     * as [apply].
     */
    private class PublishingTarget(
        private val versions: TextVersions,
        private val publisher: ChangePublisher,
    ) : EditTarget {
        override fun commit(edits: List<TextEdit>, origin: EditOrigin): TextChange? =
            versions.commit(edits, origin)?.also(publisher::enqueue)

        override fun publishPending() = publisher.flush()
    }
}
