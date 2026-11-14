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

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.api.RuntimeResult
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.conformance.eventually
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import io.karpfen.ide.runtime.session.states.StackOrigin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers dynamic attachment through the real deployment pipeline and a session against a real runtime. The shelf below
 * has a room with a lamp, the lamp's bulb and one spare bulb on the shelf. Every bulb behaves after the attachable
 * state machine `glowing`, and the descriptor lists `glowing` and `blinking` as definitions next to the room's
 * attached state machine. The room reacts to the events of the domain `service`: `stock` puts a new bulb on the shelf
 * with `ATTACH "blinking"`, `plain` puts one there without `ATTACH`, `flicker` exchanges the state machine of the
 * lamp's bulb for `blinking`, and `drop` removes the lamp's bulb. A `blinking` instance stays in `on` until `dim`.
 *
 * The scenarios check what the session knows about the instances at every moment: those the start builds through
 * `behaves after`, those that `ATTACH` and the type of a created object bring, the exchange of one instance for
 * another on the same object, the instance that leaves with its object, and that the session's view agrees with a
 * snapshot read afresh. They also restart the environment with raw requests, since a session never starts a stopped
 * environment again, and check the refusals of the runtime that the IDE maps to problems. Creating a bulb runs an EVAL
 * that returns a dictionary, so the runtime needs Python, which the runtime's container has.
 */
abstract class AttachmentScenarios {

    /** The runtime the scenarios deploy to. */
    protected abstract val runtime: SessionUnderTest

    /** The folder the scenarios write their models into. */
    @TempDir
    lateinit var root: Path

    @Test
    fun `at the start every bulb gets an instance of the definition its type behaves after`() = runtime.run(root) {
        val session = deployShelf("start")

        val model = session.awaitModel("the bulb lit and the spare dark") { it.stacks["bulb"] == listOf("lit") && it.stacks["spare"] == listOf("dark") }

        assertEquals(mapOf("room" to null, "bulb" to "glowing", "spare" to "glowing"), definitionsOf(session), diagnostics)
        assertEquals(StackOrigin.REPORTED, model.states.machines.getValue("bulb").origin)
        assertEquals(listOf("ready"), model.stacks["room"], diagnostics)
    }

    @Test
    fun `an object created with ATTACH appears with an instance of that definition, which steps`() = runtime.run(root) {
        val session = deployShelf("attach")

        session.inject("service", "stock")
        val created = awaitNewInstance(session, "blinking")
        session.awaitStack(created, listOf("on"))
        session.inject("service", "dim")
        session.awaitStack(created, listOf("off"))

        assertEquals("blinking", session.model.value.states.machines.getValue(created).definition, diagnostics)
    }

    @Test
    fun `an object created without ATTACH gets an instance of the definition its type behaves after`() = runtime.run(root) {
        val session = deployShelf("plain")

        session.inject("service", "plain")
        val created = awaitNewInstance(session, "glowing")

        session.awaitStack(created, listOf("lit"))
    }

    @Test
    fun `EXCHANGE ends the instance of an object and starts the new one in its initial state on the same key`() = runtime.run(root) {
        val session = deployShelf("exchange")
        session.awaitStack("bulb", listOf("lit"))

        session.inject("service", "flicker")
        session.awaitModel("the bulb running blinking in on") { model ->
            model.states.machines["bulb"]?.let { it.definition == "blinking" && it.stack == listOf("on") && it.origin == StackOrigin.REPORTED } == true
        }

        val lifecycle = session.received().filter { it is RuntimePush.StateMachineAttached || it is RuntimePush.StateMachineDetached }
            .filter { push -> push.keyOf() == "bulb" }
        assertEquals(
            listOf("detached glowing", "attached blinking"),
            lifecycle.takeLast(2).map { push ->
                when (push) {
                    is RuntimePush.StateMachineDetached -> "detached ${push.stateMachine}"
                    is RuntimePush.StateMachineAttached -> "attached ${push.stateMachine}"
                    else -> error("not a lifecycle push: $push")
                }
            },
            diagnostics,
        )
    }

    @Test
    fun `DROPOBJ removes the object and its instance leaves with its stack`() = runtime.run(root) {
        val session = deployShelf("drop")
        session.awaitStack("bulb", listOf("lit"))

        session.inject("service", "drop")
        val model = session.awaitModel("the bulb gone with its instance") { "bulb" !in it.states.machines && "bulb" !in it.mirror.objects }

        assertNull(model.stacks["bulb"], diagnostics)
        assertEquals(listOf("room", "spare"), model.states.machines.keys.toList(), diagnostics)
    }

    @Test
    fun `the session's instances and stacks agree with a snapshot read afresh`() = runtime.run(root) {
        val session = deployShelf("agree")
        session.inject("service", "stock")
        awaitNewInstance(session, "blinking")
        session.inject("service", "flicker")
        session.awaitModel("the bulb running blinking") { it.states.machines["bulb"]?.definition == "blinking" }

        eventually("the session's view equal to a fresh snapshot") {
            val fresh = session.snapshot()
            val seen = session.model.value
            assertEquals(fresh.stateMachines, definitionsOf(session), diagnostics)
            assertEquals(fresh.stateMachines.keys.toList(), seen.states.machines.keys.toList(), diagnostics)
            assertEquals(fresh.stacks, seen.stacks, diagnostics)
            true
        }
        val held = call(RuntimeRoute.LIST_STATE_MACHINES, "envKey" to session.environment.value).body
        assertTrue("\"glowing\"" in held && "\"blinking\"" in held, held)
    }

    @Test
    fun `a restart rebuilds the instances from the model, and those of ATTACH do not come back`() = runtime.run(root) {
        val session = deployShelf("restart")
        session.inject("service", "stock")
        val created = awaitNewInstance(session, "blinking")
        assertEquals(SessionCommandOutcome.Done, session.stop(), diagnostics)

        val environment = session.environment.value
        assertEquals(OK, call(RuntimeRoute.START_ENVIRONMENT, "envKey" to environment).status, diagnostics)
        try {
            val restarted = eventually("the instances of the restart") {
                session.snapshot().takeIf { it.phase == RuntimePhase.RUNNING.wireName && created in it.stateMachines }
            }
            assertEquals(listOf("room", "bulb", "spare", created), restarted.stateMachines.keys.toList(), diagnostics)
            assertEquals(mapOf("room" to null, "bulb" to "glowing", "spare" to "glowing", created to "glowing"), restarted.stateMachines)
        } finally {
            call(RuntimeRoute.STOP_ENVIRONMENT, "envKey" to environment)
        }
    }

    @Test
    fun `a type that behaves after a state machine the descriptor does not list is refused before and by the runtime`() = runtime.run(root) {
        val folder = Files.createDirectories(root.resolve("unlisted"))
        val descriptor = ShelfModel.writeTo(folder, definitions = emptyList())
        val workspace = open(descriptor)
        val before = call(RuntimeRoute.LIST_ENVIRONMENTS).body

        val blocked = assertIs<Preparation.Blocked>(sessions.prepare(workspace, descriptor), diagnostics)

        assertEquals(
            listOf(DeployProblemCodes.DEFINITION_NOT_LISTED),
            blocked.findings.filter { it.severity == Severity.ERROR }.map { it.code },
            "${blocked.findings}",
        )
        assertEquals(before, call(RuntimeRoute.LIST_ENVIRONMENTS).body, diagnostics)

        val environment = configured(folder)
        assertDone(environment.setStateMachine("room", ShelfModel.text(folder, "room.kstates")))
        val refusal = assertIs<RuntimeFailure.Rejected>(assertIs<CallOutcome.Failed>(environment.activate(), diagnostics).failure, diagnostics)
        assertEquals(RejectionKind.MISSING_DEFINITION, refusal.kind, refusal.message)
        assertEquals("'Bulb' behaves after 'glowing'", refusal.captured["entries"], refusal.message)
    }

    @Test
    fun `an upload in the wrong form is refused with the text of that form`() = runtime.run(root) {
        val folder = Files.createDirectories(root.resolve("forms"))
        ShelfModel.writeTo(folder)
        val environment = configured(folder)

        val attachable = assertIs<CallOutcome.Failed>(environment.setStateMachine("room", ShelfModel.text(folder, "glowing.kstates")), diagnostics)
        val attached = assertIs<CallOutcome.Failed>(environment.defineStateMachine(ShelfModel.text(folder, "room.kstates")), diagnostics)

        val wrongAttached = assertIs<RuntimeFailure.Rejected>(attachable.failure, diagnostics)
        assertEquals(RejectionKind.ATTACHED_FORM_REQUIRED, wrongAttached.kind, wrongAttached.message)
        assertEquals(mapOf("element" to "room", "machine" to "glowing"), wrongAttached.captured)
        assertEquals(RejectionKind.ATTACHABLE_FORM_REQUIRED, assertIs<RuntimeFailure.Rejected>(attached.failure, diagnostics).kind)
        assertEquals(
            RuntimeText.ATTACHABLE_FORM_REQUIRED.render(emptyMap()),
            (attached.failure as RuntimeFailure.Rejected).message,
        )
    }

    /** Writes the shelf into a folder named [name], opens it with every language feature on and deploys it. */
    private suspend fun SessionScenario.deployShelf(name: String): RuntimeSession {
        val descriptor = ShelfModel.writeTo(Files.createDirectories(root.resolve(name)))
        val session = deploy(prepare(open(descriptor, features = KstatesFeature.ALL), descriptor))
        session.awaitStack("room", listOf("ready"))
        return session
    }

    /** The definition of every instance in the model of [session], by the key of its object, null for an attached one. */
    private fun definitionsOf(session: RuntimeSession): Map<String, String?> =
        session.model.value.states.machines.mapValues { it.value.definition }

    /** Waits for an instance of [definition] on an object the model did not have, and returns the object's key. */
    private suspend fun SessionScenario.awaitNewInstance(session: RuntimeSession, definition: String): String {
        val model = session.awaitModel("a new object running $definition") { model ->
            model.states.machines.values.any { it.attachedTo !in ShelfModel.KEYS && it.definition == definition }
        }
        return model.states.machines.values.first { it.attachedTo !in ShelfModel.KEYS && it.definition == definition }.attachedTo
    }

    /** An environment of its own with the shelf's metamodel, events and model of [folder] uploaded, still configuring. */
    private suspend fun SessionScenario.configured(folder: Path): RuntimeEnvironment {
        val environment = assertIs<RuntimeResult.Ok<RuntimeEnvironment>>(client.createEnvironment(), diagnostics).value
        assertDone(environment.setMetamodel(ShelfModel.text(folder, "shelf.kmeta")))
        assertDone(environment.setEventDefinitions(ShelfModel.text(folder, "shelf-events.kmeta")))
        assertDone(environment.setModel(ShelfModel.text(folder, "shelf.kmodel")))
        return environment
    }

    /** Fails unless [outcome] is done. */
    private fun SessionScenario.assertDone(outcome: CallOutcome<Unit>) {
        assertIs<CallOutcome.Done<Unit>>(outcome, "$outcome. $diagnostics")
    }

    /** The key of the object a lifecycle push is about. */
    private fun RuntimePush.keyOf(): String? = when (this) {
        is RuntimePush.StateMachineAttached -> modelElementId
        is RuntimePush.StateMachineDetached -> modelElementId
        else -> null
    }

    private companion object {
        /** The status of a success. */
        const val OK = 200
    }
}

/**
 * The shelf of [AttachmentScenarios]: a metamodel in which every bulb behaves after `glowing`, event definitions of the
 * domain `service`, a model with the room `room`, the lamp `lamp`, its bulb `bulb` with 40 watts and the spare bulb
 * `spare` with 0 watts, the attachable state machines `glowing` and `blinking`, the room's attached state machine and
 * a descriptor with a tick delay of 50 ms and an event time to live of 30 000 ms.
 */
internal object ShelfModel {
    /** The keys of the objects of the model. */
    val KEYS: Set<String> = setOf("room", "lamp", "bulb", "spare")

    /**
     * Writes the files of the shelf into [folder] with the descriptor listing the definitions [definitions], and returns
     * the descriptor.
     */
    fun writeTo(folder: Path, definitions: List<String> = listOf("glowing.kstates", "blinking.kstates")): Path {
        FILES.forEach { (name, text) -> Files.writeString(folder.resolve(name), text) }
        val listed = definitions.joinToString("") { ",\n    { \"file\": \"$it\" }" }
        val descriptor = """
            {
              "metamodel": "shelf.kmeta",
              "events": "shelf-events.kmeta",
              "model": "shelf.kmodel",
              "tickDelayMs": 50,
              "eventTtlMs": 30000,
              "stateMachines": [
                { "file": "room.kstates", "attachedTo": "room" }$listed
              ]
            }
        """.trimIndent()
        return Files.writeString(folder.resolve(DESCRIPTOR), descriptor)
    }

    /** The text of the file [name] the shelf wrote into [folder]. */
    fun text(folder: Path, name: String): String = Files.readString(folder.resolve(name))

    /** The file name of the descriptor. */
    private const val DESCRIPTOR = "shelf.kenv.json"

    /** The files of the shelf besides the descriptor, by name. */
    private val FILES: Map<String, String> = mapOf(
        "shelf.kmeta" to """
            type "Bulb" "A bulb" behaves after "glowing" {
                prop("watts", "number")
                prop("lit", "boolean")
            }
            type "Lamp" "A lamp with one bulb" {
                has("bulb", "Bulb")
            }
            type "Room" "A room with a lamp and a shelf of spare bulbs" {
                has("lamp", "Lamp")
                has("spares", list("Bulb"))
            }
        """.trimIndent(),
        "shelf-events.kmeta" to """
            type "stock" "Puts a new bulb with blinking on the shelf" { }
            type "plain" "Puts a new bulb on the shelf" { }
            type "flicker" "Exchanges the state machine of the lamp's bulb for blinking" { }
            type "drop" "Removes the lamp's bulb" { }
            type "dim" "Turns every blinking bulb off" { }
        """.trimIndent(),
        "shelf.kmodel" to """
            make object "room":"Room" {
                has("lamp") -> make object "lamp":"Lamp" {
                    has("bulb") -> make object "bulb":"Bulb" {
                        prop("watts") -> "40"
                        prop("lit") -> "false"
                    }
                }
                has("spares") -> make object "spare":"Bulb" {
                    prop("watts") -> "0"
                    prop("lit") -> "false"
                }
            }
        """.trimIndent(),
        "glowing.kstates" to """
            STATEMACHINE "glowing" ATTACHABLE TO "Bulb" {
                STATES {
                    INITIAL STATE "dark" { }
                    STATE "lit" {
                        ENTRY { SET("lit", "true") }
                    }
                }
                TRANSITIONS {
                    TRANSITION "dark" -> "lit" {
                        CONDITION { EVAL { return $(watts) > 0 } }
                    }
                }
            }
        """.trimIndent(),
        "blinking.kstates" to """
            STATEMACHINE "blinking" ATTACHABLE TO "Bulb" {
                STATES {
                    INITIAL STATE "on" { }
                    STATE "off" { }
                }
                TRANSITIONS {
                    TRANSITION "on" -> "off" {
                        CONDITION { EVENT("service", "dim") }
                    }
                }
            }
        """.trimIndent(),
        "room.kstates" to """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "ready" { }
                    STATE "stocked" {
                        ENTRY {
                            APPENDOBJ("self", "spares", EVAL { return {"watts": 25, "lit": False} }) ATTACH "blinking"
                        }
                    }
                    STATE "plain" {
                        ENTRY {
                            APPENDOBJ("self", "spares", EVAL { return {"watts": 25, "lit": False} })
                        }
                    }
                    STATE "flickered" {
                        ENTRY {
                            EXCHANGE("lamp->bulb", "blinking")
                        }
                    }
                    STATE "dropped" {
                        ENTRY {
                            DROPOBJ("lamp->bulb")
                        }
                    }
                }
                TRANSITIONS {
                    TRANSITION "ready" -> "stocked" {
                        CONDITION { EVENT("service", "stock") }
                    }
                    TRANSITION "ready" -> "plain" {
                        CONDITION { EVENT("service", "plain") }
                    }
                    TRANSITION "ready" -> "flickered" {
                        CONDITION { EVENT("service", "flicker") }
                    }
                    TRANSITION "ready" -> "dropped" {
                        CONDITION { EVENT("service", "drop") }
                    }
                    TRANSITION "stocked" -> "ready" { }
                    TRANSITION "plain" -> "ready" { }
                    TRANSITION "flickered" -> "ready" { }
                    TRANSITION "dropped" -> "ready" { }
                }
            }
        """.trimIndent(),
    )
}
