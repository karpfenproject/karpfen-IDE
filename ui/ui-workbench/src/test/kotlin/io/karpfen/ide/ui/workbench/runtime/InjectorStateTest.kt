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
package io.karpfen.ide.ui.workbench.runtime

import io.karpfen.ide.runtime.protocol.conformance.DescriptorTiming
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.runtime.session.Preparation
import io.karpfen.ide.runtime.session.events.DomainOrigin
import io.karpfen.ide.runtime.session.events.InjectionAvailability
import io.karpfen.ide.runtime.session.events.InjectionOutcome
import io.karpfen.ide.runtime.session.events.PayloadKind
import io.karpfen.ide.runtime.session.fixtures.FakeRuntimeSession
import io.karpfen.ide.ui.workbench.testing.TestWorkbench
import io.karpfen.ide.ui.workbench.testing.withWorkbench
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The event injector of a run over a [FakeRuntimeSession] of a really prepared deployment: the domains and events it
 * offers, the payload template it prefills, the findings after every edit, and when Send is enabled.
 */
class InjectorStateTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()

    @Test
    fun `choosing an event prefills the template of its payload type`() = injector(SynthesizedModel.LAMP) { injector, _ ->
        assertEquals(listOf("service", "switch"), injector.state.value.domains.map { it.name })
        assertEquals("smash", injector.state.value.event?.name)

        injector.selectDomain("switch")

        assertEquals(listOf("press", "dim"), injector.state.value.events.map { it.name })
        assertEquals("press", injector.state.value.event?.name)

        injector.selectEvent("dim")

        val state = injector.state.value
        assertEquals(PayloadKind.JSON, state.kind)
        assertEquals("{\n  \"bright\": false\n}", state.text)
        assertFalse(state.validation.hasErrors)
    }

    @Test
    fun `every edit of the payload is checked`() = injector(SynthesizedModel.LAMP) { injector, _ ->
        injector.selectDomain("switch")
        injector.selectEvent("dim")

        injector.edit("{\"bright\": 3}")

        assertTrue(injector.state.value.validation.hasErrors)

        injector.edit("{\"bright\": true}")

        assertFalse(injector.state.value.validation.hasErrors)
    }

    @Test
    fun `Send is enabled only while events can be injected and the payload has no error`() =
        injector(SynthesizedModel.LAMP) { injector, session ->
            injector.selectDomain("switch")
            injector.selectEvent("dim")

            assertFalse(injector.state.value.canSend, "the environment does not take events yet")

            session.availabilityFlow.value = InjectionAvailability.Live
            settle()

            assertTrue(injector.state.value.canSend)

            injector.edit("{\"bright\": 3}")

            assertFalse(injector.state.value.canSend, "the payload has an error")

            injector.edit("{\"bright\": true}")
            injector.send().joinSettled()

            assertIs<InjectionOutcome.Sent>(injector.state.value.lastOutcome)
            assertFalse(injector.state.value.sending)
            assertEquals(1, session.frames.size)
        }

    @Test
    fun `a domain only the machines use follows the declared ones, and its undeclared event has no payload`() =
        injector(SynthesizedModel.METRONOME) { injector, _ ->
            assertEquals(
                listOf("beat" to DomainOrigin.DECLARED, "tick" to DomainOrigin.USED_ONLY),
                injector.state.value.domains.map { it.name to it.origin },
            )

            injector.selectDomain("tick")

            val state = injector.state.value
            assertEquals("burst done", state.event?.name)
            assertEquals(PayloadKind.NONE, state.kind)
            assertEquals("", state.text)
        }

    /** Prepares [model] in a workbench and runs [block] with the injector of a fake session of the deployment. */
    private fun injector(model: SynthesizedModel, block: suspend TestWorkbench.(InjectorState, FakeRuntimeSession) -> Unit) =
        runTest(timeout = 60.seconds) {
            val descriptor = SynthesizedModels.copyTo(root.resolve(model.folder), model, DescriptorTiming.EVENT_SCENARIOS)
            withWorkbench(dir.resolve("settings")) {
                open(root)
                val prepared = assertIs<Preparation.Ready>(realSessions.prepare(workspace, descriptor.toAbsolutePath().normalize()))
                val session = FakeRuntimeSession(prepared.prepared)
                val injector = InjectorState(session.events, scope) { request -> session.events.inject(request) }
                block(injector, session)
            }
        }
}
