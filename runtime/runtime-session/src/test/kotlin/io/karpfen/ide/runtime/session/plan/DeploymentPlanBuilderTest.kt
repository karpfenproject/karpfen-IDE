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
import io.karpfen.ide.core.document.environment.EnvironmentDescriptor
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import io.karpfen.ide.runtime.session.problem.SourceLocation
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Covers the invariants that [DeploymentPlan.Builder] checks without a workspace. These are the upload order, machines
 * that match the attachments, the domains and no error among the findings. It also covers [DeployConfiguration] from a
 * descriptor, [DeclarationLocations] and the values of the plan package.
 */
class DeploymentPlanBuilderTest {

    private val directory: Path = Path.of("/workspace/lamp").toAbsolutePath()

    private fun upload(role: UploadRole, name: String, attachedTo: String? = null): PlannedUpload =
        PlannedUpload(role, directory.resolve(name), TextSnapshot("text of $name", 0), attachedTo)

    private val metamodel = upload(UploadRole.METAMODEL, "lamp.kmeta")
    private val events = upload(UploadRole.EVENTS, "lamp-events.kmeta")
    private val model = upload(UploadRole.MODEL, "lamp.kmodel")
    private val lamp = upload(UploadRole.STATE_MACHINE, "lamp.kstates", "lamp")
    private val switch = upload(UploadRole.STATE_MACHINE, "switch.kstates", "wallSwitch")

    private fun attachmentOf(upload: PlannedUpload): AttachedMachine =
        AttachedMachine(upload.attachedTo!!, upload.file, "Type", "Type")

    private fun builder(uploads: List<PlannedUpload>, machines: List<PlannedUpload> = uploads.filter { it.attachedTo != null }) =
        DeploymentPlan.Builder()
            .name("lamp")
            .descriptor(directory.resolve("lamp.kenv.json"))
            .uploads(uploads)
            .attachments(machines.map(::attachmentOf))

    @Test
    fun `a plan uploads the metamodel, the event definitions, the model and the machines in that order`() {
        val plan = builder(listOf(metamodel, events, model, lamp, switch)).domains(listOf("service", "switch")).generation(7).build()

        assertEquals(metamodel, plan.metamodel)
        assertEquals(events, plan.events)
        assertEquals(model, plan.model)
        assertEquals(listOf(lamp, switch), plan.machines)
        assertEquals(7, plan.generation)
        assertEquals(DeployConfiguration.DEFAULT, plan.configuration)
        assertNull(builder(listOf(metamodel, model)).build().events)
    }

    @Test
    fun `the metamodel comes first and exactly once`() {
        assertFailsWith<IllegalArgumentException> { builder(listOf(model, metamodel)).build() }
        assertFailsWith<IllegalArgumentException> { builder(listOf(metamodel, metamodel, model)).build() }
        assertFailsWith<IllegalArgumentException> { builder(listOf(events, model)).build() }
    }

    @Test
    fun `the event definitions come at most once right after the metamodel`() {
        assertFailsWith<IllegalArgumentException> { builder(listOf(metamodel, model, events)).build() }
        assertFailsWith<IllegalArgumentException> { builder(listOf(metamodel, events, events, model)).build() }
    }

    @Test
    fun `exactly one model follows the metamodel and the event definitions`() {
        assertFailsWith<IllegalArgumentException> { builder(listOf(metamodel, events)).build() }
        assertFailsWith<IllegalArgumentException> { builder(listOf(metamodel, model, model)).build() }
        assertFailsWith<IllegalArgumentException> { builder(listOf(metamodel, lamp, model), listOf(lamp)).build() }
    }

    @Test
    fun `the machines have distinct keys and match the attachments in order`() {
        val again = upload(UploadRole.STATE_MACHINE, "switch.kstates", "lamp")
        assertFailsWith<IllegalArgumentException> { builder(listOf(metamodel, model, lamp, again)).build() }
        assertFailsWith<IllegalArgumentException> { builder(listOf(metamodel, model, lamp, switch), listOf(switch, lamp)).build() }
        assertFailsWith<IllegalArgumentException> { builder(listOf(metamodel, model, lamp), emptyList()).build() }
    }

    @Test
    fun `a plan needs its name and descriptor, distinct non-blank domains and no error`() {
        assertFailsWith<IllegalArgumentException> { DeploymentPlan.Builder().descriptor(directory).uploads(listOf(metamodel, model)).build() }
        assertFailsWith<IllegalArgumentException> { DeploymentPlan.Builder().name("lamp").uploads(listOf(metamodel, model)).build() }
        assertFailsWith<IllegalArgumentException> { builder(listOf(metamodel, model)).domains(listOf("a", "a")).build() }
        assertFailsWith<IllegalArgumentException> { builder(listOf(metamodel, model)).domains(listOf(" ")).build() }
        val error = DeployProblem(Severity.ERROR, DeployProblemCodes.REFERENCE_LOOP, "loop", null)
        assertFailsWith<IllegalArgumentException> { builder(listOf(metamodel, model)).findings(listOf(error)).build() }
    }

    @Test
    fun `only a state machine upload names a key`() {
        assertFailsWith<IllegalArgumentException> { upload(UploadRole.MODEL, "lamp.kmodel", "lamp") }
        assertFailsWith<IllegalArgumentException> { upload(UploadRole.STATE_MACHINE, "lamp.kstates") }
    }

    @Test
    fun `a blocked result holds an error`() {
        val info = DeployProblem(Severity.INFO, DeployProblemCodes.OBSERVE_SUPERSEDED, "observe", null)
        assertFailsWith<IllegalArgumentException> { PlanResult.Blocked(listOf(info)) }
    }

    @Test
    fun `the configuration takes the descriptor's values or the format's defaults`() {
        val bare = EnvironmentDescriptor(metamodel = "lamp.kmeta", model = "lamp.kmodel")
        assertEquals(DeployConfiguration(50, 3000), DeployConfiguration.of(bare))
        assertEquals(DeployConfiguration(10, 0), DeployConfiguration.of(bare.copy(tickDelayMs = 10, eventTtlMs = 0)))
        assertEquals(DeployConfiguration(Int.MAX_VALUE, -1), DeployConfiguration.of(bare.copy(tickDelayMs = Int.MAX_VALUE.toLong(), eventTtlMs = -1)))
        assertFailsWith<IllegalArgumentException> { DeployConfiguration.of(bare.copy(tickDelayMs = -1)) }
        assertFailsWith<IllegalArgumentException> { DeployConfiguration.of(bare.copy(tickDelayMs = Int.MAX_VALUE + 1L)) }
        assertFailsWith<IllegalArgumentException> { DeployConfiguration(-1, 0) }
    }

    @Test
    fun `declaration locations answer the range in the file's text or nothing`() {
        val file = directory.resolve("lamp.kmodel")
        val snapshot = TextSnapshot("make object \"lamp\":\"Lamp\" { }", 3)
        val locations = DeclarationLocations(
            objects = mapOf(file to mapOf("lamp" to TextRange(13, 17))),
            snapshots = mapOf(file to snapshot),
        )

        assertEquals(SourceLocation(file, TextRange(13, 17), snapshot), locations.objectIn(file, "lamp"))
        assertNull(locations.objectIn(file, "bulb"))
        assertNull(locations.typeIn(file, "Lamp"))
        assertNull(locations.stateIn(directory.resolve("lamp.kstates"), "off"))
        assertEquals(SourceLocation(file, null, snapshot), locations.fileOf(file))
        assertNull(DeclarationLocations.EMPTY.objectIn(file, "lamp"))
    }

    @Test
    fun `a location's range lies within its text`() {
        assertFailsWith<IllegalArgumentException> { SourceLocation(directory, TextRange(0, 5), TextSnapshot("abc", 0)) }
    }
}
