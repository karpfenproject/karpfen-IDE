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
 * The lines of a [ProjectValidation] in the headless mode. Each finding is one line,
 * `<file>:<line>: <severity>: <message>`, with the file relative to the project and `/` between its parts, the
 * one-based line, the severity in lower case, such as `error` or `warning`, and the message on one line. The findings
 * keep the order of the validation, which puts the errors first. A summary line follows, the same that the window's
 * report shows, such as `2 errors and 1 warning` or `No problems found.`. For example:
 *
 * ```
 * lamp.kstates:14: error: The state 'of' is not declared.
 * lamp.kmodel:3: warning: The property 'brightness' is never set.
 * 1 error and 1 warning
 * ```
 *
 * The object is pure and holds no state.
 */
internal object ValidationLines {

    /** The lines of [validation]: one per finding and the summary last. */
    fun of(validation: ProjectValidation): List<String> =
        validation.findings.map { finding ->
            "${ValidationReportTexts.file(validation, finding)}:${finding.line}: " +
                "${finding.severity.name.lowercase()}: ${ValidationReportTexts.message(finding)}"
        } + ValidationReportTexts.summary(validation)
}
