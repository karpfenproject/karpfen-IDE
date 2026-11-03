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
package io.karpfen.ide.runtime.protocol.conformance

import io.karpfen.ide.runtime.protocol.RuntimeClientOptions
import io.karpfen.ide.runtime.protocol.transport.ConnectOutcome
import io.karpfen.ide.runtime.protocol.transport.HttpOutcome
import io.karpfen.ide.runtime.protocol.transport.RuntimeTransports
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the waiting helpers and [Patience] on virtual time: [eventually] polls at the scope's pace until its probe
 * answers, takes a failed assertion for "not yet", and fails after its bound naming what it awaited, the bound, the last
 * answer and the diagnostics; [awaitValue] answers the first accepted value of a flow and fails the same way when none
 * comes or the flow ends; the live patience has the documented bounds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaitingTest {

    /** A scenario scope on the test's virtual time, with transports that are never used. */
    private class VirtualScenarioScope(scope: TestScope) : RuntimeScenarioScope, CoroutineScope by scope {
        override val transports: RuntimeTransports = RuntimeTransports(
            http = { HttpOutcome.Failed(TransportFailure.Unreachable("unused")) },
            webSockets = { ConnectOutcome.Failed(TransportFailure.Unreachable("unused")) },
        )
        override val clientOptions: RuntimeClientOptions = RuntimeClientOptions(dispatcher = StandardTestDispatcher(scope.testScheduler))
        override val patience: Patience = Patience.LIVE
        override val diagnostics: String = "runtime: virtual"
    }

    @Test
    fun `eventually polls every quarter second until the probe answers`() = runTest {
        val scope = VirtualScenarioScope(this)
        var probes = 0

        val answer = scope.eventually("the third second") {
            probes++
            if (testScheduler.currentTime >= 3.seconds.inWholeMilliseconds) "ready" else null
        }

        assertEquals("ready", answer)
        assertEquals(3.seconds.inWholeMilliseconds, testScheduler.currentTime)
        assertEquals(13, probes, "one probe at once and one after every 250 ms")
    }

    @Test
    fun `a failed assertion of the probe means not yet`() = runTest {
        val scope = VirtualScenarioScope(this)
        var probes = 0

        val answer = scope.eventually("the second probe") {
            probes++
            assertTrue(probes >= 2, "not yet")
            probes
        }

        assertEquals(2, answer)
    }

    @Test
    fun `eventually fails after its bound naming what it awaited, the bound, the last answer and the diagnostics`() = runTest {
        val scope = VirtualScenarioScope(this)

        val failure = assertFailsWith<AssertionError> {
            scope.eventually<String>("the lamp's stack [\"on\"]", within = 5.seconds) {
                assertEquals(listOf("on"), listOf("off"), "the lamp's stack")
                "never"
            }
        }

        val message = failure.message.orEmpty()
        assertTrue("the lamp's stack [\"on\"]" in message, message)
        assertTrue("5s" in message, message)
        assertTrue("[off]" in message, message)
        assertTrue("runtime: virtual" in message, message)
        assertEquals(5.seconds.inWholeMilliseconds, testScheduler.currentTime)
    }

    @Test
    fun `any other failure of the probe ends the wait at once`() = runTest {
        val scope = VirtualScenarioScope(this)

        assertFailsWith<IllegalStateException> { scope.eventually<String>("a broken probe") { error("broken") } }
        assertEquals(0, testScheduler.currentTime)
    }

    @Test
    fun `awaitValue answers the first accepted value of a flow`() = runTest {
        val scope = VirtualScenarioScope(this)
        val stacks = MutableSharedFlow<List<String>>(replay = 10)
        launch {
            stacks.emit(listOf("off"))
            delay(2.seconds)
            stacks.emit(listOf("on"))
        }

        val stack = scope.awaitValue("the stack [\"on\"]", stacks) { it == listOf("on") }

        assertEquals(listOf("on"), stack)
        assertEquals(2.seconds.inWholeMilliseconds, testScheduler.currentTime)
    }

    @Test
    fun `awaitValue fails with the flow's last value when no accepted value comes or the flow ends`() = runTest {
        val scope = VirtualScenarioScope(this)
        val stacks = MutableSharedFlow<List<String>>(replay = 10)
        stacks.emit(listOf("off"))

        val timedOut = assertFailsWith<AssertionError> { scope.awaitValue("the stack [\"on\"]", stacks, within = 10.seconds) { it == listOf("on") } }
        val ended = assertFailsWith<AssertionError> { scope.awaitValue("a two", flowOf(1, 3)) { it == 2 } }

        assertTrue("[off]" in timedOut.message.orEmpty() && "10s" in timedOut.message.orEmpty(), timedOut.message)
        assertTrue("ended" in ended.message.orEmpty() && "3" in ended.message.orEmpty(), ended.message)
    }

    @Test
    fun `the live patience is generous`() {
        assertEquals(
            Patience(
                deploy = 60.seconds,
                reaction = 30.seconds,
                burst = 60.seconds,
                crowd = 180.seconds,
                scenario = 5.minutes,
                pollEvery = 250.milliseconds,
            ),
            Patience.LIVE,
        )
    }
}
