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
package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot

/**
 * Tracks the closing characters that an editor inserted automatically. Typing a closer directly before an
 * auto-inserted closer moves the caret over it. Backspace between an auto-inserted pair deletes both characters.
 * Closers the user typed are never tracked, so they are never typed over.
 *
 * This is per-editor state without Compose, used from the UI thread. The editor state creates one tracker per editor
 * and hands it to the actions through [EditorActionContext.autoPairs].
 *
 * Offsets refer to the current text version. A pair is stamped with the version it was recorded in. When it is
 * queried, it is mapped to the current version, so the tracker needs no subscription to the text. A pair is dropped
 * when the mapping fails, or when the characters at the mapped offsets are no longer the recorded opener and closer.
 * That happens when an edit touched them, an undo removed them or a reload replaced them.
 */
public interface AutoClosedPairs {

    /**
     * Records that the closing character at [closer] was inserted automatically for the opening character at [opener].
     * Both are offsets of the current version, and [opener] must be less than [closer].
     */
    public fun record(opener: Int, closer: Int)

    /** True if the character at [offset] is a tracked auto-inserted closer. */
    public fun isAutoClosedCloser(offset: Int): Boolean

    /**
     * Returns the tracked pair whose opener is directly left of [caret] and whose closer is directly right of it, as in
     * `(|)`. The result is the range that covers both characters, or null if there is no such pair.
     */
    public fun pairAround(caret: Int): TextRange?

    /** Forgets the pair whose closer is at [closer], for example after it was typed over. Does nothing if there is none. */
    public fun forget(closer: Int)

    /** Forgets every pair. */
    public fun clear()
}

/** The tracker of the auto-closed pairs of [text]. */
public fun AutoClosedPairs(text: EditableText): AutoClosedPairs = DefaultAutoClosedPairs(text)

/**
 * The default [AutoClosedPairs]. It keeps a short list of version-stamped pairs and checks and re-stamps them on every
 * query.
 *
 * A tracked character survives a change only if its one-character cell, mapped with `EditableText.mapRange`, still has
 * length one and still holds the recorded character. `mapRange` is the single range-mapping rule of the text, with
 * start bias `RIGHT` and end bias `LEFT`. So text inserted next to the character moves it, and an edit that deleted or
 * replaced it collapses the cell. A pair recorded in a version older than the retained history is dropped. At most
 * [MAX_PAIRS] pairs are kept, and the oldest are forgotten first.
 */
internal class DefaultAutoClosedPairs(private val text: EditableText) : AutoClosedPairs {

    /** A pair that was recorded or last checked at [version], with the characters [open] and [close] it expects. */
    private data class TrackedPair(val opener: Int, val closer: Int, val version: Long, val open: Char, val close: Char)

    private var pairs: List<TrackedPair> = emptyList()

    override fun record(opener: Int, closer: Int) {
        val snapshot = text.snapshot.value
        require(opener in 0 until closer && closer < snapshot.length) {
            "a pair needs 0 <= opener < closer < length, was opener=$opener closer=$closer length=${snapshot.length}"
        }
        val pair = TrackedPair(opener, closer, snapshot.version, snapshot[opener], snapshot[closer])
        pairs = (live().filter { it.closer != closer } + pair).takeLast(MAX_PAIRS)
    }

    override fun isAutoClosedCloser(offset: Int): Boolean = live().any { it.closer == offset }

    override fun pairAround(caret: Int): TextRange? =
        live().lastOrNull { it.opener + 1 == caret && it.closer == caret }?.let { TextRange(it.opener, it.closer + 1) }

    override fun forget(closer: Int) {
        pairs = live().filterNot { it.closer == closer }
    }

    override fun clear() {
        pairs = emptyList()
    }

    /** The pairs that survived every change since they were recorded, stamped with the current version. */
    private fun live(): List<TrackedPair> {
        val snapshot = text.snapshot.value
        if (pairs.all { it.version == snapshot.version }) return pairs
        pairs = pairs.mapNotNull { pair ->
            val opener = cellOf(pair.opener, pair.version, snapshot, pair.open)
            val closer = cellOf(pair.closer, pair.version, snapshot, pair.close)
            if (opener != null && closer != null && opener < closer) {
                pair.copy(opener = opener, closer = closer, version = snapshot.version)
            } else {
                null
            }
        }
        return pairs
    }

    /** Where the character [char] recorded at [offset] of [version] is in [snapshot], or null if it did not survive. */
    private fun cellOf(offset: Int, version: Long, snapshot: TextSnapshot, char: Char): Int? {
        val cell = text.mapRange(TextRange(offset, offset + 1), version, text.lineage, snapshot.version) ?: return null
        return cell.start.takeIf { cell.length == 1 && snapshot[it] == char }
    }

    /** The bound of the tracker. */
    private companion object {
        /** The most pairs kept. A long run of auto-closed pairs forgets its oldest ones. */
        const val MAX_PAIRS = 32
    }
}
