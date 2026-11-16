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

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * Covers state machines composed of pages through a session against a real runtime. The paged lamp below has two
 * container states that both use the fragment `bulb test`. A press of the domain `switch` leads into the first use, a
 * pass of the domain `service` leads each use from `lit` through `checked` into its final state `passed`, and DONE
 * leads out of the first use into the second and out of the second into `on`. The states `lit`, `checked` and `on`
 * append their names to the list `trail`, so the model shows which states the run entered. A final state takes no
 * entry block, which is why `checked` stands before `passed`.
 *
 * The deployment uploads the fragment and the attached state machine, and the runtime runs the flat state machine. The
 * stacks carry the qualified names of the flat machine, `first test/lit`, the two uses of the fragment are told apart
 * by their container states, and a state inside a fragment can be forced by its qualified name.
 */
abstract class PageScenarios {

    /** The runtime the scenarios deploy to. */
    protected abstract val runtime: SessionUnderTest

    /** The folder the scenarios write their models into. */
    @TempDir
    lateinit var root: Path

    @Test
    fun `a press leads into the first use of the fragment, and the stack carries qualified names`() = runtime.run(root) {
        val session = deployPagedLamp()

        session.inject("switch", "press")

        val model = session.awaitStack(LAMP, listOf("first test", "first test/lit"))
        assertEquals(listOf("first test", "first test/lit"), model.stacks[LAMP], diagnostics)
        assertEquals(listOf("first test", "first test/lit"), session.snapshot().stacks[LAMP], diagnostics)
    }

    @Test
    fun `the first use runs into its final state and DONE leads out of its container state`() = runtime.run(root) {
        val session = deployPagedLamp()
        session.inject("switch", "press")
        session.awaitStack(LAMP, listOf("first test", "first test/lit"))

        session.inject("service", "pass")

        session.awaitStack(LAMP, listOf("second test", "second test/lit"))
        session.awaitModel("the first use passed and the second began") { it.mirror.objectOf(LAMP).texts("trail") == listOf("lit", "checked", "lit") }
    }

    @Test
    fun `one fragment used twice keeps its two uses apart`() = runtime.run(root) {
        val session = deployPagedLamp()
        session.inject("switch", "press")
        session.awaitStack(LAMP, listOf("first test", "first test/lit"))
        session.inject("service", "pass")
        session.awaitStack(LAMP, listOf("second test", "second test/lit"))

        session.inject("service", "pass")

        session.awaitStack(LAMP, listOf("on"))
        session.awaitModel("both uses passed and the lamp is on") {
            it.mirror.objectOf(LAMP).texts("trail") == listOf("lit", "checked", "lit", "checked", "on")
        }
    }

    @Test
    fun `a state inside a fragment is forced by its qualified name`() = runtime.run(root) {
        val session = deployPagedLamp()

        assertEquals(SessionCommandOutcome.Done, session.forceState(LAMP, "second test/lit"))
        session.awaitStack(LAMP, listOf("second test", "second test/lit"))

        session.inject("service", "pass")
        session.awaitStack(LAMP, listOf("on"))
    }

    /** Writes the paged lamp into [root], deploys it and waits for its initial stack. */
    private suspend fun SessionScenario.deployPagedLamp(): RuntimeSession {
        val descriptor = PagedLampModel.writeTo(Files.createDirectories(root.resolve("paged")))
        val session = deploy(prepare(open(descriptor), descriptor))
        session.awaitStack(LAMP, listOf("off"))
        return session
    }

    private companion object {
        /** The key of the lamp, which the state machine is attached to. */
        const val LAMP = PagedLampModel.LAMP
    }
}

/**
 * The paged lamp of [PageScenarios]: a metamodel, event definitions, a model with the one object `lamp`, the fragment
 * `bulb test`, a state machine whose container states `first test` and `second test` both use it, and a descriptor
 * that lists the fragment, with a tick delay of 50 ms and an event time to live of 30 000 ms. It uses no EVAL and no
 * macro, so it needs no Python.
 */
internal object PagedLampModel {
    /** The key of the lamp object, which the state machine is attached to. */
    const val LAMP = "lamp"

    /** The file name of the state machine of the lamp. */
    const val MACHINE = "paged_lamp.kstates"

    /** The file name of the fragment. */
    const val FRAGMENT = "bulb_test.kstates"

    /** Writes the files of the paged lamp into [folder] and returns its descriptor. */
    fun writeTo(folder: Path): Path {
        FILES.forEach { (name, text) -> Files.writeString(folder.resolve(name), text) }
        return folder.resolve(DESCRIPTOR)
    }

    /** The file name of the descriptor. */
    private const val DESCRIPTOR = "paged_lamp.kenv.json"

    /** The files of the paged lamp, by name. */
    private val FILES: Map<String, String> = mapOf(
        "paged_lamp.kmeta" to """
            type "Lamp" "A lamp that tests its bulb" {
                prop("mode", "string")
                prop("trail", list("string"))
            }
        """.trimIndent(),
        "paged_lamp-events.kmeta" to """
            type "press" "Presses the switch" { }
            type "pass" "Lets a bulb test pass" { }
        """.trimIndent(),
        "paged_lamp.kmodel" to """
            make object "lamp":"Lamp" {
                prop("mode") -> "off"
            }
        """.trimIndent(),
        FRAGMENT to """
            STATEMACHINE "bulb test" FRAGMENT {
                STATES {
                    INITIAL STATE "lit" {
                        ENTRY { APPEND("trail", "lit") }
                    }
                    STATE "checked" {
                        ENTRY { APPEND("trail", "checked") }
                    }
                    FINAL STATE "passed" { }
                }
                TRANSITIONS {
                    TRANSITION "lit" -> "checked" {
                        CONDITION { EVENT("service", "pass") }
                    }
                    TRANSITION "checked" -> "passed" { }
                }
            }
        """.trimIndent(),
        MACHINE to """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" {
                        ENTRY { SET("mode", "off") }
                    }
                    CONTAINER STATE "first test" USE "bulb test" { }
                    CONTAINER STATE "second test" USE "bulb test" { }
                    STATE "on" {
                        ENTRY {
                            SET("mode", "on")
                            APPEND("trail", "on")
                        }
                    }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "first test" {
                        CONDITION { EVENT("switch", "press") }
                    }
                    TRANSITION "first test" -> "second test" {
                        CONDITION { DONE }
                    }
                    TRANSITION "second test" -> "on" {
                        CONDITION { DONE }
                    }
                }
            }
        """.trimIndent(),
        DESCRIPTOR to """
            {
              "metamodel": "paged_lamp.kmeta",
              "events": "paged_lamp-events.kmeta",
              "model": "paged_lamp.kmodel",
              "tickDelayMs": 50,
              "eventTtlMs": 30000,
              "stateMachines": [
                { "file": "$FRAGMENT" },
                { "file": "$MACHINE", "attachedTo": "$LAMP" }
              ]
            }
        """.trimIndent(),
    )
}
