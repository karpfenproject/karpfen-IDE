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
 * Parses a document into a [ParseOutcome]. There is one implementation per language, reached through
 * [io.karpfen.ide.core.document.language.LanguageSupport.parser].
 *
 * A parser never throws, for any input. ANTLR's default recovery yields a partial tree for broken text. The outline,
 * the index, folding and spans are extracted from whatever was recognized. The version of the outcome equals
 * [TextSnapshot.version]. A parser is stateless and thread-safe.
 *
 * A 5,000-line KStates document should parse in less than 150 ms. A time-budget test documents this target, but no
 * test run executes it.
 */
public interface DocumentParser {
    /**
     * Parses [snapshot]. The [mode] decides whether dsl-tools may read the document on its own. It defaults to
     * [SemanticMode.SYNTAX_ONLY], as `DocumentSessionConfig` does, so the standalone read is opt-in.
     */
    public fun parse(snapshot: TextSnapshot, mode: SemanticMode = SemanticMode.SYNTAX_ONLY): ParseOutcome
}

/** Whether [DocumentParser.parse] reads the document with dsl-tools on its own. */
public enum class SemanticMode {
    /**
     * Reads KMeta and KStates files with dsl-tools when there are no syntax diagnostics. This mode is opt-in, and no
     * product path uses it. In a workspace the project analysis is the only place that reads files with dsl-tools.
     * Each file is then read once, and opening a file never changes what is reported. Documents edited on their own
     * and the tests of the standalone semantics use this mode.
     *
     * It is not meant for `EVENTS.kmeta`. Event payloads may reference domain types, so they only read without errors
     * with the domain metamodel as base types. core-workspace reads them that way.
     */
    STANDALONE,

    /** Syntax only. The dsl-tools readers are never called. */
    SYNTAX_ONLY,
}
