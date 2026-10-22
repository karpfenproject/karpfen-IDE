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
 * Turns what a dsl-tools reader reports into the IDE's values: a [ParseResult] into a [ReadResult], each dsl-tools
 * diagnostic into a [Diagnostic] of source [DiagnosticSource.DSL_TOOLS].
 *
 * A dsl-tools range counts UTF-16 chars like a [TextRange], so its offsets carry over unchanged; the line and column of
 * its positions are not needed, because the snapshot knows its lines. The reader guarantees that its ranges lie within
 * the text it read, and the mapping still clamps them to the snapshot, so that a defect of dsl-tools can never put a
 * range outside the document. The code of a diagnostic is the stable id of its dsl-tools code
 * ([DiagnosticCode.id]), the message is the reader's.
 */
internal object ReadResults {

    /** The [ReadResult] of [parse], a read of [source]'s text: its model and its diagnostics, sorted. */
    fun <T : Any> of(parse: ParseResult<T>, source: TextSnapshot): ReadResult<T> =
        ReadResult(parse.model, parse.diagnostics.map { diagnosticOf(it, source.length) }.sortedWith(Diagnostic.ORDER))

    /**
     * The result of a read that failed with [failure]: no model and one `reader.failure` error at the start of the text,
     * worded like the failures the readers report themselves.
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
            message = diagnostic.message,
            code = diagnostic.code.id,
            source = DiagnosticSource.DSL_TOOLS,
        )

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
