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
import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.catalogue.RejectedUpload
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.catalogue.UploadError
import io.karpfen.ide.runtime.protocol.catalogue.UploadRejection
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleRefusal
import io.karpfen.ide.runtime.protocol.lifecycle.PhaseKind
import io.karpfen.ide.runtime.protocol.lifecycle.Uploads
import io.karpfen.ide.runtime.protocol.message.ProtocolViolation
import io.karpfen.ide.runtime.protocol.message.ViolationKind
import io.karpfen.ide.runtime.session.plan.PlannedUpload
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import io.karpfen.ide.runtime.session.problem.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers how the failure locator turns the runtime's failures during a deployment into problems. An upload refused for
 * the errors in its text becomes one problem per error at its place in the uploaded text, clamped into the text. Other
 * rejections and internal errors of an upload are located at the uploaded file, and an unknown attachment at the
 * descriptor. The tests also cover failures of the runtime check, failures without a location, which are described by
 * themselves, and refusals of the environment handle.
 */
class FailureLocatorTest {

    private val locator = FailureLocator(LampPlans.plan(), ENDPOINT)

    private fun fileOf(upload: PlannedUpload): SourceLocation =
        SourceLocation(upload.file, null, upload.snapshot)

    /** The empty range at [offset] of the model text. */
    private fun modelAt(offset: Int): SourceLocation =
        SourceLocation(LampPlans.modelFile, TextRange.empty(offset), LampPlans.modelSnapshot)

    /** The one problem of the failed outcome of [failure] in a step of [kind] that uploads [upload]. */
    private fun problemOf(kind: DeployStepKind, upload: PlannedUpload?, failure: RuntimeFailure): DeployProblem =
        locator.locate(kind, upload, failure).problems.single()

    @Test
    fun `a refused upload becomes one problem per error at the place of each error in the uploaded text`() {
        val rejection = UploadRejection(
            RejectedUpload.Model,
            listOf(
                UploadError(1, 8, "Unknown class type 'Lmap' of object 'lamp'", "kmodel.unknown-class-type"),
                UploadError(1, 52, "Unknown class type 'Bulbb' of object 'spare'", "kmodel.unknown-class-type"),
            ),
        )
        val failure = Failures.refused(RuntimeRoute.SET_MODEL, rejection)

        val outcome = locator.locate(DeployStepKind.UPLOAD_MODEL, LampPlans.model, failure)

        assertEquals(
            listOf(
                DeployProblem(
                    Severity.ERROR,
                    DeployProblemCodes.RUNTIME_REJECTED,
                    "The runtime refused lamp.kmodel: Unknown class type 'Lmap' of object 'lamp'",
                    modelAt(7),
                ),
                DeployProblem(
                    Severity.ERROR,
                    DeployProblemCodes.RUNTIME_REJECTED,
                    "The runtime refused lamp.kmodel: Unknown class type 'Bulbb' of object 'spare'",
                    modelAt(51),
                ),
            ),
            outcome.problems,
        )
        assertEquals("The runtime refused lamp.kmodel with 2 errors.", outcome.summary)
        assertEquals(failure, outcome.failure)
    }

    @Test
    fun `a refused upload with one error is summarized by its problem`() {
        val failure = Failures.refused(RuntimeRoute.SET_STATE_MACHINE, RejectedUpload.StateMachine("lamp"), "Unknown state 'of'", "kstates.unknown-state")

        val outcome = locator.locate(DeployStepKind.ATTACH_STATE_MACHINE, LampPlans.lampMachine, failure)

        val problem = outcome.problems.single()
        assertEquals("The runtime refused lamp.kstates: Unknown state 'of'", problem.message)
        assertEquals(SourceLocation(LampPlans.lampMachine.file, TextRange.empty(0), LampPlans.lampMachine.snapshot), problem.location)
        assertEquals(problem.message, outcome.summary)
    }

    @Test
    fun `a place beyond the uploaded text is clamped to the end of its line or of the text`() {
        val text = LampPlans.MODEL_TEXT
        val rejection = UploadRejection(
            RejectedUpload.Model,
            listOf(
                UploadError(1, text.length + 40, "Expected '}'", "syntax.error"),
                UploadError(7, 3, "Unexpected end of the text", "syntax.error"),
            ),
        )

        val problems = locator.locate(DeployStepKind.UPLOAD_MODEL, LampPlans.model, Failures.refused(RuntimeRoute.SET_MODEL, rejection)).problems

        assertEquals(listOf(modelAt(text.length), modelAt(text.length)), problems.map { it.location })
    }

    @Test
    fun `a rejection of an upload with a text the client does not know is one problem at the uploaded file`() {
        val cases = listOf(
            Triple(DeployStepKind.UPLOAD_METAMODEL, LampPlans.metamodel, RuntimeRoute.SET_METAMODEL),
            Triple(DeployStepKind.UPLOAD_EVENTS, LampPlans.events, RuntimeRoute.SET_EVENT_DEFINITIONS),
            Triple(DeployStepKind.UPLOAD_MODEL, LampPlans.model, RuntimeRoute.SET_MODEL),
            Triple(DeployStepKind.ATTACH_STATE_MACHINE, LampPlans.switchMachine, RuntimeRoute.SET_STATE_MACHINE),
        )

        cases.forEach { (kind, upload, route) ->
            val outcome = locator.locate(kind, upload, Failures.unknownText(route, "Something went wrong"))

            val problem = outcome.problems.single()
            assertEquals(DeployProblemCodes.RUNTIME_REJECTED, problem.code)
            assertEquals("The runtime refused ${upload.fileName}: Something went wrong", problem.message)
            assertEquals(fileOf(upload), problem.location)
            assertEquals(problem.message, outcome.summary)
        }
    }

    @Test
    fun `any other rejection of an upload points at the uploaded file`() {
        val failure = Failures.rejected(RuntimeRoute.SET_MODEL, RuntimeText.METAMODEL_REQUIRED, mapOf("env" to ENVIRONMENT.value))

        val problem = problemOf(DeployStepKind.UPLOAD_MODEL, LampPlans.model, failure)

        assertEquals(DeployProblemCodes.RUNTIME_REJECTED, problem.code)
        assertEquals(fileOf(LampPlans.model), problem.location)
    }

    @Test
    fun `an internal error on an upload is an error of the runtime at the uploaded file with the runtime's text`() {
        val text = "Cannot invoke \"String.length()\" because \"name\" is null"

        val problem = problemOf(DeployStepKind.UPLOAD_EVENTS, LampPlans.events, Failures.fault(RuntimeRoute.SET_EVENT_DEFINITIONS, text))

        assertEquals(DeployProblemCodes.RUNTIME_ERROR to Severity.ERROR, problem.code to problem.severity)
        assertEquals("The runtime failed with an internal error while taking lamp-events.kmeta: $text", problem.message)
        assertEquals(fileOf(LampPlans.events), problem.location)
    }

    @Test
    fun `an activation refused for an unknown attachment points at the descriptor`() {
        val failure = Failures.rejected(
            RuntimeRoute.RUN_ENVIRONMENT,
            RuntimeText.UNKNOWN_ATTACHMENT,
            mapOf("env" to ENVIRONMENT.value, "keys" to "wallSwitch"),
        )

        val problem = problemOf(DeployStepKind.ACTIVATE, null, failure)

        assertEquals(DeployProblemCodes.RUNTIME_REJECTED, problem.code)
        assertEquals(SourceLocation(LampPlans.descriptor, null, null), problem.location)
        assertTrue("wallSwitch" in problem.message, problem.message)
    }

    @Test
    fun `a runtime that cannot be reached or lacks the IDE's routes fails the check`() {
        val unreachable = problemOf(DeployStepKind.CHECK_RUNTIME, null, Failures.unreachable(RuntimeRoute.HEALTH))
        val lacking = problemOf(
            DeployStepKind.CHECK_RUNTIME,
            null,
            RuntimeFailure.UnexpectedStatus(RuntimeRoute.MODEL_SNAPSHOT, 404, "Not Found"),
        )
        val foreign = problemOf(
            DeployStepKind.CHECK_RUNTIME,
            null,
            RuntimeFailure.Rejected(RuntimeRoute.MODEL_SNAPSHOT, 400, RejectionKind.UNCLASSIFIED, "nope", emptyMap()),
        )
        val silent = problemOf(DeployStepKind.CHECK_RUNTIME, null, Failures.noAnswer(RuntimeRoute.HEALTH))

        assertEquals(DeployProblemCodes.RUNTIME_UNREACHABLE, unreachable.code)
        assertTrue("http://127.0.0.1:8080" in unreachable.message, unreachable.message)
        assertEquals(DeployProblemCodes.RUNTIME_INCOMPATIBLE, lacking.code)
        assertTrue("http://127.0.0.1:8080" in lacking.message, lacking.message)
        assertEquals(DeployProblemCodes.RUNTIME_INCOMPATIBLE, foreign.code)
        assertEquals(DeployProblemCodes.RUNTIME_NO_ANSWER, silent.code)
        assertEquals(listOf(null, null, null, null), listOf(unreachable, lacking, foreign, silent).map { it.location })
    }

    @Test
    fun `other failures have no location and are described by the failure`() {
        val cases = listOf(
            Triple(DeployStepKind.SET_TICK_DELAY, Failures.noAnswer(RuntimeRoute.SET_TICK_DELAY), DeployProblemCodes.RUNTIME_NO_ANSWER),
            Triple(
                DeployStepKind.ACTIVATE,
                Failures.rejected(RuntimeRoute.RUN_ENVIRONMENT, RuntimeText.ALREADY_ACTIVE, mapOf("env" to ENVIRONMENT.value)),
                DeployProblemCodes.RUNTIME_REJECTED,
            ),
            Triple(DeployStepKind.START, Failures.fault(RuntimeRoute.START_ENVIRONMENT, "Unexpected error"), DeployProblemCodes.RUNTIME_ERROR),
            Triple(
                DeployStepKind.START,
                RuntimeFailure.UnexpectedStatus(RuntimeRoute.START_ENVIRONMENT, 405, "Method Not Allowed"),
                DeployProblemCodes.RUNTIME_INCOMPATIBLE,
            ),
            Triple(
                DeployStepKind.CREATE,
                RuntimeFailure.ProtocolViolated(
                    RuntimeRoute.CREATE_ENVIRONMENT,
                    ProtocolViolation(ViolationKind.entries.first(), "not an environment key", "envy"),
                ),
                DeployProblemCodes.RUNTIME_INCOMPATIBLE,
            ),
        )

        cases.forEach { (kind, failure, code) ->
            val problem = problemOf(kind, null, failure)

            assertEquals(code to failure.describe(), problem.code to problem.message)
            assertNull(problem.location)
        }
    }

    @Test
    fun `an environment the runtime no longer knows is lost, also on an upload`() {
        val problem = problemOf(DeployStepKind.UPLOAD_MODEL, LampPlans.model, Failures.unknownEnvironment(RuntimeRoute.SET_MODEL))

        assertEquals(DeployProblemCodes.ENVIRONMENT_LOST, problem.code)
        assertNull(problem.location)
    }

    @Test
    fun `a refusal of the environment handle becomes a problem by the phase it was refused in`() {
        val lost = LifecycleRefusal(LifecycleOperation.ACTIVATE, EnvironmentPhase.Lost("restarted"), "the runtime forgot it")
        val uncertain = LifecycleRefusal(
            LifecycleOperation.START,
            EnvironmentPhase.Uncertain(setOf(PhaseKind.CONFIGURING, PhaseKind.ACTIVATED), "no answer", Uploads.NONE),
            "resolve the phase first",
        )
        val refused = LifecycleRefusal(LifecycleOperation.SET_MODEL, EnvironmentPhase.Activated, "frozen at activation")

        assertEquals(DeployProblemCodes.ENVIRONMENT_LOST, locator.refused(lost).code)
        assertEquals(DeployProblemCodes.RUNTIME_NO_ANSWER, locator.refused(uncertain).code)
        assertEquals(DeployProblemCodes.RUNTIME_REJECTED, locator.refused(refused).code)
        assertTrue("frozen at activation" in locator.refused(refused).message)
    }
}
