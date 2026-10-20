package io.karpfen.ide.core.document.session

import io.karpfen.ide.core.document.parse.SemanticMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tuning of a [DocumentSession]. Tests inject a `StandardTestDispatcher` for both dispatchers so debounce runs on
 * virtual time (`kotlinx-coroutines-test`).
 *
 * @property parseDebounce quiet time after the last change before re-parsing (spec §3.4: 120 ms).
 * @property semanticMode passed to the parser; core-workspace uses [SemanticMode.SYNTAX_ONLY] for `EVENTS.kmeta`.
 * @property tokenizeDispatcher where tokenizing runs (CPU work).
 * @property parseDispatcher where parsing and standalone conversion run (CPU work).
 */
public data class DocumentSessionConfig(
    public val parseDebounce: Duration = DEFAULT_PARSE_DEBOUNCE,
    public val semanticMode: SemanticMode = SemanticMode.STANDALONE,
    public val tokenizeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    public val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    init {
        require(!parseDebounce.isNegative()) { "parseDebounce must not be negative, was $parseDebounce" }
    }

    public companion object {
        /** Default parse debounce (spec §3.4). */
        public val DEFAULT_PARSE_DEBOUNCE: Duration = 120.milliseconds
    }
}
