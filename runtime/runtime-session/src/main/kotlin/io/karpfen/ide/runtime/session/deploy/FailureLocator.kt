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
package io.karpfen.ide.runtime.session.deploy

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.runtime.protocol.api.Reach
import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.catalogue.UploadError
import io.karpfen.ide.runtime.protocol.catalogue.UploadRejection
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleRefusal
import io.karpfen.ide.runtime.session.plan.DeploymentPlan
import io.karpfen.ide.runtime.session.plan.PlannedUpload
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import io.karpfen.ide.runtime.session.problem.SourceLocation

/**
 * Turns the runtime's failures during a deployment into problems. Each problem is located in the workspace where the
 * failure points.
 *
 * - An upload the runtime refused for its content becomes one problem per error the runtime lists, in the order of
 *   the text. Each points at the place of its error in the uploaded text, which is the text the deployment analyzed
 *   and sent. The runtime names only where an error starts, so the range of a problem is that position, empty, and
 *   clamped into the text. Each message says that the runtime refused the file and gives the runtime's message for
 *   the error.
 * - Any other rejection of an upload is one problem at the uploaded file with the runtime's text. A rejection saying
 *   that the environment does not exist is the one exception. It is about the environment, not about the upload.
 * - An internal error of the runtime on an upload is an error of the runtime at the uploaded file, with the runtime's
 *   text.
 * - An activation refused because a machine is attached to a key that the model lacks points at the descriptor.
 * - A failure while checking the runtime means the runtime is unreachable or incompatible.
 * - Every other failure has no location and is described by the failure itself, see [unlocated].
 *
 * @param plan the plan deployed, whose descriptor locates a refused activation.
 * @param endpoint the runtime deployed to, named in the problems.
 */
internal class FailureLocator(private val plan: DeploymentPlan, private val endpoint: RuntimeEndpoint) {

    /** The failed outcome of a step of [kind] for [failure], located in [upload] if the step uploads it. */
    fun locate(kind: DeployStepKind, upload: PlannedUpload?, failure: RuntimeFailure): StepOutcome.Failed =
        when {
            kind == DeployStepKind.CHECK_RUNTIME && failure.reach == Reach.ANSWERED -> single(
                DeployProblemCodes.RUNTIME_INCOMPATIBLE,
                "The runtime at ${endpoint.httpBase} lacks the IDE's routes or is not a karpfen runtime with IDE support: " +
                    failure.describe(),
                null,
                failure,
            )
            upload != null && failure is RuntimeFailure.ServerFault -> single(
                DeployProblemCodes.RUNTIME_ERROR,
                "The runtime failed with an internal error while taking ${upload.fileName}: ${failure.message}",
                fileOf(upload),
                failure,
            )
            upload != null && failure is RuntimeFailure.Rejected && failure.kind != RejectionKind.UNKNOWN_ENVIRONMENT ->
                failure.content?.let { content -> refusedContent(upload, content, failure) } ?: single(
                    DeployProblemCodes.RUNTIME_REJECTED,
                    "The runtime refused ${upload.fileName}: ${failure.message}",
                    fileOf(upload),
                    failure,
                )
            kind == DeployStepKind.ACTIVATE && failure is RuntimeFailure.Rejected && failure.kind == RejectionKind.UNKNOWN_ATTACHMENT ->
                single(
                    DeployProblemCodes.RUNTIME_REJECTED,
                    "The runtime refused the activation: ${failure.message}",
                    SourceLocation(plan.descriptor, null, null),
                    failure,
                )
            else -> StepOutcome.Failed(unlocated(failure), failure)
        }

    /** The problem of a call the environment's handle refused in its phase, without sending it. */
    fun refused(refusal: LifecycleRefusal): DeployProblem =
        when (refusal.phase) {
            is EnvironmentPhase.Lost -> problem(
                DeployProblemCodes.ENVIRONMENT_LOST,
                "The runtime no longer knows the environment: ${refusal.reason}",
                null,
            )
            is EnvironmentPhase.Uncertain -> problem(
                DeployProblemCodes.RUNTIME_NO_ANSWER,
                "An earlier call got no answer, so the environment's phase is uncertain: ${refusal.reason}",
                null,
            )
            else -> problem(
                DeployProblemCodes.RUNTIME_REJECTED,
                "The environment's phase does not allow the step: ${refusal.reason}",
                null,
            )
        }

    /**
     * The problem of [failure] without a location. It is used for every failure that no file or declaration explains,
     * and also for the calls a session makes after the deployment.
     */
    fun unlocated(failure: RuntimeFailure): DeployProblem =
        when (failure) {
            is RuntimeFailure.Unreachable -> problem(
                DeployProblemCodes.RUNTIME_UNREACHABLE,
                "${failure.describe()} The runtime's address is ${endpoint.httpBase}.",
                null,
            )
            is RuntimeFailure.NoAnswer -> problem(DeployProblemCodes.RUNTIME_NO_ANSWER, failure.describe(), null)
            is RuntimeFailure.Rejected ->
                if (failure.kind == RejectionKind.UNKNOWN_ENVIRONMENT) {
                    problem(DeployProblemCodes.ENVIRONMENT_LOST, "${failure.describe()} The runtime may have restarted.", null)
                } else {
                    problem(DeployProblemCodes.RUNTIME_REJECTED, failure.describe(), null)
                }
            is RuntimeFailure.ServerFault -> problem(DeployProblemCodes.RUNTIME_ERROR, failure.describe(), null)
            is RuntimeFailure.UnexpectedStatus, is RuntimeFailure.ProtocolViolated ->
                problem(DeployProblemCodes.RUNTIME_INCOMPATIBLE, failure.describe(), null)
        }

    /**
     * The failed outcome of [upload], which the runtime refused for its [content] with [failure]. It has one problem
     * per error. With one error the problem's message is the summary, otherwise the summary counts the errors.
     */
    private fun refusedContent(upload: PlannedUpload, content: UploadRejection, failure: RuntimeFailure): StepOutcome.Failed {
        val problems = content.errors.map { error ->
            problem(DeployProblemCodes.RUNTIME_REJECTED, "The runtime refused ${upload.fileName}: ${error.message}", placeOf(error, upload))
        }
        val summary = problems.singleOrNull()?.message ?: "The runtime refused ${upload.fileName} with ${problems.size} errors."
        return StepOutcome.Failed(summary, problems, failure)
    }

    /**
     * The place of [error] in the text of [upload]. The runtime counts lines and columns from 1, and the text counts
     * them from 0. A line or a column beyond the text is clamped to the end of the text or of its line.
     */
    private fun placeOf(error: UploadError, upload: PlannedUpload): SourceLocation {
        val offset = upload.snapshot.offsetOf(error.line - 1, error.column - 1)
        return SourceLocation(upload.file, TextRange.empty(offset), upload.snapshot)
    }

    /** The file of [upload] as a whole. */
    private fun fileOf(upload: PlannedUpload): SourceLocation = SourceLocation(upload.file, null, upload.snapshot)

    /** The failed outcome of [failure] that is one ERROR with [code], [message] and [location]. */
    private fun single(code: String, message: String, location: SourceLocation?, failure: RuntimeFailure): StepOutcome.Failed =
        StepOutcome.Failed(problem(code, message, location), failure)

    /** An ERROR with [code], [message] and [location]. */
    private fun problem(code: String, message: String, location: SourceLocation?): DeployProblem =
        DeployProblem(Severity.ERROR, code, message, location)
}
