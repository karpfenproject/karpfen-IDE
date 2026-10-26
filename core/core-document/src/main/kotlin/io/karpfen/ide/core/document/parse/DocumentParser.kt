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
package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.text.TextSnapshot

/**
 * Parses a document into a [ParseOutcome]. One implementation per language, reached through
 * [io.karpfen.ide.core.document.language.LanguageSupport.parser].
 *
 * Contract (MUST):
 * - Never throws, for any input. ANTLR's default recovery yields a partial tree for broken text; outline, index,
 *   folding and spans are extracted from whatever was recognized.
 * - The outcome's version equals [TextSnapshot.version].
 * - Stateless and thread-safe.
 * - Budget: a 5,000-line KStates document parses in < 150 ms. This is a target that a time-budget test documents; no
 *   test run executes it.
 */
public interface DocumentParser {
    /**
     * Parses [snapshot]; [mode] decides whether the standalone dsl-tools conversion may run ([SemanticMode.SYNTAX_ONLY]
     * by default, like `DocumentSessionConfig`; standalone conversion is opt-in).
     */
    public fun parse(snapshot: TextSnapshot, mode: SemanticMode = SemanticMode.SYNTAX_ONLY): ParseOutcome
}

/** Whether [DocumentParser.parse] runs the standalone dsl-tools conversion. */
public enum class SemanticMode {
    /**
     * Read KMeta and KStates files with dsl-tools when there are no syntax diagnostics. Opt-in: no product path uses
     * it, because in a workspace the project analysis is the only place that reads files with dsl-tools, so each file
     * is read once and opening a file never changes what is reported. Documents edited on their own and the tests of
     * the standalone semantics use it. Not for `EVENTS.kmeta`: event payloads may reference domain types and only read
     * without errors with the domain metamodel as base types, which core-workspace does.
     */
    STANDALONE,

    /** Syntax only; never call the dsl-tools readers. */
    SYNTAX_ONLY,
}
