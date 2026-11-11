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
package io.karpfen.ide.runtime.protocol.connection

import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.doubles.FakeRuntimeUnderTest
import io.karpfen.ide.runtime.protocol.fake.FakeRuntime
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * Covers the cleanup of the connection scenarios against the fake runtime on virtual time. A scenario that fails stops
 * every environment it deployed that is activated or running. It sends nothing for one that is stopped already or was
 * never activated.
 */
class ScenarioClientTest {

    /** Where the fake runs the follow-ups of requests, which these tests never program. */
    private val followUps = CoroutineScope(Job())

    /** The fake runtime the scenarios reach. */
    private val fake = FakeRuntime(followUps)

    /** The runtime under test over the fake. */
    private val runtime = FakeRuntimeUnderTest(fake)

    @AfterTest
    fun cancelFollowUps() {
        followUps.cancel()
    }

    @Test
    fun `a client scenario that fails stops every environment it deployed and left activated or running`() {
        val failure = assertFailsWith<AssertionError> {
            runtime.runClient(ENDPOINT) {
                deployLamp()
                val activated = deployLamp()
                assertIs<CallOutcome.Done<Unit>>(activated.activate())
                val running = deployLamp()
                assertIs<CallOutcome.Done<Unit>>(running.activate())
                assertIs<CallOutcome.Done<Unit>>(running.start())
                val stopped = deployLamp()
                assertIs<CallOutcome.Done<Unit>>(stopped.activate())
                assertIs<CallOutcome.Done<Unit>>(stopped.stop())
                fail("the scenario's own assertion")
            }
        }

        assertEquals("the scenario's own assertion", failure.message)
        assertEquals(
            listOf("env-4", "env-2", "env-3"),
            fake.requests(RuntimeRoute.STOP_ENVIRONMENT).map { it.parameter(ENV_KEY) },
            "the scenario's own stop, then one stop of each environment it left activated or running",
        )
    }

    private companion object {
        /** The runtime the client believes it talks to. The fake answers whatever the URL is. */
        val ENDPOINT: RuntimeEndpoint = RuntimeEndpoint.parse("http://127.0.0.1:8080")

        /** The query parameter of an environment's key. */
        const val ENV_KEY = "envKey"
    }
}
