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

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.KarpfenLanguage

/**
 * Everything derived from parsing one text version. All ranges are in the coordinates of [version].
 *
 * @property language the language the text was parsed as.
 * @property version the parsed text version.
 * @property syntaxDiagnostics errors reported while parsing: PARSER errors plus LEXER errors seen by the parser's own
 *   lexer, sorted with [Diagnostic.ORDER]. (The document session shows LEXER errors from the tokenizer and only the
 *   PARSER ones from here, so nothing is reported twice.)
 * @property outline top-level outline nodes in document order.
 * @property index declarations and references.
 * @property folding foldable regions sorted by start.
 * @property semanticSpans sorted, non-overlapping semantic spans.
 * @property standaloneModel the dsl-tools model of this file alone, if [SemanticMode.STANDALONE] was requested, the
 *   language supports it (KMeta, KStates), there were no syntax diagnostics and the text yields a model; partial if
 *   [standaloneDiagnostics] hold an error; null otherwise.
 * @property standaloneDiagnostics DSL_TOOLS diagnostics of the standalone read, at the places the dsl-tools reader
 *   reports (empty if it did not run).
 */
public data class ParseOutcome(
    public val language: KarpfenLanguage,
    public val version: Long,
    public val syntaxDiagnostics: List<Diagnostic>,
    public val outline: List<OutlineNode>,
    public val index: SyntaxIndex,
    public val folding: List<FoldingRange>,
    public val semanticSpans: List<SemanticSpan>,
    public val standaloneModel: DslModel?,
    public val standaloneDiagnostics: List<Diagnostic>,
) {
    init {
        require(version >= 0) { "version must be >= 0, was $version" }
    }

    /** True if parsing reported no syntax diagnostics, which makes this outcome the session's new last good parse. */
    public val isSyntaxClean: Boolean get() = syntaxDiagnostics.isEmpty()
}
