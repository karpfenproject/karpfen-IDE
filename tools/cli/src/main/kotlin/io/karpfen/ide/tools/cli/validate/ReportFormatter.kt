package io.karpfen.ide.tools.cli.validate

/** Renders a [ValidationReport] for the console. */
public fun interface ReportFormatter {
    /** The complete output for [report] (empty for TEXT without problems). */
    public fun format(report: ValidationReport): String
}

/** The formatter for [format]. */
public fun ReportFormatter(format: OutputFormat): ReportFormatter = when (format) {
    OutputFormat.TEXT -> TextReportFormatter()
    OutputFormat.JSON -> JsonReportFormatter()
}

internal class TextReportFormatter : ReportFormatter {
    override fun format(report: ValidationReport): String = TODO("WP-APP: text report")
}

internal class JsonReportFormatter : ReportFormatter {
    override fun format(report: ValidationReport): String = TODO("WP-APP: JSON report (kotlinx.serialization)")
}
