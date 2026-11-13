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

/**
 * The texts of a [ProjectValidation]: those of the dialog that shows it in the window, and the parts every other
 * report of a validation shares with it, such as the headless mode's lines. The object is pure and holds no state.
 */
public object ValidationReportTexts {

    /** The summary of the report when it has no finding at all. */
    public const val NO_PROBLEMS: String = "No problems found."

    /** The summary of a report whose findings are neither errors nor warnings. */
    public const val NO_ERRORS_OR_WARNINGS: String = "No errors or warnings."

    /**
     * The line that sums up [validation]: the counts of its errors and warnings, such as `2 errors and 1 warning`,
     * `1 error` or `3 warnings`. A report without errors and warnings says [NO_PROBLEMS], or [NO_ERRORS_OR_WARNINGS] if
     * it still has findings of another severity.
     */
    public fun summary(validation: ProjectValidation): String {
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
     * The file of [finding] as a report of [validation] names it: relative to the project with `/` between its parts,
     * `.` for the project folder itself, and the whole path for a file outside the project.
     */
    public fun file(validation: ProjectValidation, finding: ValidationFinding): String {
        val path = if (finding.path.startsWith(validation.root)) validation.root.relativize(finding.path) else finding.path
        return path.invariantSeparatorsPathString.ifEmpty { "." }
    }

    /** The message of [finding] on one line: its lines trimmed, the empty ones left out, and the rest joined by blanks. */
    public fun message(finding: ValidationFinding): String =
        finding.message.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * One row per finding of [validation], in its order. A row reads `<file>:<line>  <message>`, with the file as
     * [file] names it and the message as [message] puts it.
     */
    internal fun rows(validation: ProjectValidation): List<FindingRow> =
        validation.findings.map { FindingRow("${file(validation, it)}:${it.line}  ${message(it)}", it.severity) }

    /** [count] of [noun], with the noun in the plural unless the count is one, or null if [count] is zero. */
    private fun count(count: Int, noun: String): String? = when (count) {
        0 -> null
        1 -> "1 $noun"
        else -> "$count ${noun}s"
    }
}
