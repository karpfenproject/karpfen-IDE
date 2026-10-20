package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot

/**
 * The closers an editor inserted automatically (spec §7.1: typing a closer directly before an *auto-inserted* closer
 * moves over it; Backspace between an *auto-inserted* pair deletes both). Per-editor state, Compose-free, used from the
 * UI thread; the editor state creates one per editor and hands it to the actions through
 * [EditorActionContext.autoPairs]. Closers the user typed are never tracked, so they are never over-typed.
 *
 * Offsets refer to the current text version. A pair is stamped with the version it was recorded in and mapped to the
 * current version (`EditableText.mapOffset`) when queried, so the tracker needs no subscription; a pair is dropped
 * when the mapping fails or the characters at the mapped offsets are no longer the recorded opener and closer (an
 * edit touched them, an undo removed them, a reload replaced them).
 */
public interface AutoClosedPairs {

    /**
     * Records that the closing character at [closer] was inserted automatically for the opening character at [opener]
     * (offsets in the current version, `opener < closer`).
     */
    public fun record(opener: Int, closer: Int)

    /** True if the character at [offset] is a tracked auto-inserted closer. */
    public fun isAutoClosedCloser(offset: Int): Boolean

    /**
     * The tracked pair whose opener is directly left of [caret] and whose closer directly right of it (`(|)`), as the
     * range covering both characters; null otherwise.
     */
    public fun pairAround(caret: Int): TextRange?

    /** Forgets the pair whose closer is at [closer] (e.g. after it was over-typed); no-op if there is none. */
    public fun forget(closer: Int)

    /** Forgets every pair. */
    public fun clear()
}

/** The tracker of the auto-closed pairs of [text]. */
public fun AutoClosedPairs(text: EditableText): AutoClosedPairs = DefaultAutoClosedPairs(text)

/**
 * Default [AutoClosedPairs]: a short list of version-stamped pairs, re-validated and re-stamped on every query.
 *
 * A tracked character survives a change iff its one-character cell, mapped with `EditableText.mapRange` (the one
 * range-mapping rule, start `RIGHT`, end `LEFT`), still has length one and holds the recorded character: text inserted
 * next to it moves it, an edit that deleted or replaced it collapses the cell, history beyond the retained versions
 * drops it. At most [MAX_PAIRS] pairs are kept (the oldest are forgotten first).
 */
internal class DefaultAutoClosedPairs(private val text: EditableText) : AutoClosedPairs {

    /** A pair recorded in (or last re-validated at) [version]. */
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

    private companion object {
        const val MAX_PAIRS = 32
    }
}
