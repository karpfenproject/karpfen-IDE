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
package io.karpfen.ide.runtime.session

import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.session.deploy.DeploymentResult
import io.karpfen.ide.runtime.session.deploy.EnvironmentLedger
import io.karpfen.ide.runtime.session.deploy.LedgerDisposition
import io.karpfen.ide.runtime.session.deploy.LedgerEntry
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides how the environment of a session leaves it. The decision is made once, even when closing races the end of
 * the deployment or a stop. The session records its environment in the ledger at most once. When the deployment
 * failed, the deployment's cleanup records it instead. A deployment can succeed while the session is closed at the same
 * time. Whoever of the two learns about the other second takes care of the deployed environment.
 *
 * Every function may be called from any thread. Closing, the end of the deployment and the session's own stop are
 * decided under one lock.
 *
 * @param ledger where the environment is recorded.
 * @param endpoint the runtime the environment is on.
 */
internal class EnvironmentDeparture(private val ledger: EnvironmentLedger, private val endpoint: RuntimeEndpoint) {
    /** Guards [closed] and [deployed]. */
    private val lock = Any()

    /** True once the session was closed. */
    private var closed = false

    /** The deployment's result once it succeeded. */
    private var deployed: DeploymentResult.Deployed? = null

    /** True once the environment was recorded. */
    private val recorded = AtomicBoolean(false)

    /** True while the session's own stop call runs. That call records the environment itself. */
    private var stopping = false

    /**
     * Takes the deployment's [result] and returns true if the session was closed while the deployment ran. In that
     * case the caller takes care of a deployed environment, because closing found no deployment to leave.
     */
    fun deployEnded(result: DeploymentResult): Boolean =
        synchronized(lock) {
            deployed = result as? DeploymentResult.Deployed
            closed
        }

    /**
     * Marks the session as closed and returns the deployed environment it leaves. Returns null if the deployment still
     * runs, because its cancellation cleans up. Also returns null if the deployment failed, because its cleanup
     * recorded the environment already.
     */
    fun close(): Leaving? =
        synchronized(lock) {
            closed = true
            deployed?.let { Leaving(it, records = !stopping) }
        }

    /** Marks that the session's own stop call starts. */
    fun stopStarts() {
        synchronized(lock) { stopping = true }
    }

    /**
     * Marks that the session's own stop call ended and returns true if the session was closed meanwhile. In that case
     * closing left the recording to the stop, so the caller records the environment as the stop left it.
     */
    fun stopEnded(): Boolean =
        synchronized(lock) {
            stopping = false
            closed
        }

    /**
     * Records [environment] as [disposition] with [note], unless it was recorded already. Returns the new entry, or
     * null if nothing was recorded.
     */
    fun record(environment: EnvironmentKey, disposition: LedgerDisposition, note: String): LedgerEntry? {
        if (!recorded.compareAndSet(false, true)) return null
        return LedgerEntry(endpoint, environment, disposition, note).also(ledger::record)
    }

    /** Records [environment] by the [phase] it is left in, with [note], unless it was recorded already. */
    fun leave(environment: EnvironmentKey, phase: EnvironmentPhase, note: String): LedgerEntry? =
        record(environment, LedgerDisposition.of(phase), note)
}

/**
 * The deployed environment a closed session leaves.
 *
 * @property deployed the deployment's result.
 * @property records true if closing records the environment. It is false while the session's own stop call runs,
 *   because that call records the environment when it ends.
 */
internal data class Leaving(val deployed: DeploymentResult.Deployed, val records: Boolean)
