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
package io.karpfen.ide.runtime.session.deploy

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.PhaseKind
import io.karpfen.ide.runtime.protocol.lifecycle.Uploads
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the environment ledger with its order, its publication and records from many threads. It also covers keeping
 * environments with the phase they were left in, and the info that an environment stays on the runtime.
 */
class EnvironmentLedgerTest {

    private fun entry(key: String, disposition: LedgerDisposition): LedgerEntry =
        LedgerEntry(ENDPOINT, EnvironmentKey(key), disposition, "Left by a test.")

    @Test
    fun `the ledger publishes its entries in the order they were recorded`() {
        val ledger = EnvironmentLedger()
        val first = entry("env-1", LedgerDisposition.STOPPED)
        val second = entry("env-2", LedgerDisposition.LEFT_CONFIGURED)

        assertEquals(emptyList(), ledger.entries.value)
        ledger.record(first)
        ledger.record(second)

        assertEquals(listOf(first, second), ledger.entries.value)
    }

    @Test
    fun `records from many threads are all kept`() {
        val ledger = EnvironmentLedger()

        val writers = (1..8).map { writer ->
            thread { repeat(250) { ledger.record(entry("env-$writer-$it", LedgerDisposition.STOPPED)) } }
        }
        writers.forEach { it.join() }

        assertEquals(2_000, ledger.entries.value.map { it.key }.toSet().size)
    }

    @Test
    fun `keeping an environment records the phase it was left in`() = runTest {
        val phases = listOf(
            EnvironmentPhase.Configuring(Uploads.NONE) to LedgerDisposition.LEFT_CONFIGURED,
            EnvironmentPhase.Activated to LedgerDisposition.LEFT_ACTIVATED,
            EnvironmentPhase.Running to LedgerDisposition.LEFT_RUNNING,
            EnvironmentPhase.Halted to LedgerDisposition.LEFT_HALTED,
            EnvironmentPhase.Stopped to LedgerDisposition.STOPPED,
            EnvironmentPhase.Lost("restarted") to LedgerDisposition.LOST,
            EnvironmentPhase.Uncertain(setOf(PhaseKind.RUNNING, PhaseKind.STOPPED), "no answer", Uploads.NONE) to LedgerDisposition.UNKNOWN,
        )
        val ledger = EnvironmentLedger()
        val keep = KeepEnvironments(ledger)

        val entries = phases.map { (phase, _) ->
            val environment = ScriptedEnvironment(ENVIRONMENT, DeployScript { 0 }).also { it.settle(phase) }
            keep.dispose(environment, ENDPOINT)
        }

        assertEquals(phases.map { it.second }, entries.map { it.disposition })
        assertEquals(entries, ledger.entries.value)
        assertTrue(entries.all { it.key == ENVIRONMENT && it.endpoint == ENDPOINT })
    }

    @Test
    fun `an environment that stays is an info naming its key, phase and runtime`() {
        val info = assertNotNull(entry("env-1761560123456", LedgerDisposition.LEFT_RUNNING).leftBehind())

        assertEquals(DeployProblemCodes.ENVIRONMENT_LEFT_BEHIND to Severity.INFO, info.code to info.severity)
        assertTrue("env-1761560123456" in info.message && "running" in info.message, info.message)
        assertTrue("http://127.0.0.1:8080" in info.message, info.message)
        assertNull(info.location)
    }

    @Test
    fun `an environment the runtime lost does not stay`() {
        assertNull(entry("env-1", LedgerDisposition.LOST).leftBehind())
    }
}
