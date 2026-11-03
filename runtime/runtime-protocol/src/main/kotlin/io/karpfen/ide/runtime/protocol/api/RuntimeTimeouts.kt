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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long the client waits for the runtime's answer, per [TimeoutClass]. The waits run as coroutine timeouts on the
 * client's dispatcher, so tests run them on virtual time.
 *
 * @property quick calls the runtime answers at once.
 * @property upload uploads, which the runtime parses before it answers.
 * @property stop stops, which join the engine for up to about 5 s.
 * @property snapshot snapshots, which wait up to 10 s for a tick boundary.
 * @throws IllegalArgumentException if a duration is not positive.
 */
public data class RuntimeTimeouts(
    public val quick: Duration = 10.seconds,
    public val upload: Duration = 30.seconds,
    public val stop: Duration = 30.seconds,
    public val snapshot: Duration = 30.seconds,
) {
    init {
        require(listOf(quick, upload, stop, snapshot).all { it.isPositive() }) { "every timeout must be positive: $this" }
    }

    /** The timeout of [timeoutClass]. */
    public fun of(timeoutClass: TimeoutClass): Duration =
        when (timeoutClass) {
            TimeoutClass.QUICK -> quick
            TimeoutClass.UPLOAD -> upload
            TimeoutClass.STOP -> stop
            TimeoutClass.SNAPSHOT -> snapshot
        }
}
