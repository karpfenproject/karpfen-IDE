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
package io.karpfen.ide.runtime.session

import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.eventually
import io.karpfen.ide.runtime.session.mirror.MirrorComparison
import io.karpfen.ide.runtime.session.mirror.MirrorSnapshot
import io.karpfen.ide.runtime.session.mirror.ObjectOrigin
import io.karpfen.ide.runtime.session.mirror.ObjectRef
import io.karpfen.ide.runtime.session.observation.ObservedModel
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Covers objects the runtime creates while it runs, observed through a session with observation on, against a real
 * runtime. The scenarios use the depot, the one model that runs Python inside the container to create objects at run
 * time. A crate created into the atomic slot replaces the modeled one. It is observed with its own later change and its
 * deletion. Crates created into a list are observed one by one. Switching on after crates were created reads them from
 * the snapshot and then observes their changes. At the end the mirror equals a fresh snapshot. Generated ids are never
 * asserted. The scenarios only check that they are not in the uploaded model.
 */
abstract class RunTimeObjectScenarios {

    /** The runtime the scenarios deploy to. */
    protected abstract val runtime: SessionUnderTest

    /** The folder the scenarios copy their models into. */
    @TempDir
    lateinit var root: Path

    @Test
    fun `a crate created into the slot is observed with its own change and its deletion`() = runtime.run(root) {
        val session = deploy(SynthesizedModel.DEPOT)
        session.awaitStack(DEPOT, IDLE)
        val modeled = session.prepared.plan.modelObjectIds

        session.inject("dock", "receive")
        val received = session.awaitModel("a run-time crate in the slot", patience.burst) { model ->
            val crate = model.mirror.slot()
            crate != null && crate !in modeled && model.mirror.objects[crate]?.text("label") == "received" && "crate0" !in model.mirror.objects &&
                model.backInIdleAfter("receiving")
        }
        val crate = checkNotNull(received.mirror.slot())
        assertEquals(ObjectOrigin.RUNTIME, received.mirror.objectOf(crate).origin)

        session.inject("dock", "label")
        session.awaitModel("the run-time crate relabelled without a pull") {
            it.mirror.objects[crate]?.text("label") == "relabelled" && it.backInIdleAfter("labelling")
        }
        session.inject("dock", "scrap")
        session.awaitModel("the slot empty and the run-time crate gone") { it.mirror.slot() == null && crate !in it.mirror.objects }

        agreesWithSnapshot(session)
    }

    @Test
    fun `crates created into a list are observed one by one`() = runtime.run(root) {
        val session = deploy(SynthesizedModel.DEPOT)
        session.awaitStack(DEPOT, IDLE)

        session.inject("dock", "stack")
        session.awaitModel("one stacked crate", patience.burst) { it.mirror.stacked().size == 1 && it.backInIdleAfter("stacking") }
        session.inject("dock", "stack")
        val stacked = session.awaitModel("two stacked crates", patience.burst) { it.mirror.stacked().size == 2 && it.backInIdleAfter("stacking") }

        val crates = stacked.mirror.stacked()
        assertNotEquals(crates[0], crates[1])
        crates.forEach { crate ->
            assertEquals("stacked", stacked.mirror.objectOf(crate).text("label"))
            assertEquals(ObjectOrigin.RUNTIME, stacked.mirror.objectOf(crate).origin)
        }
        agreesWithSnapshot(session)
    }

    @Test
    fun `switching on after crates were created reads them from the snapshot and observes their changes`() = runtime.run(root) {
        val session = deploy(SynthesizedModel.DEPOT)
        session.awaitStack(DEPOT, IDLE)
        assertEquals(SessionCommandOutcome.Done, session.setObservation(false))

        session.inject("dock", "receive")
        rawUntil(session, "a run-time crate in the slot") { it.slot() != null && it.status() == "received" }
        session.inject("dock", "stack")
        rawUntil(session, "a stacked crate") { it.stacked().size == 1 && it.status() == "stacked" }
        assertEquals(SessionCommandOutcome.Done, session.setObservation(true))

        val switchedOn = session.model.value.mirror
        val crate = checkNotNull(switchedOn.slot())
        assertEquals(ObjectOrigin.RUNTIME, switchedOn.objectOf(crate).origin)
        assertEquals(1, switchedOn.stacked().size)
        session.inject("dock", "label")
        session.awaitModel("the run-time crate relabelled without a pull") { it.mirror.objects[crate]?.text("label") == "relabelled" }
        agreesWithSnapshot(session)
    }

    /** Waits until raw snapshots of [session] show the depot in idle with a model [accept] accepts. */
    private suspend fun SessionScenario.rawUntil(session: RuntimeSession, what: String, accept: (MirrorSnapshot) -> Boolean): RawSnapshot =
        eventually(what, patience.burst) { session.snapshot().takeIf { it.stacks[DEPOT] == IDLE && accept(it.mirror) } }

    /** Waits until [session]'s mirror equals a fresh snapshot. */
    private suspend fun SessionScenario.agreesWithSnapshot(session: RuntimeSession) {
        eventually("the mirror equal to a fresh snapshot") {
            session.snapshot().takeIf { MirrorComparison.differences(it.mirror, session.model.value.mirror).isEmpty() }
        }
    }

    private companion object {
        /** The depot's key. */
        const val DEPOT = "depot"

        /** The depot machine's stack while it waits. */
        val IDLE = listOf("idle")

        /** The id of the crate in the depot's slot, or null if it is empty. */
        fun MirrorSnapshot.slot(): String? = targets("slot").firstOrNull()

        /** The ids of the crates on the depot's stack, in order. */
        fun MirrorSnapshot.stacked(): List<String> = targets("stack")

        /** The depot's status. */
        fun MirrorSnapshot.status(): String? = objects[DEPOT]?.text("status")

        /** The ids of the keyed targets of the depot's relation [key]. */
        fun MirrorSnapshot.targets(key: String): List<String> =
            objects[DEPOT]?.relations?.firstOrNull { it.key == key }?.targets?.filterIsInstance<ObjectRef.Keyed>()?.map { it.id }.orEmpty()

        /** True if the depot's machine reports idle right after [state]. */
        fun ObservedModel.backInIdleAfter(state: String): Boolean =
            states.machines[DEPOT]?.let { it.stack == IDLE && it.previous == listOf(state) } == true
    }
}
