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
package io.karpfen.ide.core.document.frame

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.parse.DocumentParser
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.parse.SemanticMode
import io.karpfen.ide.core.document.parse.SemanticSpan
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.text.TextFrame
import io.karpfen.ide.core.text.TextSnapshot

/**
 * The parser of a piece of a [FramedDocument]. It decorates the parser of the file's language: it puts the piece in
 * its place in the whole text, lets the parser of the language parse that whole text and gives the outcome back in the
 * coordinates of the piece.
 *
 * The outcome of a piece holds the syntax errors and the semantic spans of the whole text. A semantic span is clipped
 * to the piece, and one outside the piece is left out, so the piece gets the semantic colours it has in the file. The
 * syntax errors are placed by [PieceDiagnostics.pinned]: one outside the piece stands at the nearest end of the piece,
 * because the text around the piece comes from a file without syntax errors, so the piece is what broke it. The
 * outline, the index, the folding and the standalone semantics stay empty, because they describe the whole file. The
 * language services read them from [FramedDocument.view] instead. The whole text is always parsed with
 * [SemanticMode.SYNTAX_ONLY], since only the project analysis reads files with dsl-tools.
 *
 * A piece that the document cannot place is parsed by [fallback], which finds nothing.
 */
internal class FramedDocumentParser(
    private val document: FramedDocument,
    private val fallback: DocumentParser,
) : DocumentParser {

    override fun parse(snapshot: TextSnapshot, mode: SemanticMode): ParseOutcome {
        val framed = document.frame(snapshot) ?: return fallback.parse(snapshot, mode)
        val outcome = document.parse(framed)
        return ParseOutcome(
            language = outcome.language,
            version = snapshot.version,
            syntaxDiagnostics = PieceDiagnostics.pinned(framed.frame, outcome.syntaxDiagnostics),
            outline = emptyList(),
            index = SyntaxIndex.EMPTY,
            folding = emptyList(),
            semanticSpans = spansIn(framed.frame, outcome.semanticSpans),
            standaloneModel = null,
            standaloneDiagnostics = emptyList<Diagnostic>(),
        )
    }

    /** The [spans] of the whole text that reach into the piece of [frame], clipped to it and in its coordinates. */
    private fun spansIn(frame: TextFrame, spans: List<SemanticSpan>): List<SemanticSpan> =
        spans.mapNotNull { span -> frame.toPiece(span.range)?.takeUnless { it.isEmpty }?.let { SemanticSpan(it, span.kind) } }
}
