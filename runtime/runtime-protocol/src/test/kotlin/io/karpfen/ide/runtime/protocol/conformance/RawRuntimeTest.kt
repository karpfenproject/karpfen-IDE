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

import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.conformance.RawRuntime.Companion.ENV_KEY
import io.karpfen.ide.runtime.protocol.doubles.FakeRuntimeUnderTest
import io.karpfen.ide.runtime.protocol.fake.FakeRuntime
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

/**
 * Covers the cleanup of the raw scenarios against the fake runtime on virtual time: a scenario that fails stops every
 * environment it asked to activate or start and did not stop, each stop bounded and whatever comes of it, and a
 * scenario that stopped what it launched sends no stop after it.
 */
class RawRuntimeTest {

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
    fun `a raw scenario that fails stops every environment it activated or started and did not stop`() {
        val failure = assertFailsWith<AssertionError> {
            runtime.runRaw(WEB_SOCKET_URL) {
                create()
                val activated = create()
                ok(RuntimeRoute.RUN_ENVIRONMENT, ENV_KEY to activated)
                val running = create()
                ok(RuntimeRoute.RUN_ENVIRONMENT, ENV_KEY to running)
                ok(RuntimeRoute.START_ENVIRONMENT, ENV_KEY to running)
                val stopped = create()
                ok(RuntimeRoute.RUN_ENVIRONMENT, ENV_KEY to stopped)
                ok(RuntimeRoute.START_ENVIRONMENT, ENV_KEY to stopped)
                ok(RuntimeRoute.STOP_ENVIRONMENT, ENV_KEY to stopped)
                fake.hang(RuntimeRoute.STOP_ENVIRONMENT, where = mapOf(ENV_KEY to activated))
                fake.fail(
                    RuntimeRoute.STOP_ENVIRONMENT,
                    TransportFailure.Interrupted("Connection reset"),
                    where = mapOf(ENV_KEY to running),
                )
                fail("the scenario's own assertion")
            }
        }

        assertEquals("the scenario's own assertion", failure.message)
        assertEquals(
            listOf("env-4", "env-2", "env-3"),
            fake.requests(RuntimeRoute.STOP_ENVIRONMENT).map { it.parameter(ENV_KEY) },
            "the scenario's own stop, then one stop of each environment it left launched, though the first never answers",
        )
    }

    @Test
    fun `a raw scenario that stopped what it launched sends no stop after it`() {
        runtime.runRaw(WEB_SOCKET_URL) {
            val environment = create()
            ok(RuntimeRoute.RUN_ENVIRONMENT, ENV_KEY to environment)
            ok(RuntimeRoute.START_ENVIRONMENT, ENV_KEY to environment)
            ok(RuntimeRoute.STOP_ENVIRONMENT, ENV_KEY to environment)
            ok(RuntimeRoute.START_ENVIRONMENT, ENV_KEY to environment)
            ok(RuntimeRoute.STOP_ENVIRONMENT, ENV_KEY to environment)
        }

        assertEquals(listOf("env-1", "env-1"), fake.requests(RuntimeRoute.STOP_ENVIRONMENT).map { it.parameter(ENV_KEY) })
    }

    private companion object {
        /** The WebSocket the raw scenarios would connect to; these never connect. */
        const val WEB_SOCKET_URL = "ws://127.0.0.1:8080/ws"
    }
}
