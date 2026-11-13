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

import io.karpfen.ide.core.workspace.validation.ProjectValidation
import io.karpfen.ide.ui.workbench.dialog.ValidationReportTexts

/**
 * The [ValidationReport] of this validation of the project. There is one [ReportedProblem] per finding, and nothing
 * is filtered or merged, because the validation of the workspace already decided what is reported. Each problem has
 * its path as `ValidationReportTexts.file` names it, relative to the root with `/` separators, and the one-based
 * position of its finding. The problems are sorted by path, line and column. Ties keep the order of the validation,
 * `ValidationFinding.ORDER`.
 */
internal fun ProjectValidation.toValidationReport(): ValidationReport {
    val problems = findings.map { finding ->
        ReportedProblem(
            path = ValidationReportTexts.file(this, finding),
            line = finding.line,
            column = finding.column,
            severity = finding.severity,
            message = finding.message,
            code = finding.code,
        )
    }
    return ValidationReport(root, problems.sortedWith(REPORT_ORDER))
}

/** Path, then line, then column. A stable sort keeps the order of the validation for ties. */
private val REPORT_ORDER: Comparator<ReportedProblem> =
    compareBy<ReportedProblem> { it.path }.thenBy { it.line }.thenBy { it.column }
