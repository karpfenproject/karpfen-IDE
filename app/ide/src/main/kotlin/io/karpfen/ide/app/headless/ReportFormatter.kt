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
package io.karpfen.ide.app.headless

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.workspace.validation.ProjectValidation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Renders the report of a validation of the whole project for the console. */
internal fun interface ReportFormatter {
    /** The lines of the complete output for [validation], each written as one line. */
    fun lines(validation: ProjectValidation): List<String>
}

/** The formatter for [format]. */
internal fun ReportFormatter(format: OutputFormat): ReportFormatter = when (format) {
    OutputFormat.TEXT -> ReportFormatter(ValidationLines::of)
    OutputFormat.JSON -> JsonReportFormatter()
}

/**
 * A pretty-printed JSON document. Its shape is
 * `{"root": "<absolute root>", "errorCount": n, "warningCount": n, "problems": [{"path", "line", "column", "severity",
 * "message", "code"}, ...]}`, with the members in this order. The problems are those of
 * [ProjectValidation.toValidationReport], in its order. Positions are one-based, paths are relative to the root with
 * `/` separators, severities are in lower case, and a message keeps its line breaks.
 */
internal class JsonReportFormatter : ReportFormatter {
    override fun lines(validation: ProjectValidation): List<String> {
        val report = validation.toValidationReport()
        val document = JsonReport(
            root = report.root.toString(),
            errorCount = report.problems.count { it.severity == Severity.ERROR },
            warningCount = report.problems.count { it.severity == Severity.WARNING },
            problems = report.problems.map { problem ->
                JsonProblem(problem.path, problem.line, problem.column, problem.severity.label, problem.message, problem.code)
            },
        )
        return JSON.encodeToString(JsonReport.serializer(), document).lines()
    }

    /** The serialized shape of the whole report. */
    @Serializable
    private data class JsonReport(val root: String, val errorCount: Int, val warningCount: Int, val problems: List<JsonProblem>)

    /** The serialized shape of one problem, with [severity] in lower case. */
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

/** The severity as the JSON document writes it: `error`, `warning`, `info`, `hint`. */
private val Severity.label: String get() = name.lowercase()
