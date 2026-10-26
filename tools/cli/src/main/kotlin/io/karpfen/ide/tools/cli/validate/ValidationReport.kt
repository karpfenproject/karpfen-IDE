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
import java.nio.file.Path

/** Output format of `karpfen validate`. */
public enum class OutputFormat {
    /** One line per problem: `path:line:col: severity: message [code]` (one-based, severity in lower case). */
    TEXT,

    /** A JSON document with the root and the list of problems. */
    JSON,
}

/** One reported problem; [path] is relative to the validated root with `/` separators; [line]/[column] are one-based. */
public data class ReportedProblem(
    public val path: String,
    public val line: Int,
    public val column: Int,
    public val severity: Severity,
    public val message: String,
    public val code: String,
)

/** Everything `karpfen validate` found below [root], sorted by path, line, column. */
public data class ValidationReport(public val root: Path, public val problems: List<ReportedProblem>) {
    /** True if any problem is an ERROR (exit status 1). */
    public val hasErrors: Boolean get() = problems.any { it.severity == Severity.ERROR }
}
