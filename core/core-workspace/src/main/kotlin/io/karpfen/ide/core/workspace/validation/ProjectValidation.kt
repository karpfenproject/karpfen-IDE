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
package io.karpfen.ide.core.workspace.validation

import io.karpfen.ide.core.document.Severity
import java.nio.file.Path

/**
 * The report of a validation of a whole project, as [ProjectValidator] makes it. It lists every finding of every file:
 * what the project analysis found and what the verifier of dsl-tools found across the files of each modeling context.
 *
 * @property root the folder of the project, absolute and normalized.
 * @property findings the findings in the order of [ValidationFinding.ORDER]: errors first, then warnings, then the
 *   rest, and within one severity by path, line and column. A finding is listed once.
 */
public data class ProjectValidation(public val root: Path, public val findings: List<ValidationFinding>) {

    /** The number of findings of severity ERROR. */
    public val errorCount: Int get() = findings.count { it.severity == Severity.ERROR }

    /** The number of findings of severity WARNING. */
    public val warningCount: Int get() = findings.count { it.severity == Severity.WARNING }

    /** True if any finding is an ERROR. Such a project does not run. */
    public val hasErrors: Boolean get() = findings.any { it.severity == Severity.ERROR }
}

/**
 * One finding of a [ProjectValidation].
 *
 * @property path the file, absolute and normalized. For a file or directory that could not be read it is that path.
 * @property line the one-based line of the start of the finding.
 * @property column the one-based column of the start of the finding.
 * @property severity the severity.
 * @property code the stable code, such as `syntax-error`, `kmodel.unknown-property` or `project.unknown-path`.
 * @property message what is wrong, in words.
 */
public data class ValidationFinding(
    public val path: Path,
    public val line: Int,
    public val column: Int,
    public val severity: Severity,
    public val code: String,
    public val message: String,
) {
    public companion object {
        /**
         * The order of the findings of a report: by severity in the order of [Severity], so errors come before
         * warnings, then by path, line, column, code and message. Two findings that compare equal are equal.
         */
        public val ORDER: Comparator<ValidationFinding> = compareBy<ValidationFinding> { it.severity }
            .thenBy { it.path }
            .thenBy { it.line }
            .thenBy { it.column }
            .thenBy { it.code }
            .thenBy { it.message }
    }
}
