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
 * @property parseDebounce quiet time after the last change before re-parsing ([DEFAULT_PARSE_DEBOUNCE] by default).
 * @property semanticMode passed to the parser. [SemanticMode.SYNTAX_ONLY] (the default) is what every product path uses:
 *   in a workspace the project analysis is the only place that converts files, so each file is converted once and
 *   opening a file never changes what is reported; sessions created without an explicit config (editor tests,
 *   embeddings) run the same diagnostics pipeline as the product. [SemanticMode.STANDALONE] is opt-in, for a document
 *   converted on its own (and the tests of the standalone semantics and of the session).
 * @property tokenizeDispatcher where tokenizing runs (CPU work).
 * @property parseDispatcher where parsing and standalone conversion run (CPU work).
 */
public data class DocumentSessionConfig(
    public val parseDebounce: Duration = DEFAULT_PARSE_DEBOUNCE,
    public val semanticMode: SemanticMode = SemanticMode.SYNTAX_ONLY,
    public val tokenizeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    public val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    init {
        require(!parseDebounce.isNegative()) { "parseDebounce must not be negative, was $parseDebounce" }
    }

    public companion object {
        /** Default parse debounce: 120 ms, short enough to feel live, long enough to skip most keystrokes. */
        public val DEFAULT_PARSE_DEBOUNCE: Duration = 120.milliseconds
    }
}
