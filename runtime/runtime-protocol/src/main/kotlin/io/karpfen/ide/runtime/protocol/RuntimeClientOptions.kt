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
package io.karpfen.ide.runtime.protocol

import io.karpfen.ide.runtime.protocol.api.RuntimeTimeouts
import io.karpfen.ide.runtime.protocol.connection.ReconnectPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.time.TimeSource

/**
 * How a [RuntimeClient] runs, with the production defaults; tests hand in a test dispatcher and its time source, so
 * every delay, timeout and backoff runs on virtual time.
 *
 * @property dispatcher where the client runs its calls, delays and timeouts.
 * @property timeSource the clock of the client's measurements.
 * @property timeouts how long the client waits for the runtime's answers.
 * @property reconnect how connections retry.
 */
public data class RuntimeClientOptions(
    public val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    public val timeSource: TimeSource = TimeSource.Monotonic,
    public val timeouts: RuntimeTimeouts = RuntimeTimeouts(),
    public val reconnect: ReconnectPolicy = ReconnectPolicy(),
)
