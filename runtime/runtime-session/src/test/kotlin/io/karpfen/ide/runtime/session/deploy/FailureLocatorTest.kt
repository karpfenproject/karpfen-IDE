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
import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleRefusal
import io.karpfen.ide.runtime.protocol.lifecycle.PhaseKind
import io.karpfen.ide.runtime.protocol.lifecycle.Uploads
import io.karpfen.ide.runtime.protocol.message.ProtocolViolation
import io.karpfen.ide.runtime.protocol.message.ViolationKind
import io.karpfen.ide.runtime.session.plan.PlannedUpload
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import io.karpfen.ide.runtime.session.problem.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers how the failure locator turns the runtime's failures during a deployment into problems. The known texts of the
 * runtime's model parser are located at the object they name. Other parser texts and internal errors are located at the
 * uploaded file, and an unknown attachment at the descriptor. The tests also cover failures of the runtime check,
 * failures without a location, which are described by themselves, and refusals of the environment handle.
 */
class FailureLocatorTest {

    private val locator = FailureLocator(LampPlans.plan(), ENDPOINT)

    private fun objectAt(key: String): SourceLocation? = LampPlans.locations.objectIn(LampPlans.modelFile, key)

    private fun fileOf(upload: PlannedUpload): SourceLocation =
        SourceLocation(upload.file, null, upload.snapshot)

    @Test
    fun `a known text of the model parser points at the declaration of the object it names`() {
        val texts = mapOf(
            "Unknown class type 'Bulbb' for object 'spare'" to "spare",
            "Unknown property 'watts' for class type 'Bulb' in object 'bulb'" to "bulb",
            "Unknown object property 'socket' for class type 'Lamp' in object 'lamp'" to "lamp",
            "Could not resolve reference to object with id 'nowhere' in property 'switch' of object 'lamp'" to "lamp",
        )

        texts.forEach { (text, key) ->
            val problem = locator.locate(DeployStepKind.UPLOAD_MODEL, LampPlans.model, Failures.refused(RuntimeRoute.SET_MODEL, text))

            assertEquals(DeployProblemCodes.RUNTIME_REJECTED to Severity.ERROR, problem.code to problem.severity)
            assertEquals(objectAt(key), problem.location, text)
            assertTrue(text in problem.message && "lamp.kmodel" in problem.message, problem.message)
        }
    }

    @Test
    fun `a known text naming an object the model does not declare points at the model file`() {
        val failure = Failures.refused(RuntimeRoute.SET_MODEL, "Unknown class type 'Bulbb' for object 'attic'")

        val problem = locator.locate(DeployStepKind.UPLOAD_MODEL, LampPlans.model, failure)

        assertEquals(fileOf(LampPlans.model), problem.location)
    }

    @Test
    fun `other parser texts point at the uploaded file`() {
        val cases = listOf(
            Triple(DeployStepKind.UPLOAD_METAMODEL, LampPlans.metamodel, "KMeta content does not define any types"),
            Triple(DeployStepKind.UPLOAD_EVENTS, LampPlans.events, "Unknown target class type 'Pose' for property"),
            Triple(DeployStepKind.ATTACH_STATE_MACHINE, LampPlans.lampMachine, "Exactly one state must be marked as INITIAL, but found 0."),
            Triple(DeployStepKind.ATTACH_STATE_MACHINE, LampPlans.switchMachine, "Operation SET expects 2 arguments, but was called as 1."),
            Triple(DeployStepKind.UPLOAD_MODEL, LampPlans.model, "For input string: \"bright\""),
        )

        cases.forEach { (kind, upload, text) ->
            val problem = locator.locate(kind, upload, Failures.refused(RuntimeRoute.SET_MODEL, text))

            assertEquals(DeployProblemCodes.RUNTIME_REJECTED, problem.code)
            assertEquals(fileOf(upload), problem.location, text)
        }
    }

    @Test
    fun `any other rejection of an upload points at the uploaded file`() {
        val failure = Failures.rejected(RuntimeRoute.SET_MODEL, RuntimeText.METAMODEL_REQUIRED, mapOf("env" to ENVIRONMENT.value))

        val problem = locator.locate(DeployStepKind.UPLOAD_MODEL, LampPlans.model, failure)

        assertEquals(DeployProblemCodes.RUNTIME_REJECTED, problem.code)
        assertEquals(fileOf(LampPlans.model), problem.location)
    }

    @Test
    fun `an internal error on an upload says that the older parser could not read the file and quotes its text`() {
        val text = "Cannot invoke \"org.antlr.v4.runtime.Token.getText()\" because the return value of \"name()\" is null"

        val problem = locator.locate(DeployStepKind.UPLOAD_EVENTS, LampPlans.events, Failures.fault(RuntimeRoute.SET_EVENT_DEFINITIONS, text))

        assertEquals(DeployProblemCodes.RUNTIME_ERROR to Severity.ERROR, problem.code to problem.severity)
        assertTrue("dsl-tools 1.0" in problem.message && "lamp-events.kmeta" in problem.message, problem.message)
        assertTrue("\"$text\"" in problem.message, problem.message)
        assertEquals(fileOf(LampPlans.events), problem.location)
    }

    @Test
    fun `an internal error naming an object of the model points at its declaration`() {
        val failure = Failures.fault(RuntimeRoute.SET_MODEL, "Unknown property 'watts' for class type 'Bulb' in object 'spare'")

        val problem = locator.locate(DeployStepKind.UPLOAD_MODEL, LampPlans.model, failure)

        assertEquals(DeployProblemCodes.RUNTIME_ERROR, problem.code)
        assertEquals(objectAt("spare"), problem.location)
    }

    @Test
    fun `an activation refused for an unknown attachment points at the descriptor`() {
        val failure = Failures.rejected(
            RuntimeRoute.RUN_ENVIRONMENT,
            RuntimeText.UNKNOWN_ATTACHMENT,
            mapOf("env" to ENVIRONMENT.value, "keys" to "wallSwitch"),
        )

        val problem = locator.locate(DeployStepKind.ACTIVATE, null, failure)

        assertEquals(DeployProblemCodes.RUNTIME_REJECTED, problem.code)
        assertEquals(SourceLocation(LampPlans.descriptor, null, null), problem.location)
        assertTrue("wallSwitch" in problem.message, problem.message)
    }

    @Test
    fun `a runtime that cannot be reached or lacks the IDE's routes fails the check`() {
        val unreachable = locator.locate(DeployStepKind.CHECK_RUNTIME, null, Failures.unreachable(RuntimeRoute.HEALTH))
        val lacking = locator.locate(
            DeployStepKind.CHECK_RUNTIME,
            null,
            RuntimeFailure.UnexpectedStatus(RuntimeRoute.MODEL_SNAPSHOT, 404, "Not Found"),
        )
        val foreign = locator.locate(
            DeployStepKind.CHECK_RUNTIME,
            null,
            RuntimeFailure.Rejected(RuntimeRoute.MODEL_SNAPSHOT, 400, RejectionKind.UNCLASSIFIED, "nope", emptyMap()),
        )
        val silent = locator.locate(DeployStepKind.CHECK_RUNTIME, null, Failures.noAnswer(RuntimeRoute.HEALTH))

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
            val problem = locator.locate(kind, null, failure)

            assertEquals(code to failure.describe(), problem.code to problem.message)
            assertNull(problem.location)
        }
    }

    @Test
    fun `an environment the runtime no longer knows is lost, also on an upload`() {
        val problem = locator.locate(DeployStepKind.UPLOAD_MODEL, LampPlans.model, Failures.unknownEnvironment(RuntimeRoute.SET_MODEL))

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
