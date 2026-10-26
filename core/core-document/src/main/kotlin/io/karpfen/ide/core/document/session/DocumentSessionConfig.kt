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
 *   in a workspace the project analysis is the only place that reads files with dsl-tools, so each file is read once
 *   and opening a file never changes what is reported; sessions created without an explicit config (editor tests,
 *   embeddings) run the same diagnostics pipeline as the product. [SemanticMode.STANDALONE] is opt-in, for a document
 *   read on its own (and the tests of the standalone semantics and of the session).
 * @property tokenizeDispatcher where tokenizing runs (CPU work).
 * @property parseDispatcher where parsing and the standalone read run (CPU work).
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
