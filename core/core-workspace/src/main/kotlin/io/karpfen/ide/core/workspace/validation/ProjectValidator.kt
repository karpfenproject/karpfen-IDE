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

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.dsl.DslVerifier
import io.karpfen.ide.core.text.TextPosition
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import java.nio.file.Path

/**
 * Validates a whole project. A validation takes two things together. The first is everything the project analysis
 * found in the files, as the [WorkspaceProblems] of that analysis hold it: syntax errors, the findings of the dsl-tools
 * readers, and the findings about descriptors, contexts and files that cannot be read. The second is what the verifier
 * of dsl-tools finds when it checks the state machines of each modeling context against the metamodels and the model of
 * that context. These checks span several files, and their codes start with `project.`. The continuous analysis never
 * runs them, so they are found only when a validation is asked for.
 *
 * The verification works on the reads that the [ProjectAnalysis] kept, so it sees exactly the texts the analysis saw.
 * Its ranges are turned into lines and columns with the snapshot of the analyzed file. Those of the problems come from
 * `FileProblems.positionOf`. The `Workspace` runs a validation after an analysis with [validate] and offers it as
 * `Workspace.validate`. Implementations are pure and thread-safe.
 */
public fun interface ProjectValidator {

    /**
     * The report of the project at [root], made from [analysis] and the [problems] of the same analysis. Its findings
     * are sorted with [ValidationFinding.ORDER], and each one is listed once.
     */
    public fun validate(root: Path, analysis: ProjectAnalysis, problems: WorkspaceProblems): ProjectValidation
}

/** The default [ProjectValidator]. It verifies each context with [verifier]. */
public fun ProjectValidator(verifier: DslVerifier = DslVerifier()): ProjectValidator =
    DefaultProjectValidator(ContextVerification(verifier))

/**
 * The [ProjectValidator] described there. It lists the problems of every file and then the findings of [verification]
 * for every context of the analysis, drops the findings that are listed twice and sorts the rest.
 */
internal class DefaultProjectValidator(private val verification: ContextVerification) : ProjectValidator {

    override fun validate(root: Path, analysis: ProjectAnalysis, problems: WorkspaceProblems): ProjectValidation {
        val findings = ArrayList<ValidationFinding>()
        for (file in problems.files) {
            file.diagnostics.forEach { findings += findingOf(file.path, it, file.positionOf(it)) }
        }
        for (context in analysis.contexts) {
            for ((path, diagnostics) in verification.findings(context)) {
                val snapshot = context.files[path]?.snapshot ?: continue
                diagnostics.forEach { findings += findingOf(path, it, snapshot.positionOf(it.range.start.coerceAtMost(snapshot.length))) }
            }
        }
        return ProjectValidation(root, findings.distinct().sortedWith(ValidationFinding.ORDER))
    }

    /** The finding of [diagnostic] in the file at [path], whose range starts at the zero-based [position]. */
    private fun findingOf(path: Path, diagnostic: Diagnostic, position: TextPosition): ValidationFinding =
        ValidationFinding(
            path = path,
            line = position.line + 1,
            column = position.column + 1,
            severity = diagnostic.severity,
            code = diagnostic.code,
            message = diagnostic.message,
        )
}
