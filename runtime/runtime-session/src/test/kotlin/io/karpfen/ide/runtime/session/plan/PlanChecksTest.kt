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

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.runtime.protocol.conformance.DescriptorTiming
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers each finding code of the planner's gate and preflight checks on a workspace edited to provoke it. It checks
 * the code's severity, whether it blocks, and where it points.
 */
class PlanChecksTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()

    private val lampDescriptor: Path get() = root.resolve("lamp/lamp.kenv.json")
    private val lampModel: Path get() = root.resolve("lamp/lamp.kmodel")

    @Test
    fun `a descriptor that cannot be read resolves to no context`() = runTest(timeout = 60.seconds) {
        val descriptor = DescriptorExamples.copyExplicitContext(root.resolve("robot"), DescriptorExamples.INVALID_JSON)

        val problem = PlanningWorkspaces(this, root).plan(descriptor).blocked().single()

        assertEquals(DeployProblemCodes.NO_CONTEXT to Severity.ERROR, problem.code to problem.severity)
        assertEquals(descriptor, problem.location?.file)
    }

    @Test
    fun `an error in a context file blocks at its first error with the count`() = runTest(timeout = 60.seconds) {
        val workspaces = PlanningWorkspaces(this, root)
        workspaces.copy(SynthesizedModel.LAMP)
        lampModel.replaceText("prop(\"mode\") -> \"off\"", "prop(\"mode\") -> \"off\"\n        prop(\"nothing\") -> \"1\"")

        val problem = workspaces.plan(lampDescriptor).blocked().single()

        assertEquals(DeployProblemCodes.CONTEXT_ERRORS to Severity.ERROR, problem.code to problem.severity)
        assertTrue(problem.message.startsWith("lamp.kmodel has an error"), problem.message)
        val location = assertNotNull(problem.location)
        assertEquals(lampModel, location.file)
        assertTrue(assertNotNull(location.snapshot).substring(assertNotNull(location.range)).contains("nothing"))
    }

    @Test
    fun `a missing metamodel blocks with no metamodel and no model`() = runTest(timeout = 60.seconds) {
        val workspaces = PlanningWorkspaces(this, root)
        workspaces.copy(SynthesizedModel.LAMP)
        lampDescriptor.replaceText("\"lamp.kmeta\"", "\"missing.kmeta\"")

        val codes = workspaces.plan(lampDescriptor).blocked().codes()

        assertTrue(DeployProblemCodes.NO_METAMODEL to Severity.ERROR in codes, "$codes")
        assertTrue(DeployProblemCodes.NO_MODEL to Severity.ERROR in codes, "$codes")
        assertTrue(DeployProblemCodes.CONTEXT_ERRORS to Severity.ERROR in codes, "the descriptor names a missing file: $codes")
    }

    @Test
    fun `a missing model blocks with no model`() = runTest(timeout = 60.seconds) {
        val workspaces = PlanningWorkspaces(this, root)
        workspaces.copy(SynthesizedModel.LAMP)
        lampDescriptor.replaceText("\"lamp.kmodel\"", "\"missing.kmodel\"")

        val codes = workspaces.plan(lampDescriptor).blocked().codes()

        assertTrue(DeployProblemCodes.NO_MODEL to Severity.ERROR in codes, "$codes")
        assertTrue(DeployProblemCodes.NO_METAMODEL to Severity.ERROR !in codes, "$codes")
    }

    @Test
    fun `a machine attached to a key the model lacks blocks at the descriptor`() = runTest(timeout = 60.seconds) {
        val workspaces = PlanningWorkspaces(this, root)
        workspaces.copy(SynthesizedModel.LAMP)
        lampDescriptor.replaceText("\"attachedTo\": \"lamp\"", "\"attachedTo\": \"nobody\"")

        val problem = workspaces.plan(lampDescriptor).blocked().single()

        assertEquals(DeployProblemCodes.UNKNOWN_ATTACHMENT to Severity.ERROR, problem.code to problem.severity)
        assertTrue("'nobody'" in problem.message, problem.message)
        assertEquals(lampDescriptor, problem.location?.file)
    }

    @Test
    fun `two machines attached to one key block`() = runTest(timeout = 60.seconds) {
        val workspaces = PlanningWorkspaces(this, root)
        workspaces.copy(SynthesizedModel.LAMP)
        lampDescriptor.replaceText("\"attachedTo\": \"wallSwitch\"", "\"attachedTo\": \"lamp\"")

        val findings = workspaces.plan(lampDescriptor).blocked()

        assertEquals(
            listOf(DeployProblemCodes.DUPLICATE_ATTACHMENT to Severity.ERROR, DeployProblemCodes.ATTACHMENT_TYPE to Severity.WARNING),
            findings.codes(),
        )
        assertTrue("lamp.kstates, switch.kstates" in findings.first().message, findings.first().message)
    }

    @Test
    fun `a machine attached to an object of another type is a warning at the machine`() = runTest(timeout = 60.seconds) {
        val workspaces = PlanningWorkspaces(this, root)
        workspaces.copy(SynthesizedModel.LAMP)
        lampDescriptor.replaceText("\"attachedTo\": \"lamp\"", "\"attachedTo\": \"bulb\"")

        val plan = workspaces.plan(lampDescriptor).ready()

        val problem = plan.findings.single()
        assertEquals(DeployProblemCodes.ATTACHMENT_TYPE to Severity.WARNING, problem.code to problem.severity)
        assertTrue("'Lamp'" in problem.message && "'Bulb'" in problem.message, problem.message)
        assertEquals(root.resolve("lamp/lamp.kstates"), problem.location?.file)
        assertEquals(listOf("Bulb" to "Lamp", "Switch" to "Switch"), plan.attachments.map { it.objectType to it.machineType })
    }

    @Test
    fun `a tick delay below zero or above the largest Int blocks`() = runTest(timeout = 60.seconds) {
        for (tickDelay in listOf(-1L, Int.MAX_VALUE + 1L)) {
            val workspaces = PlanningWorkspaces(this, root.resolve("t$tickDelay"))
            val descriptor = workspaces.copy(SynthesizedModel.LAMP, DescriptorTiming(tickDelayMs = tickDelay))

            val problem = workspaces.plan(descriptor).blocked().single()

            assertEquals(DeployProblemCodes.TICK_DELAY_RANGE to Severity.ERROR, problem.code to problem.severity)
            assertTrue("$tickDelay ms" in problem.message, problem.message)
        }
    }

    @Test
    fun `the largest Int is a tick delay the runtime accepts`() = runTest(timeout = 60.seconds) {
        val workspaces = PlanningWorkspaces(this, root)
        val descriptor = workspaces.copy(SynthesizedModel.LAMP, DescriptorTiming(tickDelayMs = Int.MAX_VALUE.toLong()))

        assertEquals(Int.MAX_VALUE, workspaces.plan(descriptor).ready().configuration.tickDelayMs)
    }

    @Test
    fun `a tick delay of zero is a warning`() = runTest(timeout = 60.seconds) {
        val workspaces = PlanningWorkspaces(this, root)
        val descriptor = workspaces.copy(SynthesizedModel.LAMP, DescriptorTiming(tickDelayMs = 0))

        val plan = workspaces.plan(descriptor).ready()

        assertEquals(listOf(DeployProblemCodes.TICK_DELAY_ZERO to Severity.WARNING), plan.findings.codes())
        assertEquals(0, plan.configuration.tickDelayMs)
    }

    @Test
    fun `a time to live of zero is an info and a negative one a warning`() = runTest(timeout = 60.seconds) {
        val zero = PlanningWorkspaces(this, root.resolve("zero"))
        val negative = PlanningWorkspaces(this, root.resolve("negative"))

        val zeroPlan = zero.plan(zero.copy(SynthesizedModel.LAMP, DescriptorTiming(eventTtlMs = 0))).ready()
        val negativePlan = negative.plan(negative.copy(SynthesizedModel.LAMP, DescriptorTiming(eventTtlMs = -5))).ready()

        assertEquals(listOf(DeployProblemCodes.EVENT_TTL_NEVER_EXPIRES to Severity.INFO), zeroPlan.findings.codes())
        assertEquals(listOf(DeployProblemCodes.EVENT_TTL_NEVER_EXPIRES to Severity.WARNING), negativePlan.findings.codes())
        assertEquals(-5L, negativePlan.configuration.eventTtlMs)
    }

    @Test
    fun `a reference loop blocks`() = runTest(timeout = 60.seconds) {
        val workspaces = PlanningWorkspaces(this, root)
        val descriptor = workspaces.copy(SynthesizedModel.RING)

        assertEquals(listOf(DeployProblemCodes.REFERENCE_LOOP to Severity.ERROR), workspaces.plan(descriptor).blocked().codes())
    }

    @Test
    fun `a loop through an object without a key blocks at the keyed object on it`() = runTest(timeout = 60.seconds) {
        val folder = root.resolve("chain")
        Files.createDirectories(folder)
        Files.writeString(folder.resolve("chain.kmeta"), "type \"Node\" \"A node\" {\n    has(\"inner\", \"Node\")\n    knows(\"next\", \"Node\")\n}\n")
        Files.writeString(
            folder.resolve("chain.kmodel"),
            "make object \"a\":\"Node\" {\n    has(\"inner\") -> make object \"\":\"Node\" {\n        knows(\"next\") -> \"a\"\n    }\n}\n",
        )
        val descriptor = folder.resolve("chain.kenv.json")
        Files.writeString(descriptor, "{\n  \"metamodel\": \"chain.kmeta\",\n  \"model\": \"chain.kmodel\"\n}\n")

        val problem = PlanningWorkspaces(this, root).plan(descriptor).blocked().single()

        assertEquals(DeployProblemCodes.REFERENCE_LOOP to Severity.ERROR, problem.code to problem.severity)
        assertTrue("'a'" in problem.message && "without a key" in problem.message, problem.message)
        assertEquals(folder.resolve("chain.kmodel"), problem.location?.file)
    }

    @Test
    fun `objects sharing a key are a warning at the first of them`() = runTest(timeout = 60.seconds) {
        val workspaces = PlanningWorkspaces(this, root)
        workspaces.copy(SynthesizedModel.LAMP)
        lampModel.replaceText("make object \"spare\":\"Bulb\"", "make object \"bulb\":\"Bulb\"")

        val plan = workspaces.plan(lampDescriptor).ready()

        val problem = plan.findings.single()
        assertEquals(DeployProblemCodes.DUPLICATE_OBJECT_KEY to Severity.WARNING, problem.code to problem.severity)
        assertTrue("2 objects share the key 'bulb'" in problem.message, problem.message)
        assertEquals(lampModel, problem.location?.file)
        assertEquals(setOf("living room", "lamp", "bulb", "wallSwitch"), plan.modelObjectIds)
    }

    @Test
    fun `an event domain named like an attached key is a warning`() = runTest(timeout = 60.seconds) {
        val workspaces = PlanningWorkspaces(this, root)
        workspaces.copy(SynthesizedModel.LAMP)
        lampModel.replaceText("wallSwitch", "switch")
        lampDescriptor.replaceText("\"wallSwitch\"", "\"switch\"")

        val plan = workspaces.plan(lampDescriptor).ready()

        assertEquals(listOf(DeployProblemCodes.DOMAIN_IS_ATTACHMENT to Severity.WARNING), plan.findings.codes())
        assertTrue("'switch'" in plan.findings.single().message)
    }

    @Test
    fun `an observe key, the runtime's packages and bridges are infos`() = runTest(timeout = 60.seconds) {
        val workspaces = PlanningWorkspaces(this, root)
        workspaces.copy(SynthesizedModel.LAMP)
        lampDescriptor.replaceText(
            "\"model\": \"lamp.kmodel\",",
            "\"model\": \"lamp.kmodel\",\n  \"observe\": { \"objects\": \"*\" },\n  \"runtime\": { \"image\": \"karpfen-runtime:latest\" },",
        )

        val plan = workspaces.plan(lampDescriptor).ready()

        assertEquals(
            listOf(DeployProblemCodes.OBSERVE_SUPERSEDED to Severity.INFO, DeployProblemCodes.HOSTING_IGNORED to Severity.INFO),
            plan.findings.codes(),
        )
        assertTrue("runtime.image" in plan.findings.last().message, plan.findings.last().message)
    }
}
