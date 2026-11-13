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
package io.karpfen.ide.ui.workbench.bridges

import io.karpfen.ide.bridges.api.BridgeState
import io.karpfen.ide.bridges.api.Delivery
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.conformance.DescriptorTiming
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.protocol.message.EventPayload
import io.karpfen.ide.runtime.protocol.message.PublishedEvent
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.message.WireValue
import io.karpfen.ide.runtime.session.ObservedDomainEvent
import io.karpfen.ide.runtime.session.Preparation
import io.karpfen.ide.runtime.session.SessionState
import io.karpfen.ide.runtime.session.events.InjectionAvailability
import io.karpfen.ide.runtime.session.fixtures.FakeRuntimeSession
import io.karpfen.ide.runtime.session.mirror.MirrorProperty
import io.karpfen.ide.runtime.session.mirror.PropertyValue
import io.karpfen.ide.runtime.session.mirror.ScalarValue
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.runtime.RuntimeState
import io.karpfen.ide.ui.workbench.testing.TestWorkbench
import io.karpfen.ide.ui.workbench.testing.withWorkbench
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.net.ConnectException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tests the bridges of a run. The sessions are scripted fakes that the tests drive through their writable flows, and
 * the HTTP bridge is the real one on a free port of this machine.
 *
 * The tests cover the run dialog that asks which bridges to start, the remembered choice, starting the bridges when the
 * environment runs and stopping them with it, a bridge that cannot be built, and the link between a bridge and the
 * session of the run.
 */
class RunBridgesTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()
    private val settingsDirectory: Path get() = dir.resolve("settings")

    @Test
    fun `a run asks for the bridges, starts the ticked ones once the environment runs and stops them with it`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        val port = freePort()
        val http = TestWorkbench.write(root.resolve("door.kbridge.json"), httpBridge(port))
        val ros = TestWorkbench.write(root.resolve("robot.kbridge.json"), """{ "kind": "ros", "settings": { "url": "robot" } }""")
        TestWorkbench.write(root.resolve("notes.json"), "{}")
        withWorkbench(settingsDirectory, scriptSessions = true) {
            open(root)

            val run = runtime.run(lamp)
            val asked = answerRun(setOf(0, 1))
            run.joinSettled()

            assertEquals(listOf(http, ros), asked.bridges)
            assertEquals(emptySet(), asked.preselected, "nothing is ticked the first time")
            val session = scriptedSessions.launched.single()
            val bridges = assertNotNull(runtime.bridges(runtime.runs.value.single()))
            val (door, robot) = bridges.runs.value
            assertEquals("door.kbridge.json", door.name)
            assertEquals(BridgeState.STOPPED, door.status.state, "the bridge waits for the environment to run")
            assertEquals(BridgeState.FAILED, robot.status.state)
            assertEquals("The address \"robot\" is no address of a rosbridge server such as ws://localhost:9090.", robot.status.problem)

            session.availabilityFlow.value = InjectionAvailability.Live
            session.stateFlow.value = SessionState.Running(ENVIRONMENT)
            settle()
            assertEquals(BridgeState.RUNNING, bridges.runs.value.first().status.state)

            assertEquals(ACCEPTED, post(port, "/press"))
            settle()
            assertEquals(listOf(EventFrame("switch", "press", EventPayload.None)), session.frames)
            assertEquals(1, bridges.runs.value.first().status.inbound.single().delivered)

            session.stateFlow.value = SessionState.Stopped(ENVIRONMENT)
            settle()
            assertEquals(BridgeState.STOPPED, bridges.runs.value.first().status.state)
            assertFailsWith<ConnectException>("the port is free again") { post(port, "/press") }
        }
    }

    @Test
    fun `the bridges of the last run are ticked the next time and stored in the project file`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        TestWorkbench.write(root.resolve("a.kbridge.json"), httpBridge(freePort()))
        val second = TestWorkbench.write(root.resolve("lab/b.kbridge.json"), """{ "kind": "ros" }""")
        withWorkbench(settingsDirectory, scriptSessions = true) {
            open(root)

            val first = runtime.run(lamp)
            val shown = answerRun(setOf(0))
            first.joinSettled()

            assertEquals(second, shown.bridges.first(), "the bridge files are listed in the order of the project tree")

            assertEquals(listOf("lab/b.kbridge.json"), state.settings.value.activeBridges)
            assertEquals(listOf(second), runtime.bridges(runtime.runs.value.first())?.runs?.value?.map { it.file })

            val again = runtime.run(lamp)
            val asked = answer(DialogRequest.ConfigureRun::class, DialogResponse.Cancel)
            again.joinSettled()

            assertEquals(setOf(0), asked.preselected, "the last choice is ticked")
            assertEquals(1, runtime.runs.value.size, "a cancelled choice runs nothing")
            assertEquals(listOf("lab/b.kbridge.json"), state.settings.value.activeBridges, "a cancelled choice is not remembered")

            val none = runtime.run(lamp)
            answerRun(emptySet())
            none.joinSettled()

            assertEquals(emptyList(), state.settings.value.activeBridges)
            assertEquals(emptyList(), runtime.bridges(runtime.runs.value.first())?.runs?.value)
        }
    }

    @Test
    fun `closing the workspace lets go of the port of a bridge`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        val port = freePort()
        TestWorkbench.write(root.resolve("door.kbridge.json"), httpBridge(port))
        withWorkbench(settingsDirectory, scriptSessions = true) {
            open(root)
            val run = runtime.run(lamp)
            answerRun(setOf(0))
            run.joinSettled()
            val session = scriptedSessions.launched.single()
            session.availabilityFlow.value = InjectionAvailability.Live
            session.stateFlow.value = SessionState.Running(ENVIRONMENT)
            settle()
            assertEquals(ACCEPTED, post(port, "/press"))

            open(Files.createDirectories(dir.resolve("other")).toAbsolutePath().normalize())

            assertFailsWith<ConnectException> { post(port, "/press") }
        }
    }

    @Test
    fun `the link hands the events of a bridge to the session and says why one is not taken`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        withWorkbench(settingsDirectory) {
            open(root)
            val session = FakeRuntimeSession(assertIs<Preparation.Ready>(realSessions.prepare(workspace, lamp)).prepared)
            val link = SessionModelLink(session)
            val press = EventFrame("switch", "press", EventPayload.None)

            assertEquals(Delivery.Failed("The environment is still being deployed."), link.send(press))

            session.availabilityFlow.value = InjectionAvailability.Live
            assertEquals(Delivery.Delivered, link.send(press))
            assertEquals(listOf(press), session.frames)

            val refused = assertIs<Delivery.Failed>(link.send(EventFrame("switch", " ", EventPayload.None)))
            assertTrue(refused.reason.isNotBlank())
            assertEquals(listOf(press), session.frames, "a refused event is not sent")
        }
    }

    @Test
    fun `the link tells the values once at the start, then every change and every domain event`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        withWorkbench(settingsDirectory) {
            open(root)
            val session = FakeRuntimeSession(assertIs<Preparation.Ready>(realSessions.prepare(workspace, lamp)).prepared)
            val pushes = ArrayList<RuntimePush>()
            val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) { SessionModelLink(session).pushes.toList(pushes) }
            settle()

            val objects = pushes.filterIsInstance<RuntimePush.ObjectChanged>()
            assertEquals(session.model.value.mirror.objects.keys, objects.map { it.objectId }.toSet())
            assertNotNull(objects.single { it.objectId == "bulb" }.value["watts"])
            assertEquals(
                setOf("lamp", "wallSwitch"),
                pushes.filterIsInstance<RuntimePush.StateChanged>().map { it.modelElementId }.toSet(),
            )
            pushes.clear()

            val model = session.model.value
            val bulb = assertNotNull(model.mirror.objects["bulb"])
            val dimmed = bulb.copy(properties = listOf(MirrorProperty("watts", PropertyValue.Single(ScalarValue.Number(30.0)))))
            session.modelFlow.value = model.copy(mirror = model.mirror.copy(objects = model.mirror.objects + ("bulb" to dimmed)))
            settle()

            val change = assertIs<RuntimePush.ObjectChanged>(pushes.single())
            assertEquals("bulb", change.objectId)
            assertEquals(30.0, assertIs<WireValue.WireNumber>(change.value["watts"]).value)
            pushes.clear()

            val event = PublishedEvent("press", "", "none", "EXTERNAL_MESSAGE", 0, 1000)
            session.domainEventFlow.emit(ObservedDomainEvent("switch", event, "{}", Duration.ZERO))
            settle()

            assertEquals(listOf<RuntimePush>(RuntimePush.DomainEvent("switch", event, "{}")), pushes)
            assertTrue(collector.isActive, "the link keeps reading")
            collector.cancel()
        }
    }

    /** Copies [model] into a folder of its own below the workspace root and returns its descriptor. */
    private fun copy(model: SynthesizedModel): Path =
        SynthesizedModels.copyTo(root.resolve(model.folder), model, DescriptorTiming.EVENT_SCENARIOS).toAbsolutePath().normalize()

    /** The runtime state of the opened workspace. */
    private val TestWorkbench.runtime: RuntimeState
        get() = assertNotNull(state.runtime.value, "no runtime state")

    /** The text of an HTTP bridge file that listens on [port] and turns a request to `/press` into `switch/press`. */
    private fun httpBridge(port: Int): String =
        """{ "kind": "http", "settings": { "port": "$port" }, "inbound": [ { "from": "/press", "event": "switch/press" } ] }"""

    /** Posts an empty request to [path] on [port] of this machine and returns the status of the answer. */
    private fun post(port: Int, path: String): Int {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).POST(HttpRequest.BodyPublishers.noBody()).build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    /** A port of this machine that nothing listens on right now. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        val TIMEOUT = 60.seconds

        /** The status of an accepted request. */
        const val ACCEPTED = 202

        /** The environment the scripted session pretends to run. */
        val ENVIRONMENT = EnvironmentKey("env-1")
    }
}
