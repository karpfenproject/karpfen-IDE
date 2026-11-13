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
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.workspace.fixtures.openDocument
import io.karpfen.ide.core.workspace.fixtures.settle
import io.karpfen.ide.runtime.protocol.catalogue.FeatureName
import io.karpfen.ide.runtime.protocol.conformance.DescriptorTiming
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers [DeploymentPlanner] over real workspaces. The single robot and the lamp are planned with their uploads in
 * order from the analyzed snapshots. Their attachments, domains and configuration come from the descriptor or the
 * defaults. The ring is blocked by its reference loop. The gate reads the diagnostics of the analyzed snapshots rather
 * than those of a newer editor text. Unsaved files block, and `_` is kept as an object key. A plan lists the runtime
 * features that its machines need.
 */
class DeploymentPlannerTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()

    @Test
    fun `the single robot uploads its four files in order with its attachment, domain and configuration`() = runTest(timeout = 60.seconds) {
        val descriptor = DescriptorExamples.copyExplicitContext(root.resolve("robot"))
        val analysis = PlanningWorkspaces(this, root).analyze()

        val plan = DeploymentPlanner().plan(analysis, descriptor, emptySet()).ready()

        assertEquals("single_robot", plan.name)
        assertEquals(descriptor, plan.descriptor)
        assertEquals(analysis.generation, plan.generation)
        assertEquals(
            listOf(
                UploadRole.METAMODEL to "cleaning_robot.kmeta",
                UploadRole.EVENTS to "EVENTS.kmeta",
                UploadRole.MODEL to "cleaning_robot.kmodel",
                UploadRole.STATE_MACHINE to "cleaning_robot.kstates",
            ),
            plan.uploads.map { it.role to it.fileName },
        )
        plan.uploads.forEach { assertSame(analysis.file(it.file)?.snapshot, it.snapshot, "the analyzed snapshot of ${it.fileName}") }
        assertEquals(listOf(AttachedMachine(DescriptorExamples.ATTACHED_TO, plan.machines.single().file, "Robot", "Robot")), plan.attachments)
        assertEquals(listOf("public"), plan.domains)
        assertEquals(DeployConfiguration(50, 3000), plan.configuration)
        assertEquals(23, plan.modelObjectIds.size)
        assertTrue("APB 2101" in plan.modelObjectIds && "turtle" in plan.modelObjectIds)
        assertEquals(
            listOf(DeployProblemCodes.OBSERVE_SUPERSEDED to Severity.INFO, DeployProblemCodes.HOSTING_IGNORED to Severity.INFO),
            plan.findings.codes(),
        )
    }

    @Test
    fun `the lamp plans both machines in descriptor order with its declared domains and the descriptor's timing`() = runTest(timeout = 60.seconds) {
        val workspaces = PlanningWorkspaces(this, root)
        val descriptor = workspaces.copy(SynthesizedModel.LAMP, DescriptorTiming(tickDelayMs = 20, eventTtlMs = 500))

        val plan = workspaces.plan(descriptor).ready()

        assertEquals(
            listOf("lamp.kmeta", "lamp-events.kmeta", "lamp.kmodel", "lamp.kstates", "switch.kstates"),
            plan.uploads.map { it.fileName },
        )
        assertEquals(listOf(null, null, null, "lamp", "wallSwitch"), plan.uploads.map { it.attachedTo })
        assertEquals(SynthesizedModels.text(SynthesizedModel.LAMP, "lamp.kmodel"), plan.model.snapshot.text)
        assertEquals(listOf("lamp" to "Lamp", "wallSwitch" to "Switch"), plan.attachments.map { it.attachedTo to it.objectType })
        assertEquals(listOf("Lamp", "Switch"), plan.attachments.map { it.machineType })
        assertEquals(listOf("service", "switch"), plan.domains)
        assertEquals(DeployConfiguration(20, 500), plan.configuration)
        assertEquals(setOf("living room", "lamp", "bulb", "spare", "wallSwitch"), plan.modelObjectIds)
        assertEquals(emptyList(), plan.findings)
    }

    @Test
    fun `a plan switches on the runtime features its machines need, and the lamp needs none`() = runTest(timeout = 60.seconds) {
        val workspaces = PlanningWorkspaces(this, root)
        val descriptor = workspaces.copy(SynthesizedModel.LAMP)
        assertEquals(emptyList(), workspaces.plan(descriptor).ready().features)

        root.resolve("lamp/lamp.kstates").replaceText("""        STATE "on" {""", """        SHALLOW HISTORY STATE "on" {""")
        assertEquals(listOf(FeatureName.HISTORY), workspaces.plan(descriptor).ready().features)

        root.resolve("lamp/switch.kstates").replaceText("""        STATE "up" {""", """        STATE "up" {""" + "\n" + TIMEOUT)
        assertEquals(listOf(FeatureName.HISTORY, FeatureName.TICK_LIMIT), workspaces.plan(descriptor).ready().features)
    }

    @Test
    fun `a descriptor without timing keys deploys with the defaults of the descriptor format`() = runTest(timeout = 60.seconds) {
        val workspaces = PlanningWorkspaces(this, root)
        val descriptor = workspaces.copy(SynthesizedModel.LAMP)

        assertEquals(DeployConfiguration(50, 3000), workspaces.plan(descriptor).ready().configuration)
        assertEquals(DeployConfiguration.DEFAULT, workspaces.plan(descriptor).ready().configuration)
    }

    @Test
    fun `the declarations of the uploaded files locate objects, types and states`() = runTest(timeout = 60.seconds) {
        val workspaces = PlanningWorkspaces(this, root)
        val descriptor = workspaces.copy(SynthesizedModel.LAMP)

        val plan = workspaces.plan(descriptor).ready()

        val spare = assertNotNull(plan.locations.objectIn(plan.model.file, "spare"))
        assertSame(plan.model.snapshot, spare.snapshot)
        assertTrue(plan.model.snapshot.substring(assertNotNull(spare.range)).contains("spare"))
        val bulb = assertNotNull(plan.locations.typeIn(plan.metamodel.file, "Bulb"))
        assertTrue(plan.metamodel.snapshot.substring(assertNotNull(bulb.range)).contains("Bulb"))
        val fullPower = assertNotNull(plan.locations.stateIn(plan.machines.first().file, "full power"))
        assertTrue(plan.machines.first().snapshot.substring(assertNotNull(fullPower.range)).contains("full power"))
        assertEquals(null, plan.locations.objectIn(plan.model.file, "nobody"))
    }

    @Test
    fun `the ring is blocked by its reference loop at the declaration of the first object found on it`() = runTest(timeout = 60.seconds) {
        val workspaces = PlanningWorkspaces(this, root)
        val descriptor = workspaces.copy(SynthesizedModel.RING)

        val findings = workspaces.plan(descriptor).blocked()

        val loop = findings.single { it.code == DeployProblemCodes.REFERENCE_LOOP }
        assertEquals(Severity.ERROR, loop.severity)
        assertTrue("'a'" in loop.message, loop.message)
        val location = assertNotNull(loop.location)
        assertEquals(root.resolve("ring/ring.kmodel"), location.file)
        assertTrue(assertNotNull(location.snapshot).substring(assertNotNull(location.range)).contains("a"))
    }

    @Test
    fun `the gate reads the analyzed snapshot even when the editor holds a newer text with an error`() = runTest(timeout = 60.seconds) {
        val workspaces = PlanningWorkspaces(this, root)
        val descriptor = workspaces.copy(SynthesizedModel.LAMP)
        val model = root.resolve("lamp/lamp.kmodel")
        val workspace = workspaces.open()
        val document = workspace.openDocument(model)
        val analyzed = workspace.awaitAnalysis()

        document.session.text.apply(listOf(TextEdit.insert(0, "make object {\n")), EditOrigin.Typing)
        settle()

        assertTrue(assertNotNull(workspace.problems.value.of(model)).errorCount > 0, "the editor text has an error")
        val plan = DeploymentPlanner().plan(analyzed, descriptor, emptySet()).ready()
        assertSame(analyzed.file(model)?.snapshot, plan.model.snapshot)
        val newer = DeploymentPlanner().plan(workspace.analysis.value, descriptor, emptySet()).blocked()
        assertEquals(DeployProblemCodes.CONTEXT_ERRORS to Severity.ERROR, newer.codes().first())
        assertEquals(model, newer.first().location?.file)
    }

    @Test
    fun `a context file or the descriptor that differs from its saved state blocks the deployment`() = runTest(timeout = 60.seconds) {
        val workspaces = PlanningWorkspaces(this, root)
        val descriptor = workspaces.copy(SynthesizedModel.LAMP)
        val model = root.resolve("lamp/lamp.kmodel")
        val analysis = workspaces.analyze()

        val unsavedModel = DeploymentPlanner().plan(analysis, descriptor, setOf(model)).blocked().single()
        assertEquals(DeployProblemCodes.UNSAVED_FILE to Severity.ERROR, unsavedModel.code to unsavedModel.severity)
        assertEquals(model, unsavedModel.location?.file)

        val unsavedDescriptor = DeploymentPlanner().plan(analysis, descriptor, setOf(descriptor)).blocked().single()
        assertEquals(DeployProblemCodes.UNSAVED_FILE, unsavedDescriptor.code)
        assertEquals(descriptor, unsavedDescriptor.location?.file)

        DeploymentPlanner().plan(analysis, descriptor, setOf(root.resolve("elsewhere.kmodel"))).ready()
    }

    @Test
    fun `an object keyed with an underscore is a key of the runtime's index`() = runTest(timeout = 60.seconds) {
        val workspaces = PlanningWorkspaces(this, root)
        val descriptor = workspaces.copy(SynthesizedModel.LAMP)
        root.resolve("lamp/lamp.kmodel").replaceText("make object \"spare\":\"Bulb\"", "make object \"_\":\"Bulb\"")

        val plan = workspaces.plan(descriptor).ready()

        assertEquals(setOf("living room", "lamp", "bulb", "_", "wallSwitch"), plan.modelObjectIds)
    }

    private companion object {
        /** A tick limit with a timeout, written as the first block of the switch's state `up`. */
        const val TIMEOUT: String = """            TICK LIMIT {
                TIMEOUT: AFTER 10 TICKS TRANSITION TO "back down"
            }"""
    }
}
