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
import io.karpfen.ide.runtime.protocol.conformance.awaitValue
import io.karpfen.ide.runtime.session.events.DomainOrigin
import io.karpfen.ide.runtime.session.events.PayloadKind
import io.karpfen.ide.runtime.session.mirror.ObjectRef
import io.karpfen.ide.runtime.session.observation.ObservedModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers injecting events into the lamp through a session against a real runtime. Two presses toggle both machines and
 * every value they set. A JSON or KModel payload decides the guard of `dim`, and a false guard fires nothing. `smash`
 * deletes the spare bulb from the model. Both domains of the session are declared, and every press is announced back as
 * a domain event of an external message. Every event is injected only once the state that listens for it is observed.
 */
abstract class InjectionScenarios {

    /** The runtime the scenarios deploy to. */
    protected abstract val runtime: SessionUnderTest

    /** The folder the scenarios copy their models into. */
    @TempDir
    lateinit var root: Path

    @Test
    fun `two presses toggle both machines and every value they set`() = runtime.run(root) {
        val session = deploy(SynthesizedModel.LAMP)
        session.awaitModel("both machines in their initial states") { it.stacks == INITIAL }

        session.inject("switch", "press")
        val first = session.awaitModel("the first press") { it.stacks == mapOf("lamp" to listOf("on"), "wallSwitch" to listOf("up")) && it.log() == listOf("off", "on") }

        assertEquals("on", first.mirror.objectOf("lamp").text("mode"))
        assertEquals(true, first.mirror.objectOf("bulb").flag("lit"))
        assertEquals("up", first.mirror.objectOf("wallSwitch").text("position"))
        assertEquals(listOf("up"), first.mirror.objectOf("wallSwitch").texts("presses"))
        session.inject("switch", "press")
        session.awaitModel("the second press") {
            it.stacks == mapOf("lamp" to listOf("off"), "wallSwitch" to listOf("back down")) && it.log() == listOf("off", "on", "off")
        }
    }

    @Test
    fun `a JSON payload decides the guard of dim`() = runtime.run(root) {
        val session = switchedOn()

        session.inject("switch", "dim", PayloadKind.JSON, """{"bright": true}""")

        val model = session.awaitStack("lamp", listOf("on", "full power"))
        session.awaitModel("the bulb at 100 W") { it.mirror.objectOf("bulb").number("watts") == 100.0 && it.log()?.lastOrNull() == FULL_POWER }
        assertEquals(listOf("on", "full power"), model.stacks["lamp"])
    }

    @Test
    fun `a false guard fires nothing`() = runtime.run(root) {
        val session = switchedOn()

        session.inject("switch", "dim", PayloadKind.JSON, """{"bright": false}""")
        session.inject("switch", "press")

        val model = session.awaitStack("lamp", listOf("off"))
        session.awaitModel("the log of one press after the dim") { it.log() == listOf("off", "on", "off") }
        assertEquals(40.0, model.mirror.objectOf("bulb").number("watts"))
    }

    @Test
    fun `a KModel payload decides the guard of dim`() = runtime.run(root) {
        val session = switchedOn()

        session.inject("switch", "dim", PayloadKind.KMODEL, """make object "p":"dim" { prop("bright") -> "true" }""")

        session.awaitStack("lamp", listOf("on", "full power"))
        session.awaitModel("the bulb at 100 W") { it.mirror.objectOf("bulb").number("watts") == 100.0 && it.log()?.lastOrNull() == FULL_POWER }
    }

    @Test
    fun `smash deletes the spare bulb from the model`() = runtime.run(root) {
        val session = deploy(SynthesizedModel.LAMP)
        session.awaitModel("both machines in their initial states") { it.stacks == INITIAL }

        session.inject("service", "smash")

        val model = session.awaitModel("the broken lamp without its spare bulb") {
            it.stacks["lamp"] == listOf("broken") && "spare" !in it.mirror.objects && it.log()?.lastOrNull() == "broken"
        }
        val spare = model.mirror.objectOf("lamp").relations.firstOrNull { it.key == "spare" }
        assertTrue(spare == null || spare.targets.none { it is ObjectRef.Keyed || it is ObjectRef.Anonymous }, "$spare")
    }

    @Test
    fun `the domains are declared and every press is announced back as an external message`() = runtime.run(root) {
        val session = deploy(SynthesizedModel.LAMP)
        val announced = MutableStateFlow<List<ObservedDomainEvent>>(emptyList())
        launch(start = CoroutineStart.UNDISPATCHED) { session.domainEvents.collect { event -> announced.update { it + event } } }
        session.awaitModel("both machines in their initial states") { it.stacks == INITIAL }

        val catalog = session.events.catalog
        assertEquals(listOf("service" to DomainOrigin.DECLARED, "switch" to DomainOrigin.DECLARED), catalog.domains.map { it.name to it.origin })
        assertEquals(setOf("press", "dim", "smash"), catalog.events.filter { it.declared }.map { it.name }.toSet())
        session.inject("switch", "press")
        session.awaitStack("lamp", listOf("on"))
        session.inject("switch", "press")

        val presses = awaitValue("two presses announced", announced) { events -> events.count { it.event?.name == "press" } >= 2 }
        assertTrue(presses.all { it.domain == "switch" && it.event?.source == "EXTERNAL_MESSAGE" }, "$presses")
    }

    /** Deploys the lamp and presses once, so that it is on. */
    private suspend fun SessionScenario.switchedOn(): RuntimeSession {
        val session = deploy(SynthesizedModel.LAMP)
        session.awaitModel("both machines in their initial states") { it.stacks == INITIAL }
        session.inject("switch", "press")
        session.awaitModel("the lamp on") { it.stacks["lamp"] == listOf("on") && it.log() == listOf("off", "on") }
        return session
    }

    /** The lamp's log, or null if the mirror has none. */
    private fun ObservedModel.log(): List<String>? = mirror.objects["lamp"]?.texts("log")

    private companion object {
        /** The initial stacks of the lamp's machines. */
        val INITIAL = mapOf("lamp" to listOf("off"), "wallSwitch" to listOf("down"))

        /** The log entry of full power, with its en dash. */
        const val FULL_POWER = "100 W – full power"
    }
}
