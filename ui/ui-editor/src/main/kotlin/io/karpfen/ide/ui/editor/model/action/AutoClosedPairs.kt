package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextRange

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

internal class DefaultAutoClosedPairs(private val text: EditableText) : AutoClosedPairs {
    override fun record(opener: Int, closer: Int): Unit = TODO("WP-EDITOR-MODEL: record a version-stamped pair")

    override fun isAutoClosedCloser(offset: Int): Boolean = TODO("WP-EDITOR-MODEL: mapped closer lookup")

    override fun pairAround(caret: Int): TextRange? = TODO("WP-EDITOR-MODEL: mapped pair lookup")

    override fun forget(closer: Int): Unit = TODO("WP-EDITOR-MODEL: forget a pair")

    override fun clear(): Unit = TODO("WP-EDITOR-MODEL: forget all pairs")
}
