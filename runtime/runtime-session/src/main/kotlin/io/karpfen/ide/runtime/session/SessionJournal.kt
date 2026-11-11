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

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The clock of one session. It measures the time since the session was launched, on the time source of the options.
 * In tests that time source runs on virtual time. Immutable and thread-safe.
 *
 * @param timeSource the clock.
 */
internal class SessionClock(timeSource: TimeSource) {
    /** The moment the session was launched. */
    private val launched = timeSource.markNow()

    /** The time since the session was launched. */
    fun elapsed(): Duration = launched.elapsedNow()
}

/**
 * The journal of one session. The latest [capacity] entries are replayed to every new collector, and older ones are
 * dropped. Recording never suspends. The session's router is the journal's only writer.
 *
 * @param capacity how many entries are kept for a late collector.
 */
internal class SessionJournal(capacity: Int) {
    /** The entries, replayed up to the capacity. */
    private val entries = MutableSharedFlow<SessionActivity>(replay = capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The kept entries, then every new one. */
    val activity: Flow<SessionActivity> = entries.asSharedFlow()

    /** Records [activity]. It never suspends. When the journal is full, the oldest kept entry gives way. */
    fun record(activity: SessionActivity) {
        entries.tryEmit(activity)
    }
}
