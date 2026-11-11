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

import io.karpfen.ide.runtime.protocol.catalogue.TimeoutClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the default of every timeout class in [RuntimeTimeouts], the lookup by class and the refusal of empty waits.
 */
class RuntimeTimeoutsTest {

    @Test
    fun `the defaults are ten seconds for quick calls and thirty for the others`() {
        val timeouts = RuntimeTimeouts()

        assertEquals(
            mapOf(TimeoutClass.QUICK to 10.seconds, TimeoutClass.UPLOAD to 30.seconds, TimeoutClass.STOP to 30.seconds, TimeoutClass.SNAPSHOT to 30.seconds),
            TimeoutClass.entries.associateWith { timeouts.of(it) },
        )
    }

    @Test
    fun `each class answers its own timeout`() {
        val timeouts = RuntimeTimeouts(quick = 1.seconds, upload = 2.seconds, stop = 3.seconds, snapshot = 4.seconds)

        assertEquals(listOf(1.seconds, 2.seconds, 3.seconds, 4.seconds), TimeoutClass.entries.map { timeouts.of(it) })
    }

    @Test
    fun `a timeout must be positive`() {
        assertFailsWith<IllegalArgumentException> { RuntimeTimeouts(quick = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { RuntimeTimeouts(snapshot = (-1).seconds) }
    }
}
