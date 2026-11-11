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
import io.karpfen.ide.runtime.protocol.api.Reach
import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleRefusal
import io.karpfen.ide.runtime.session.plan.DeploymentPlan
import io.karpfen.ide.runtime.session.plan.PlannedUpload
import io.karpfen.ide.runtime.session.plan.UploadRole
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import io.karpfen.ide.runtime.session.problem.SourceLocation

/**
 * Turns the runtime's failures during a deployment into problems. Each problem is located in the workspace where the
 * failure points.
 *
 * - A rejection of an uploaded text, or an internal error while reading it, points at that upload's file. When the
 *   text is a known text of the runtime's parser that names an object, it points at that object's declaration in the
 *   model instead. A rejection saying that the environment does not exist is the one exception. It is about the
 *   environment, not about the upload.
 * - An internal error on an upload says that the runtime's older parser could not read the file, and it quotes the
 *   parser's text.
 * - An activation refused because a machine is attached to a key that the model lacks points at the descriptor.
 * - A failure while checking the runtime means the runtime is unreachable or incompatible.
 * - Every other failure has no location and is described by the failure itself, see [unlocated].
 *
 * The parser texts are those of the runtime's dsl-tools 1.0 converters `KmetaDSLConverter`, `KmodelDSLConverter` and
 * `KstatesDSLConverter` in `dsl/textual`, at the commit the runtime pins.
 *
 * @param plan the plan deployed, whose declarations locate the subjects of the parser's texts.
 * @param endpoint the runtime deployed to, named in the problems.
 */
internal class FailureLocator(private val plan: DeploymentPlan, private val endpoint: RuntimeEndpoint) {

    /** The problem of [failure] in a step of [kind], which uploads [upload] if it is an upload step. */
    fun locate(kind: DeployStepKind, upload: PlannedUpload?, failure: RuntimeFailure): DeployProblem =
        when {
            kind == DeployStepKind.CHECK_RUNTIME && failure.reach == Reach.ANSWERED -> problem(
                DeployProblemCodes.RUNTIME_INCOMPATIBLE,
                "The runtime at ${endpoint.httpBase} lacks the IDE's routes or is not a karpfen runtime with IDE support: " +
                    failure.describe(),
                null,
            )
            upload != null && failure is RuntimeFailure.ServerFault -> problem(
                DeployProblemCodes.RUNTIME_ERROR,
                "The runtime's older parser (dsl-tools 1.0) could not read ${upload.fileName}: \"${failure.message}\"",
                subjectOf(failure.message, upload) ?: fileOf(upload),
            )
            upload != null && failure is RuntimeFailure.Rejected && failure.kind != RejectionKind.UNKNOWN_ENVIRONMENT -> problem(
                DeployProblemCodes.RUNTIME_REJECTED,
                "The runtime refused ${upload.fileName}: ${failure.message}",
                subjectOf(failure.message, upload) ?: fileOf(upload),
            )
            kind == DeployStepKind.ACTIVATE && failure is RuntimeFailure.Rejected && failure.kind == RejectionKind.UNKNOWN_ATTACHMENT ->
                problem(
                    DeployProblemCodes.RUNTIME_REJECTED,
                    "The runtime refused the activation: ${failure.message}",
                    SourceLocation(plan.descriptor, null, null),
                )
            else -> unlocated(failure)
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

    /** The declaration of the subject a known parser [text] names, in the file of [upload], or null. */
    private fun subjectOf(text: String, upload: PlannedUpload): SourceLocation? {
        if (upload.role != UploadRole.MODEL) return null
        val key = OBJECT_TEXTS.firstNotNullOfOrNull { pattern -> pattern.matchEntire(text)?.groupValues?.last() } ?: return null
        return plan.locations.objectIn(upload.file, key)
    }

    /** The file of [upload] as a whole. */
    private fun fileOf(upload: PlannedUpload): SourceLocation = SourceLocation(upload.file, null, upload.snapshot)

    /** An ERROR with [code], [message] and [location]. */
    private fun problem(code: String, message: String, location: SourceLocation?): DeployProblem =
        DeployProblem(Severity.ERROR, code, message, location)

    private companion object {
        /** The texts of the runtime's model parser that name an object, with the object's key as their last group. */
        private val OBJECT_TEXTS = listOf(
            Regex("Unknown class type '(.*)' for object '(.*)'"),
            Regex("Unknown property '(.*)' for class type '(.*)' in object '(.*)'"),
            Regex("Unknown object property '(.*)' for class type '(.*)' in object '(.*)'"),
            Regex("Could not resolve reference to object with id '(.*)' in property '(.*)' of object '(.*)'"),
        )
    }
}
