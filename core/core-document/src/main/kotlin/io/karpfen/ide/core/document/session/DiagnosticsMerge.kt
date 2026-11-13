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

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.parse.ParseOutcome

/**
 * The one merge rule for the diagnostics of a document. [DocumentSession.diagnostics] and `AnalyzedFile.ownDiagnostics`
 * in core-workspace both use it. So an open and an unopened file are merged the same way, and the editor and the
 * validation of the project report the same.
 *
 * The merge takes [lexical] whole. These are the tokenizer's diagnostics, which are LEXER errors. From [parse] it takes
 * the PARSER diagnostics and the standalone DSL_TOOLS diagnostics. The LEXER diagnostics of [parse] come from the
 * parser's own lexer and repeat the tokenizer's, so they are dropped and nothing is reported twice. Last, it takes
 * every list of [external], such as the sources of [ExternalDiagnosticSources].
 *
 * All ranges must already refer to the same text. The session maps older ones forward before merging. The result is
 * sorted with [Diagnostic.ORDER] and has no duplicates. The function is pure.
 */
public fun mergeDiagnostics(
    lexical: List<Diagnostic>,
    parse: ParseOutcome?,
    external: Collection<List<Diagnostic>> = emptyList(),
): List<Diagnostic> {
    val merged = ArrayList<Diagnostic>(lexical)
    if (parse != null) {
        parse.syntaxDiagnostics.filterTo(merged) { it.source == DiagnosticSource.PARSER }
        merged += parse.standaloneDiagnostics
    }
    external.forEach { merged += it }
    return merged.distinct().sortedWith(Diagnostic.ORDER)
}
