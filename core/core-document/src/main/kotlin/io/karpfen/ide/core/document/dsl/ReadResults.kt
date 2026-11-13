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
package io.karpfen.ide.core.document.dsl

import io.karpfen.dsl.diagnostics.DiagnosticCode
import io.karpfen.dsl.reader.ParseResult
import io.karpfen.dsl.source.SourceRange
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.dsl.diagnostics.Diagnostic as DslDiagnostic
import io.karpfen.dsl.diagnostics.Severity as DslSeverity

/**
 * Turns what a dsl-tools reader or the dsl-tools verifier reports into the IDE's values. A [ParseResult] becomes a
 * [ReadResult], and each dsl-tools diagnostic becomes a [Diagnostic] with source [DiagnosticSource.DSL_TOOLS].
 *
 * A dsl-tools range counts UTF-16 chars like a [TextRange], so its offsets carry over unchanged. The line and column of
 * its positions are not needed, because the snapshot knows its lines. The reader guarantees that its ranges lie within
 * the text it read. The mapping still clamps them to the snapshot, so that a defect of dsl-tools can never put a range
 * outside the document. The code of a diagnostic is the stable id of its dsl-tools code, [DiagnosticCode.id]. The
 * message is the reader's. The one addition is for a construct of a language feature that is switched off: dsl-tools
 * cannot know where a tool switches a feature on, so the IDE says it behind the reader's message.
 */
internal object ReadResults {

    /**
     * The [ReadResult] of [parse], which read the text of [source]. It holds the model and the sorted diagnostics, and
     * it keeps [parse] for the verification.
     */
    fun <T : Any> of(parse: ParseResult<T>, source: TextSnapshot): ReadResult<T> =
        ReadResult(
            model = parse.model,
            diagnostics = diagnosticsOf(parse.diagnostics, source.length),
            source = DslSource(parse, source.length),
        )

    /** The IDE diagnostics of [diagnostics], reported for a text of [textLength] chars, sorted with [Diagnostic.ORDER]. */
    fun diagnosticsOf(diagnostics: List<DslDiagnostic>, textLength: Int): List<Diagnostic> =
        diagnostics.map { diagnosticOf(it, textLength) }.sortedWith(Diagnostic.ORDER)

    /**
     * The result of a read that failed with [failure]. It has no model and one `reader.failure` error at the start of
     * the text, worded like the failures that the readers report themselves.
     */
    fun failure(failure: Throwable): ReadResult<Nothing> =
        ReadResult(
            model = null,
            diagnostics = listOf(
                Diagnostic(
                    range = TextRange.EMPTY,
                    severity = Severity.ERROR,
                    message = "The text could not be read: $failure",
                    code = DiagnosticCode.READER_FAILURE.id,
                    source = DiagnosticSource.DSL_TOOLS,
                ),
            ),
        )

    /** The IDE diagnostic of [diagnostic], reported for a text of [textLength] chars. */
    fun diagnosticOf(diagnostic: DslDiagnostic, textLength: Int): Diagnostic =
        Diagnostic(
            range = rangeOf(diagnostic.range, textLength),
            severity = severityOf(diagnostic.severity),
            message = if (diagnostic.code == DiagnosticCode.FEATURE_OFF) "${diagnostic.message} $FEATURE_SWITCH" else diagnostic.message,
            code = diagnostic.code.id,
            source = DiagnosticSource.DSL_TOOLS,
        )

    /** Where the IDE switches a language feature on, said behind the message of a construct whose feature is off. */
    const val FEATURE_SWITCH: String = "Run > Language Features switches them on for this project."

    /** The [TextRange] of [range], clamped to a text of [textLength] chars. */
    private fun rangeOf(range: SourceRange, textLength: Int): TextRange {
        val start = range.start.offset.coerceIn(0, textLength)
        return TextRange(start, range.end.offset.coerceIn(start, textLength))
    }

    /** The IDE severity of a dsl-tools [severity]. */
    private fun severityOf(severity: DslSeverity): Severity =
        when (severity) {
            DslSeverity.ERROR -> Severity.ERROR
            DslSeverity.WARNING -> Severity.WARNING
        }
}
