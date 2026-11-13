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
import java.nio.file.Path

/** Output format of `karpfen-ide validate`, which its option `--format` chooses. */
internal enum class OutputFormat {
    /** One line per finding and a summary, as [ValidationLines] describes. */
    TEXT,

    /** A JSON document with the root, the counts and the list of problems, as [JsonReportFormatter] describes. */
    JSON,
}

/**
 * One reported problem.
 *
 * @property path the file, relative to the validated root with `/` separators.
 * @property line the one-based line.
 * @property column the one-based column.
 * @property severity the severity of the finding.
 * @property message the finding's message.
 * @property code the finding's code, such as `unreadable`.
 */
internal data class ReportedProblem(
    val path: String,
    val line: Int,
    val column: Int,
    val severity: Severity,
    val message: String,
    val code: String,
)

/** Everything a validation found below [root], sorted by path, line and column. */
internal data class ValidationReport(val root: Path, val problems: List<ReportedProblem>)
