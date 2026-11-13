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
package io.karpfen.ide.app.headless

import io.karpfen.ide.app.launch.ExitSequence
import io.karpfen.ide.runtime.protocol.conformance.DescriptorTiming
import io.karpfen.ide.runtime.protocol.conformance.LiveRuntime
import io.karpfen.ide.runtime.protocol.conformance.LiveRuntimeUnderTest
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.runtime.protocol.conformance.eventually
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ConnectException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Runs the synthesized lamp headless against the real runtime that the live task is given, usually a throwaway
 * container of `runtime/live-runtime.sh test`. It stands in for `karpfen-ide run <folder> --bridge http.kbridge.json`
 * in a terminal: the run starts an HTTP bridge on a port of this machine, and the wiring is the production wiring of
 * [HeadlessLaunch] on a thread of its own, except that [RunningRuntimeHosts] hand it the live runtime in place of the
 * IDE's Docker container.
 *
 * The test reads what the run writes. Once the run and its bridge run, it presses the wall switch
 * through the bridge and waits until the lamp is reported on. Then it runs the shutdown sequence as a terminate
 * signal would, through the one hook that the launch registered, and checks that the run shut down, ended with 0 and
 * let go of the bridge's port.
 */
@LiveRuntime
class LiveHeadlessRunTest {

    @TempDir
    lateinit var dir: Path

    private val runtime = LiveRuntimeUnderTest()

    @Test
    fun `a headless run moves the lamp through an HTTP bridge and shuts down on the signal`() = runtime.run {
        val root = dir.resolve("lamp").toAbsolutePath().normalize()
        SynthesizedModels.copyTo(root, SynthesizedModel.LAMP, DescriptorTiming.EVENT_SCENARIOS)
        val port = freePort()
        val bridge = Files.writeString(
            root.resolve("http.kbridge.json"),
            """
            {
              "kind": "http",
              "settings": { "port": "$port" },
              "inbound": [ { "from": "/press", "event": "switch/press" } ]
            }
            """.trimIndent(),
        )
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val console = HeadlessConsole(PrintStream(output, true, Charsets.UTF_8), PrintStream(errors, true, Charsets.UTF_8))
        val hooks = CopyOnWriteArrayList<Thread>()
        val launch = HeadlessLaunch(console, ExitSequence { hooks += it }, RunningRuntimeHosts(runtime.endpoint))
        val status = AtomicInteger(NOT_ENDED)
        val running = thread(name = "live-headless-run") {
            status.set(launch.run(HeadlessCommand.Run(root, bridges = listOf(bridge))))
        }
        try {
            eventually("the run and its bridge running", patience.deploy) {
                assertTrue("Run lamp: running" in lines(output) && BRIDGE_RUNNING in lines(output), "${lines(output)} ${lines(errors)}")
            }

            assertEquals(ACCEPTED, post(port, "/press"), diagnostics)
            eventually("the lamp reported on after the press") {
                assertTrue("lamp: on" in lines(output), "${lines(output)} ${lines(errors)}")
            }

            withContext(Dispatchers.IO) {
                hooks.single().run()
                running.join()
            }

            println(lines(output).joinToString(separator = "\n", prefix = "What the headless run printed:\n"))
            assertEquals(HeadlessExitCodes.OK, status.get(), "${lines(output)} ${lines(errors)}")
            val closing = lines(output).dropWhile { it != RunLines.STOP_REQUESTED }
            assertEquals(
                listOf(RunLines.STOP_REQUESTED, RunLines.SHUTTING_DOWN, RunLines.SHUT_DOWN),
                closing.filter { it in setOf(RunLines.STOP_REQUESTED, RunLines.SHUTTING_DOWN, RunLines.SHUT_DOWN) },
                "the closing lines in their order, with what the bridges and the runtime say in between: $closing",
            )
            assertEquals(RunLines.SHUT_DOWN, closing.last())
            assertEquals("Running lamp.kenv.json with http.kbridge.json", lines(output).first())
            assertTrue(lines(errors).none { it.startsWith("karpfen-ide:") }, "${lines(errors)}")
            assertFailsWith<ConnectException>("the port of the bridge is free again") { post(port, "/press") }
        } finally {
            withContext(Dispatchers.IO) {
                if (running.isAlive) {
                    hooks.forEach { it.run() }
                    running.join()
                }
            }
        }
    }

    /** The lines written to [stream] so far. */
    private fun lines(stream: ByteArrayOutputStream): List<String> =
        stream.toString(Charsets.UTF_8).lines().dropLastWhile { it.isEmpty() }

    /** Posts an empty request to [path] on [port] of this machine and returns the status of the answer. */
    private fun post(port: Int, path: String): Int {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).POST(HttpRequest.BodyPublishers.noBody()).build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    /** A port of this machine that nothing listens on right now. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        /** The status of an accepted request. */
        const val ACCEPTED = 202

        /** The line of the bridge once it runs and has passed nothing on yet. */
        const val BRIDGE_RUNNING = "http.kbridge.json: running, 0 in, 0 out"

        /** The status before the run ended. */
        const val NOT_ENDED = -1
    }
}
