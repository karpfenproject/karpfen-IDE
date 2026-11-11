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
package io.karpfen.ide.runtime.protocol.api

import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.message.ProtocolViolation
import io.karpfen.ide.runtime.protocol.message.ViolationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers [RuntimeResult] and [RuntimeFailure]. The tests cover the extension functions on successes and failures, the
 * reach of every failure, and its description as one plain sentence that names the route.
 */
class RuntimeResultTest {

    private val failure = RuntimeFailure.Unreachable(RuntimeRoute.HEALTH, "Connection refused")

    @Test
    fun `map and flatMap transform a success and pass a failure on`() {
        val ok: RuntimeResult<Int> = RuntimeResult.Ok(2)
        val failed: RuntimeResult<Int> = RuntimeResult.Failed(failure)

        assertEquals(RuntimeResult.Ok("2"), ok.map { it.toString() })
        assertSame<Any>(failed, failed.map { it.toString() })
        assertEquals(RuntimeResult.Ok(4), ok.flatMap { RuntimeResult.Ok(it * 2) })
        assertEquals(RuntimeResult.Failed(failure), ok.flatMap { RuntimeResult.Failed(failure) })
        assertSame<Any>(failed, failed.flatMap { RuntimeResult.Ok(it * 2) })
    }

    @Test
    fun `getOrNull answers the value of a success and onFailure runs only for a failure`() {
        val seen = mutableListOf<RuntimeFailure>()

        assertEquals(2, RuntimeResult.Ok(2).onFailure { seen += it }.getOrNull())
        assertNull(RuntimeResult.Failed(failure).onFailure { seen += it }.getOrNull())
        assertEquals(listOf<RuntimeFailure>(failure), seen)
    }

    @Test
    fun `the reach tells whether the runtime may have applied the call`() {
        assertEquals(
            listOf(Reach.NOT_SENT, Reach.MAYBE_APPLIED, Reach.ANSWERED, Reach.ANSWERED, Reach.ANSWERED, Reach.ANSWERED),
            failures().map { it.reach },
        )
    }

    @Test
    fun `every failure describes itself in one sentence that names the route`() {
        assertEquals(
            listOf(
                "The runtime could not be reached for POST /runEnvironment: Connection refused.",
                "The runtime did not answer POST /runEnvironment in time, so the call may or may not have been applied.",
                "The runtime rejected POST /runEnvironment with status 409: " +
                    "Environment with key env-1 must have both a metamodel and a model before it can be run.",
                "The runtime failed with an internal error on POST /runEnvironment: boom.",
                "The runtime answered POST /runEnvironment with the unexpected status 404: Not Found.",
                "The runtime's answer to POST /runEnvironment broke the protocol: not an environment key.",
            ),
            failures().map { it.describe() },
        )
        assertEquals(
            "The connection broke during POST /runEnvironment (Connection reset), so the call may or may not have been applied.",
            RuntimeFailure.NoAnswer(RuntimeRoute.RUN_ENVIRONMENT, "Connection reset.", timedOut = false).describe(),
        )
        assertEquals(
            "The runtime answered GET /observatory/model with the unexpected status 404.",
            RuntimeFailure.UnexpectedStatus(RuntimeRoute.MODEL_SNAPSHOT, 404, "").describe(),
        )
        assertTrue(failures().all { it.describe().count { char -> char == '\n' } == 0 })
    }

    /** One failure of every kind, all of `POST /runEnvironment`. */
    private fun failures(): List<RuntimeFailure> {
        val route = RuntimeRoute.RUN_ENVIRONMENT
        return listOf(
            RuntimeFailure.Unreachable(route, "Connection refused"),
            RuntimeFailure.NoAnswer(route, "timed out", timedOut = true),
            RuntimeFailure.Rejected(
                route, 409, RejectionKind.MODELS_REQUIRED,
                "Environment with key env-1 must have both a metamodel and a model before it can be run", mapOf("env" to "env-1"),
            ),
            RuntimeFailure.ServerFault(route, "boom"),
            RuntimeFailure.UnexpectedStatus(route, 404, "Not\n Found"),
            RuntimeFailure.ProtocolViolated(route, ProtocolViolation(ViolationKind.UNEXPECTED_BODY, "not an environment key", "nope")),
        )
    }
}
