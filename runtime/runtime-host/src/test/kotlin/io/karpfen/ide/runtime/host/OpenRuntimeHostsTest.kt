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
package io.karpfen.ide.runtime.host

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [OpenRuntimeHosts] over hosts that only count how often they are closed. The tests check that closing the
 * factory closes the hosts still open and no host twice, that a host created afterwards is closed at once, and that the
 * factory waits for a host that is being closed by someone else.
 */
class OpenRuntimeHostsTest {

    /** The hosts the factory under test created, in order. */
    private val created = ArrayList<CountingHost>()

    /** The factory under test, over hosts that count their closes. */
    private val hosts = OpenRuntimeHosts { _ -> CountingHost().also { created += it } }

    @Test
    fun `closing the factory closes the hosts that are still open, each once`() {
        val first = create()
        create()
        first.close()

        hosts.close()
        hosts.close()

        assertEquals(listOf(1, 1), created.map { it.closes.get() })
    }

    @Test
    fun `a host created after the factory was closed is closed at once`() {
        hosts.close()

        create()

        assertEquals(listOf(1), created.map { it.closes.get() })
    }

    @Test
    fun `the factory waits for a host that is being closed elsewhere`() {
        val host = create()
        val slow = created.single()
        slow.hold = CountDownLatch(1)
        val closingHost = thread { host.close() }
        slow.entered.await()

        val closingFactory = thread { hosts.close() }
        while (closingFactory.state != Thread.State.BLOCKED) {
            assertTrue(closingFactory.isAlive, "the factory returned while its host was still being closed")
            Thread.onSpinWait()
        }
        slow.hold?.countDown()
        closingHost.join()
        closingFactory.join()

        assertEquals(1, slow.closes.get())
    }

    /** A host of the factory under test. */
    private fun create(): RuntimeHost = hosts.create(Path.of("workspace"))

    /** A host that counts its closes. With [hold] set, a close waits for it after [entered] opened. */
    private class CountingHost : RuntimeHost {
        /** How often [close] was called. */
        val closes = AtomicInteger()

        /** Opens when a close began. */
        val entered = CountDownLatch(1)

        /** What a close waits for, or null. */
        @Volatile
        var hold: CountDownLatch? = null

        override val status: StateFlow<HostStatus> = MutableStateFlow(HostStatus.Stopped)
        override val logs: Flow<HostLogLine> = emptyFlow()

        override suspend fun ensureRunning(): HostOutcome =
            HostOutcome.Failed(HostProblem(HostProblemCode.CONTAINER_FAILED, "This host runs nothing."))

        override suspend fun stop() = Unit

        override fun close() {
            closes.incrementAndGet()
            entered.countDown()
            hold?.await()
        }
    }
}
