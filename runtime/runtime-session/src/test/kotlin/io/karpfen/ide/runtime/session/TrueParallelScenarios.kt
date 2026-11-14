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
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.session.deploy.DeployOptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * Covers the runtime feature TrueParallel through a session against a real runtime. The relay below splits into a
 * left and a right branch on the event `go` of the domain `signal`. Each branch takes its own steps, writes its own
 * property and appends its name to the shared list `trail`, and a join then leads both branches into `joined`.
 * The project has splits and joins switched on and no other language feature, so the plan itself switches no runtime
 * feature on.
 *
 * Deployed with [DeployOptions.trueParallel], the environment has exactly TrueParallel active, and the relay reaches the
 * same final model as without it. The order of the two names in `trail` is not fixed while the branches step at the
 * same time, so the scenarios compare it sorted. The session cannot read the runtime's log, which would name the
 * threads `Branch-<id>`, so these scenarios prove that the feature runs end to end through the deployment, and the
 * runtime's engine tests (`TrueParallelEngineTest`) prove that the branches step at the same time on threads of
 * their own. Without the option, no feature is active and the branches step one after the other.
 */
abstract class TrueParallelScenarios {

    /** The runtime the scenarios deploy to. */
    protected abstract val runtime: SessionUnderTest

    /** The folder the scenarios write their models into. */
    @TempDir
    lateinit var root: Path

    @Test
    fun `with TrueParallel the feature is active, both branches write the model, and the join fires`() = runtime.run(root) {
        val session = deployRelay(DeployOptions(trueParallel = true))

        assertEquals(setOf(TRUE_PARALLEL), activeFeatures(session), diagnostics)
        awaitJoined(session)
    }

    @Test
    fun `without TrueParallel the same relay reaches the same model and no feature is active`() = runtime.run(root) {
        val session = deployRelay(DeployOptions())

        assertEquals(emptySet(), activeFeatures(session), diagnostics)
        awaitJoined(session)
    }

    /** Writes the relay into a folder of its own, opens it with splits and joins on, and deploys it as [options] say. */
    private suspend fun SessionScenario.deployRelay(options: DeployOptions): RuntimeSession {
        val folder = Files.createDirectories(root.resolve(if (options.trueParallel) "relay-parallel" else "relay-plain"))
        val descriptor = RelayModel.writeTo(folder)
        val prepared = prepare(open(descriptor, features = setOf(KstatesFeature.PARALLEL)), descriptor)
        val session = deploy(prepared, options)
        session.awaitStack(RELAY, listOf("idle"))
        return session
    }

    /** The features the runtime reports active in the environment of [session]. */
    private suspend fun SessionScenario.activeFeatures(session: RuntimeSession): Set<String> =
        call(RuntimeRoute.GET_ACTIVE_FEATURES, "envKey" to session.environment.value).body.split(",").filter { it.isNotEmpty() }.toSet()

    /** Sends `go` and waits until the join fired and the model holds what both branches wrote. */
    private suspend fun SessionScenario.awaitJoined(session: RuntimeSession) {
        session.inject("signal", "go")
        session.awaitStack(RELAY, listOf("joined"))
        val model = session.awaitModel("what both branches and the join wrote") {
            val relay = it.mirror.objectOf(RELAY)
            relay.text("phase") == "joined" && relay.texts("trail")?.size == 2
        }
        val relay = model.mirror.objectOf(RELAY)
        assertEquals("done", relay.text("left"), diagnostics)
        assertEquals("done", relay.text("right"), diagnostics)
        assertEquals(listOf("left", "right"), relay.texts("trail")?.sorted(), diagnostics)
    }

    private companion object {
        /** The runtime's name of the feature. */
        const val TRUE_PARALLEL = "TrueParallel"

        /** The key of the relay object, which the state machine is attached to. */
        const val RELAY = RelayModel.RELAY
    }
}

/**
 * The relay of [TrueParallelScenarios]: a metamodel, event definitions, a model with the one object `relay`, a state
 * machine that splits into two branches and joins them again, and a descriptor with a tick delay of 50 ms and an event
 * time to live of 30 000 ms. It uses no EVAL and no macro, so it needs no Python. Each branch writes in a state of its
 * own before it moves on to the source of the join, because the runtime skips the entry block of a state that a join
 * leaves in the next tick.
 */
internal object RelayModel {
    /** The key of the relay object, which the state machine is attached to. */
    const val RELAY = "relay"

    /** Writes the files of the relay into [folder] and returns its descriptor. */
    fun writeTo(folder: Path): Path {
        FILES.forEach { (name, text) -> Files.writeString(folder.resolve(name), text) }
        return folder.resolve(DESCRIPTOR)
    }

    /** The file name of the descriptor. */
    private const val DESCRIPTOR = "relay.kenv.json"

    /** The files of the relay, by name. */
    private val FILES: Map<String, String> = mapOf(
        "relay.kmeta" to """
            type "Relay" "Passes a signal along two branches" {
                prop("phase", "string")
                prop("left", "string")
                prop("right", "string")
                prop("trail", list("string"))
            }
        """.trimIndent(),
        "relay-events.kmeta" to """
            type "go" "Starts both branches" { }
        """.trimIndent(),
        "relay.kmodel" to """
            make object "relay":"Relay" {
                prop("phase") -> "idle"
                prop("left") -> "waiting"
                prop("right") -> "waiting"
            }
        """.trimIndent(),
        "relay.kstates" to """
            STATEMACHINE ATTACHED TO "Relay" {
                STATES {
                    INITIAL STATE "idle" { }
                    STATE "left start" { }
                    STATE "right start" { }
                    STATE "left work" {
                        ENTRY {
                            SET("left", "done")
                            APPEND("trail", "left")
                        }
                    }
                    STATE "right work" {
                        ENTRY {
                            SET("right", "done")
                            APPEND("trail", "right")
                        }
                    }
                    STATE "left done" { }
                    STATE "right done" { }
                    STATE "joined" {
                        ENTRY { SET("phase", "joined") }
                    }
                }
                TRANSITIONS {
                    SPLIT "idle" -> "left start", "right start" {
                        CONDITION { EVENT("signal", "go") }
                    }
                    TRANSITION "left start" -> "left work" { }
                    TRANSITION "right start" -> "right work" { }
                    TRANSITION "left work" -> "left done" { }
                    TRANSITION "right work" -> "right done" { }
                    JOIN "left done", "right done" -> "joined" { }
                }
            }
        """.trimIndent(),
        DESCRIPTOR to """
            {
              "metamodel": "relay.kmeta",
              "events": "relay-events.kmeta",
              "model": "relay.kmodel",
              "tickDelayMs": 50,
              "eventTtlMs": 30000,
              "stateMachines": [
                { "file": "relay.kstates", "attachedTo": "relay" }
              ]
            }
        """.trimIndent(),
    )
}
