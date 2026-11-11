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

import io.karpfen.ide.core.text.TextRange
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the search rules of a [FindMatcher] off the UI thread. Long-running work runs on a background dispatcher and can
 * be cancelled, so the UI never waits for a search.
 *
 * Matching and the expansion of regex replacements run on [backgroundDispatcher]. They stop when their coroutine is
 * cancelled because of a newer text or query, or when they exceed [budget]. So a pathological regular expression with
 * catastrophic backtracking ends in the error [TOO_SLOW] instead of keeping a thread busy. The budget runs on the clock
 * of the caller's dispatcher through `withTimeoutOrNull`, so tests measure it in virtual time. The class has no state,
 * and callers suspend.
 */
internal class FindSearch(
    private val backgroundDispatcher: CoroutineDispatcher,
    private val budget: Duration = DEFAULT_BUDGET,
    private val matcher: FindMatcher = FindMatcher(),
) {

    /** Returns the matches of [query] in [text], or the error outcome [TOO_SLOW] when the search exceeds the budget. */
    suspend fun matches(text: String, query: FindQuery): MatchOutcome {
        if (query.text.isEmpty()) return MatchOutcome(emptyList(), null)
        return withTimeoutOrNull(budget) {
            withContext(backgroundDispatcher) { matcher.find(text, query) { ensureActive() } }
        } ?: MatchOutcome(emptyList(), TOO_SLOW)
    }

    /**
     * Returns the texts that replace [matches] when they need no search, or null. Outside regex mode each text is the
     * literal [replacement]. In regex mode the result is null, because group references need [replacements].
     */
    fun literalReplacements(query: FindQuery, matches: List<TextRange>, replacement: String): List<String>? =
        if (query.regex) null else matches.map { replacement }

    /**
     * Returns the texts that replace [matches], with group references expanded by [FindMatcher.replacements]. [matches]
     * are the matches of [query] in [text]. Returns null when the expansion exceeds the budget.
     */
    suspend fun replacements(text: String, query: FindQuery, matches: List<TextRange>, replacement: String): List<String>? =
        withTimeoutOrNull(budget) {
            withContext(backgroundDispatcher) { matcher.replacements(text, query, matches, replacement) { ensureActive() } }
        }

    /** Holds the default budget and its error message. */
    companion object {
        /** How long one search or expansion may take before it is given up. */
        val DEFAULT_BUDGET: Duration = 2.seconds

        /** The error of a search that exceeded its budget. */
        const val TOO_SLOW: String = "Search took too long; simplify the regular expression"
    }
}
