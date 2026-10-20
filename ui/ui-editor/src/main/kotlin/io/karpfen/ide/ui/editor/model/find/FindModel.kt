package io.karpfen.ide.ui.editor.model.find

import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.action.EditorActionExecutor
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** What to search for (spec §7.1). An empty [text] matches nothing. */
public data class FindQuery(
    public val text: String = "",
    public val caseSensitive: Boolean = false,
    public val wholeWord: Boolean = false,
    public val regex: Boolean = false,
)

/**
 * The find state for one text version.
 *
 * @property matches non-overlapping matches in document order (empty matches of a regex are skipped).
 * @property currentIndex index of the current match in [matches], or null if there is none.
 * @property error message of an invalid regular expression (then [matches] is empty), else null.
 */
public data class FindResult(
    public val query: FindQuery,
    public val version: Long,
    public val matches: List<TextRange>,
    public val currentIndex: Int?,
    public val error: String?,
) {
    init {
        require(currentIndex == null || currentIndex in matches.indices) { "currentIndex $currentIndex out of range" }
    }

    /** The current match, or null. */
    public val current: TextRange? get() = currentIndex?.let { matches[it] }

    /** One-based number of the current match for the "n/m" counter (0 if none). */
    public val currentNumber: Int get() = currentIndex?.plus(1) ?: 0

    /** Number of matches. */
    public val total: Int get() = matches.size
}

/**
 * Find and replace in one editor (spec §7.1). Compose-free state holder; matches follow the text: recomputed for every
 * new version and query on the factory's background dispatcher (latest wins) and published in its scope. Invalid
 * regular expressions put the model into an error state; nothing throws. [next], [previous] and the replace functions
 * act on the matches of the current text: when the published state lags behind (right after an edit), they compute
 * the matches of the current snapshot synchronously first. Replacing runs as an `EditorAction` through the factory's
 * [EditorActionExecutor] (origin `Command`, caret mementos, one undo step), like every other edit.
 */
public interface FindModel {

    /** The current query, matches and selection of the current match. */
    public val state: StateFlow<FindResult>

    /** Replaces the query; the current match becomes the first match at or after the primary caret. */
    public fun setQuery(query: FindQuery)

    /** Moves to the next match (wrapping) and selects it. */
    public fun next()

    /** Moves to the previous match (wrapping) and selects it. */
    public fun previous()

    /** Replaces the current match by [replacement] (with `$1` group references in regex mode) and moves to the next. */
    public fun replaceCurrent(replacement: String)

    /** Replaces every match as one undo step; returns the number of replacements. */
    public fun replaceAll(replacement: String): Int
}

/**
 * A [FindModel] over [text] that selects matches through [caret] and replaces through [executor]; the state is published
 * in [scope] (the editor's UI scope), matches are computed on [backgroundDispatcher] (the editor state passes its own;
 * tests a test dispatcher).
 */
public fun FindModel(
    text: EditableText,
    caret: CaretModel,
    executor: EditorActionExecutor,
    scope: CoroutineScope,
    backgroundDispatcher: CoroutineDispatcher,
): FindModel = DefaultFindModel(text, caret, executor, scope, backgroundDispatcher)

internal class DefaultFindModel(
    private val text: EditableText,
    private val caret: CaretModel,
    private val executor: EditorActionExecutor,
    private val scope: CoroutineScope,
    private val backgroundDispatcher: CoroutineDispatcher,
) : FindModel {
    override val state: StateFlow<FindResult>
        get() = TODO("WP-EDITOR-MODEL: find state")

    override fun setQuery(query: FindQuery): Unit = TODO("WP-EDITOR-MODEL: set query")

    override fun next(): Unit = TODO("WP-EDITOR-MODEL: next match")

    override fun previous(): Unit = TODO("WP-EDITOR-MODEL: previous match")

    override fun replaceCurrent(replacement: String): Unit = TODO("WP-EDITOR-MODEL: replace current")

    override fun replaceAll(replacement: String): Int = TODO("WP-EDITOR-MODEL: replace all")
}
