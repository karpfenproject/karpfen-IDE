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
 * Splits a document into classified tokens. One implementation per language, reached through
 * [io.karpfen.ide.core.document.language.LanguageSupport.tokenizer].
 *
 * Contract (MUST):
 * - Never throws, for any input; lexer errors become [TokenKind.ERROR] tokens plus LEXER diagnostics.
 * - The result's version equals [TextSnapshot.version]; tokens are sorted, non-overlapping, and cover every
 *   non-whitespace character exactly once.
 * - Stateless and thread-safe: may be called concurrently for different snapshots.
 * - Budget: a 5,000-line KStates document tokenizes in < 30 ms. This is a target that a time-budget test documents;
 *   no test run executes it.
 */
public fun interface Tokenizer {
    /** Tokenizes [snapshot]. */
    public fun tokenize(snapshot: TextSnapshot): TokenizeResult
}
