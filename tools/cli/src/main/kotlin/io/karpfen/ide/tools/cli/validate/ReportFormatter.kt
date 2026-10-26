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
package io.karpfen.ide.tools.cli.validate

import io.karpfen.ide.core.document.Severity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

/**
 * One line per problem, `path:line:col: severity: message [code]` (one-based, severity in lower case), each ended by
 * `\n`; nothing at all without problems. A multi-line message is joined into one line, so every line is one problem.
 */
internal class TextReportFormatter : ReportFormatter {
    override fun format(report: ValidationReport): String = buildString {
        for (problem in report.problems) {
            append(problem.path).append(':').append(problem.line).append(':').append(problem.column).append(": ")
            append(problem.severity.label).append(": ")
            append(singleLine(problem.message)).append(" [").append(problem.code).append("]\n")
        }
    }

    /** [message] with its non-blank lines trimmed and joined by single spaces. */
    private fun singleLine(message: String): String =
        message.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
}

/**
 * A pretty-printed JSON document ended by `\n`:
 * `{"root": "<absolute root>", "errorCount": n, "warningCount": n, "problems": [{"path", "line", "column", "severity",
 * "message", "code"}, …]}` with one-based positions, `/` paths relative to the root and lower-case severities.
 */
internal class JsonReportFormatter : ReportFormatter {
    override fun format(report: ValidationReport): String {
        val document = JsonReport(
            root = report.root.toString(),
            errorCount = report.problems.count { it.severity == Severity.ERROR },
            warningCount = report.problems.count { it.severity == Severity.WARNING },
            problems = report.problems.map { problem ->
                JsonProblem(problem.path, problem.line, problem.column, problem.severity.label, problem.message, problem.code)
            },
        )
        return JSON.encodeToString(JsonReport.serializer(), document) + "\n"
    }

    /** The serialized shape of the whole report. */
    @Serializable
    private data class JsonReport(val root: String, val errorCount: Int, val warningCount: Int, val problems: List<JsonProblem>)

    /** The serialized shape of one problem; [severity] in lower case. */
    @Serializable
    private data class JsonProblem(
        val path: String,
        val line: Int,
        val column: Int,
        val severity: String,
        val message: String,
        val code: String,
    )

    private companion object {
        private val JSON = Json { prettyPrint = true }
    }
}

/** The severity as both formats print it: `error`, `warning`, `info`, `hint`. */
private val Severity.label: String get() = name.lowercase()
