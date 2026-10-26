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
package io.karpfen.ide.ui.editor.model.find

import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.action.EditorActionExecutor
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * What to search for. An empty [text] matches nothing.
 *
 * @property text the searched text, or the regular expression when [regex] is set.
 * @property caseSensitive whether letter case must match (otherwise Unicode case-insensitive).
 * @property wholeWord whether a match must not be preceded or followed by an identifier character.
 * @property regex whether [text] is a regular expression (multi-line mode: `^` and `$` match at line bounds).
 */
public data class FindQuery(
    public val text: String = "",
    public val caseSensitive: Boolean = false,
    public val wholeWord: Boolean = false,
    public val regex: Boolean = false,
)

/**
 * The find state for one text version.
 *
 * @property query the query these matches belong to (it lags behind [FindModel.query] while they are computed).
 * @property version the text version the matches were computed for.
 * @property matches non-overlapping matches in document order (empty matches of a regex are skipped).
 * @property currentIndex index of the current match in [matches], or null if there is none.
 * @property error message of an invalid regular expression or of a search that took too long (then [matches] is empty),
 *   else null.
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
 * Find and replace in one editor. Compose-free state holder used from the UI thread. [query] is the query the model
 * searches for, set synchronously by [setQuery]; the matches follow the text and the query: computed for every new
 * version and query on the factory's background dispatcher (latest wins, cancellable, with a time budget — a regular
 * expression that takes too long ends in an error state) and published as [state] in its scope. Invalid regular
 * expressions put the model into an error state; nothing throws.
 *
 * Nothing matches on the UI thread: [next], [previous] and the replace functions act on the matches of the current text
 * and query. When the published matches lag behind (right after an edit or a new query), the operation waits and runs,
 * in call order, once they are published; a new query drops operations still waiting for the old one. Regex
 * replacements are expanded on the background dispatcher, too.
 *
 * Every selection and edit goes through the factory's [EditorActionExecutor] (Command pattern): selecting a match runs
 * an internal select-match `EditorAction` (caret only, no undo step), replacing runs a replace action (origin
 * `Command`, caret mementos, one undo step). Because the editor state reveals the caret after every executed action, a
 * selected match is always scrolled into view, whoever asked for it (find bar, FIND_NEXT key, menu).
 */
public interface FindModel {

    /**
     * The query the model searches for, set synchronously by [setQuery] (the find bar renders it; find next and the
     * selection seeding read it). [FindResult.query] of [state] follows it once its matches are computed.
     */
    public val query: StateFlow<FindQuery>

    /** The matches of the current text for [FindResult.query] (the latest computed query) and the current match. */
    public val state: StateFlow<FindResult>

    /**
     * The replacement text of the find/replace bar, set by [setReplacement]. Like [query] it lives as long as the model
     * (the editor), so it survives closing the bar and a new composition of the editor, e.g. after switching tabs. The
     * replace functions take their replacement as an argument; the bar passes this one.
     */
    public val replacement: StateFlow<String>

    /**
     * Replaces the query (incremental search): once the matches of the new query are known, the current match is the
     * first match at or after the start of the primary selection (so a match that is already selected stays current)
     * and it is selected through the executor; without matches the selection stays. Typing in the find field calls
     * this for every change. Operations still waiting for the matches of the previous query are dropped.
     */
    public fun setQuery(query: FindQuery)

    /** Replaces [replacement] (typing in the replacement field calls this for every change). */
    public fun setReplacement(replacement: String)

    /**
     * Find next: if the primary selection is not exactly the current match yet, selects the current match; otherwise
     * moves to the next match (wrapping) and selects it. No-op without matches. Waits for the matches of the current
     * text and query if they are not published yet.
     */
    public fun next()

    /** Find previous: like [next], moving to the previous match (wrapping). */
    public fun previous()

    /**
     * Replaces the current match by [replacement] (with `$1` group references in regex mode) and moves to the next.
     * Waits like [next]; a regex replacement is expanded on the background dispatcher before the edit.
     */
    public fun replaceCurrent(replacement: String)

    /** Replaces every match as one undo step; waits and expands like [replaceCurrent]. */
    public fun replaceAll(replacement: String)
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
