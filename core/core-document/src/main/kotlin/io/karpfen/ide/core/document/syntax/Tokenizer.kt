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
package io.karpfen.ide.core.document.syntax

import io.karpfen.ide.core.text.TextSnapshot

/**
 * Splits a document into classified tokens. Each language has one implementation, reached through
 * [io.karpfen.ide.core.document.language.LanguageSupport.tokenizer].
 *
 * Every implementation keeps these rules:
 * - It never throws, for any input. Lexer errors become [TokenKind.ERROR] tokens plus LEXER diagnostics.
 * - The version of the result equals [TextSnapshot.version]. The tokens are sorted, do not overlap and cover every
 *   non-whitespace character exactly once.
 * - It holds no state and is thread-safe, so it may be called concurrently for different snapshots.
 * - A 5,000-line KStates document should tokenize in less than 30 ms. A time-budget test documents this target, but no
 *   test run executes it.
 */
public fun interface Tokenizer {
    /** Tokenizes [snapshot]. */
    public fun tokenize(snapshot: TextSnapshot): TokenizeResult
}
