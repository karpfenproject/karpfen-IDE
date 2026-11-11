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

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The log of a host. It carries the host's own lines about its work and the lines of its runtime in one hot stream. A
 * new collector first receives the last [capacity] lines and then follows the new ones. Writing is safe from any thread
 * and never blocks. Once the log holds [capacity] lines, each new line drops the oldest one.
 *
 * @param capacity how many lines are replayed.
 */
internal class HostLog(capacity: Int = CAPACITY) {
    /** The lines, the recent ones replayed. */
    private val flow = MutableSharedFlow<HostLogLine>(replay = capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The lines as [RuntimeHost.logs] offers them. */
    val lines: SharedFlow<HostLogLine> = flow.asSharedFlow()

    /** Adds a line the host writes about its own work. */
    fun host(text: String) {
        add(HostLogLine(text, LogStream.STDOUT))
    }

    /** Adds [line]. */
    fun add(line: HostLogLine) {
        flow.tryEmit(line)
    }

    /** The texts of the last [count] lines. */
    fun last(count: Int): List<String> = flow.replayCache.takeLast(count).map { it.text }

    private companion object {
        /** The number of lines a new collector receives first. */
        const val CAPACITY = 2_000
    }
}
