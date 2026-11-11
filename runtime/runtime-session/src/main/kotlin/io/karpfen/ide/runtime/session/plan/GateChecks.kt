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
package io.karpfen.ide.runtime.session.plan

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import io.karpfen.ide.runtime.session.problem.SourceLocation
import java.nio.file.Path

/** Finds a descriptor that resolves to no explicit context, because it could not be read or decoded. */
internal class NoContextCheck : PlanCheck {
    override fun check(subject: PlanSubject): List<DeployProblem> {
        if (subject.context != null && subject.environment != null) return emptyList()
        return listOf(
            DeployProblem(
                Severity.ERROR,
                DeployProblemCodes.NO_CONTEXT,
                "${subject.nameOf(subject.descriptor)} resolves to no modeling context: it could not be read or decoded, " +
                    "and its own problems say why.",
                subject.descriptorLocation,
            ),
        )
    }
}

/**
 * Finds files of the context and the descriptor that still differ from their saved state, because they were edited
 * while the deployment was prepared. The deployment uploads saved files only.
 */
internal class UnsavedFileCheck : PlanCheck {
    override fun check(subject: PlanSubject): List<DeployProblem> =
        subject.gatedFiles.distinct().filter { it in subject.unsaved }.map { file ->
            DeployProblem(
                Severity.ERROR,
                DeployProblemCodes.UNSAVED_FILE,
                "${subject.nameOf(file)} differs from its saved state; it was edited while the deployment was prepared. " +
                    "Deploy again.",
                if (file == subject.descriptor) subject.descriptorLocation else subject.fileLocation(file),
            )
        }
}

/**
 * Finds the errors in the analyzed texts that the deployment would upload. For a file of the context, it reads the
 * file's own diagnostics and the project diagnostics of the same analysis. For the descriptor, it reads its project
 * diagnostics. These belong to exactly the analyzed snapshots. The workspace's problems are different, because their
 * entries for open documents may belong to a newer text. The check reports one problem per file, at its first error,
 * with the error count. A file of the context that the analysis could not load is an error too.
 */
internal class ContextErrorsCheck : PlanCheck {
    override fun check(subject: PlanSubject): List<DeployProblem> {
        val contextFiles = subject.modelingContext?.files.orEmpty().distinct()
        return contextFiles.mapNotNull { contextFileProblem(subject, it) } + listOfNotNull(descriptorProblem(subject))
    }

    /** The problem of the context file [file], or null if its analyzed text has no error. */
    private fun contextFileProblem(subject: PlanSubject, file: Path): DeployProblem? {
        val analyzed = subject.context?.files?.get(file)
        val errors = errorsOf(analyzed?.ownDiagnostics.orEmpty() + subject.analysis.diagnosticsFor(file))
        if (analyzed == null && errors.isEmpty()) {
            return DeployProblem(
                Severity.ERROR,
                DeployProblemCodes.CONTEXT_ERRORS,
                "${subject.nameOf(file)} could not be read, so it cannot be uploaded.",
                SourceLocation(file, null, null),
            )
        }
        val first = errors.firstOrNull() ?: return null
        return problem(subject.nameOf(file), errors, subject.rangeLocation(file, first.range))
    }

    /** The problem of the descriptor, or null if it has no error. */
    private fun descriptorProblem(subject: PlanSubject): DeployProblem? {
        val errors = errorsOf(subject.analysis.diagnosticsFor(subject.descriptor))
        val first = errors.firstOrNull() ?: return null
        return problem(subject.nameOf(subject.descriptor), errors, SourceLocation(subject.descriptor, first.range, null))
    }

    /** The distinct errors among [diagnostics], in diagnostic order. */
    private fun errorsOf(diagnostics: List<Diagnostic>): List<Diagnostic> =
        diagnostics.filter { it.severity == Severity.ERROR }.distinct().sortedWith(Diagnostic.ORDER)

    /** The problem of the file [name] with [errors], at [location], which is the location of the first error. */
    private fun problem(name: String, errors: List<Diagnostic>, location: SourceLocation): DeployProblem {
        val count = if (errors.size == 1) "an error" else "${errors.size} errors"
        return DeployProblem(
            Severity.ERROR,
            DeployProblemCodes.CONTEXT_ERRORS,
            "$name has $count in the text that would be uploaded; the first: ${errors.first().message}",
            location,
        )
    }
}

/** Finds a context that names no domain metamodel or no model, or whose metamodel or model was not read. */
internal class MissingInputsCheck : PlanCheck {
    override fun check(subject: PlanSubject): List<DeployProblem> {
        val context = subject.context ?: return emptyList()
        val name = subject.nameOf(subject.descriptor)
        val metamodel = subject.metamodelFile
        val model = subject.modelFile
        return listOfNotNull(
            when {
                metamodel == null -> missing(DeployProblemCodes.NO_METAMODEL, "$name names no domain metamodel that exists.", subject)
                context.metamodel == null ->
                    missing(DeployProblemCodes.NO_METAMODEL, "The metamodel ${subject.nameOf(metamodel)} was not read.", subject)
                else -> null
            },
            when {
                model == null -> missing(DeployProblemCodes.NO_MODEL, "$name names no model that exists.", subject)
                subject.model == null ->
                    missing(DeployProblemCodes.NO_MODEL, "The model ${subject.nameOf(model)} was not read against its metamodel.", subject)
                else -> null
            },
        )
    }

    /** An error with [code] and [message] at the descriptor. */
    private fun missing(code: String, message: String, subject: PlanSubject): DeployProblem =
        DeployProblem(Severity.ERROR, code, message, subject.descriptorLocation)
}
