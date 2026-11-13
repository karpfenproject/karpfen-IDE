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
package io.karpfen.ide.ui.workbench.dialog

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.workspace.validation.ProjectValidation
import io.karpfen.ide.core.workspace.validation.ValidationFinding
import kotlin.io.path.invariantSeparatorsPathString

/**
 * One row of the findings of a dialog. The dialog shows [text] in the colour of [severity].
 *
 * @property text the row as it is shown.
 * @property severity the severity of the finding the row stands for.
 */
internal data class FindingRow(val text: String, val severity: Severity)

/** The texts of the dialog that shows a [ProjectValidation]. The object is pure and holds no state. */
internal object ValidationReportTexts {

    /** The summary of the report when it has no finding at all. */
    const val NO_PROBLEMS: String = "No problems found."

    /** The summary of a report whose findings are neither errors nor warnings. */
    const val NO_ERRORS_OR_WARNINGS: String = "No errors or warnings."

    /**
     * The line that sums up [validation]: the counts of its errors and warnings, such as `2 errors and 1 warning`,
     * `1 error` or `3 warnings`. A report without errors and warnings says [NO_PROBLEMS], or [NO_ERRORS_OR_WARNINGS] if
     * it still has findings of another severity.
     */
    fun summary(validation: ProjectValidation): String {
        val counts = listOfNotNull(
            count(validation.errorCount, "error"),
            count(validation.warningCount, "warning"),
        )
        return when {
            counts.isNotEmpty() -> counts.joinToString(" and ")
            validation.findings.isEmpty() -> NO_PROBLEMS
            else -> NO_ERRORS_OR_WARNINGS
        }
    }

    /**
     * One row per finding of [validation], in its order. A row reads `<file>:<line>  <message>`, with the file relative
     * to the project and `/` between its parts, and the message on one line.
     */
    fun rows(validation: ProjectValidation): List<FindingRow> =
        validation.findings.map { FindingRow(rowOf(validation, it), it.severity) }

    /** The text of the row of [finding] in [validation]. */
    private fun rowOf(validation: ProjectValidation, finding: ValidationFinding): String {
        val path = if (finding.path.startsWith(validation.root)) validation.root.relativize(finding.path) else finding.path
        val file = path.invariantSeparatorsPathString.ifEmpty { "." }
        val message = finding.message.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        return "$file:${finding.line}  $message"
    }

    /** [count] of [noun], with the noun in the plural unless the count is one, or null if [count] is zero. */
    private fun count(count: Int, noun: String): String? = when (count) {
        0 -> null
        1 -> "1 $noun"
        else -> "$count ${noun}s"
    }
}
