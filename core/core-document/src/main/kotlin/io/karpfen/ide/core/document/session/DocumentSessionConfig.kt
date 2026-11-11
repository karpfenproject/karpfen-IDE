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
 * Tuning of a [DocumentSession]. Tests inject a `StandardTestDispatcher` for both dispatchers, so the debounce runs on
 * the virtual time of `kotlinx-coroutines-test`.
 *
 * @property parseDebounce the quiet time after the last change before the text is parsed again. It is
 *   [DEFAULT_PARSE_DEBOUNCE] by default.
 * @property semanticMode the mode passed to the parser. Every product path uses the default,
 *   [SemanticMode.SYNTAX_ONLY]. In a workspace the project analysis is the only place that reads files with dsl-tools.
 *   Each file is then read once, and opening a file never changes what is reported. Sessions created without an
 *   explicit config, such as editor tests and embeddings, run the same diagnostics pipeline as the product.
 *   [SemanticMode.STANDALONE] is opt-in. It serves a document read on its own, and the tests of the standalone
 *   semantics and of the session.
 * @property tokenizeDispatcher where tokenizing runs. It is CPU work.
 * @property parseDispatcher where parsing and the standalone read run. Both are CPU work.
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
        /**
         * The default parse debounce of 120 ms. It is short enough to feel live and long enough to skip most
         * keystrokes.
         */
        public val DEFAULT_PARSE_DEBOUNCE: Duration = 120.milliseconds
    }
}
