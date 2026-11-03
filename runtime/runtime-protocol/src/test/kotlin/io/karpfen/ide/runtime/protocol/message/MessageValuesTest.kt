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
package io.karpfen.ide.runtime.protocol.message

import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Covers the message values: the lifecycle phases by their wire names, the invariants of snapshots and protocol
 * violations, and the equality of numbers that the runtime writes as non-finite literals.
 */
class MessageValuesTest {

    @Test
    fun `phases are found by the names the snapshot writes`() {
        assertEquals(
            listOf("configuring", "activated", "running", "halted", "stopped"),
            RuntimePhase.entries.map { it.wireName },
        )
        RuntimePhase.entries.forEach { assertEquals(it, RuntimePhase.ofWireName(it.wireName)) }
        assertNull(RuntimePhase.ofWireName("RUNNING"))
        assertNull(RuntimePhase.ofWireName(""))
    }

    @Test
    fun `a snapshot's revision is never negative`() {
        val snapshot = RuntimeModelSnapshot(EnvironmentKey("env-1"), RuntimePhase.ACTIVATED, 0, emptyMap(), emptyList())
        assertEquals(0, snapshot.revision)
        assertFailsWith<IllegalArgumentException> { snapshot.copy(revision = -1) }
    }

    @Test
    fun `an excerpt of a violation holds at most 200 characters`() {
        val longest = "x".repeat(200)
        assertEquals(longest, ProtocolViolation(ViolationKind.UNDECODABLE_FRAME, "not JSON", longest).excerpt)
        assertFailsWith<IllegalArgumentException> { ProtocolViolation(ViolationKind.UNDECODABLE_FRAME, "not JSON", longest + "x") }
    }

    @Test
    fun `non-finite numbers equal themselves, so read values compare by content`() {
        assertEquals(WireValue.WireNumber(Double.NaN), WireValue.WireNumber(Double.NaN))
        assertEquals(
            WireValue.WireObject(listOf(WireField("x", WireValue.WireNumber(Double.POSITIVE_INFINITY)))),
            (RuntimeJson.read("""{"x": Infinity}""") as JsonReading.Read).value,
        )
    }
}
