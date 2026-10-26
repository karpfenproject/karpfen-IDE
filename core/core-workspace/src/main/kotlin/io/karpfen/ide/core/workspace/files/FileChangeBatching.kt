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
package io.karpfen.ide.core.workspace.files

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.time.Duration

/**
 * Debounces raw file changes into batches, the workspace's watch debounce: a batch starts with the first change after a
 * quiet period and collects every further change until no change arrived for [quiet]; it is then emitted with all of
 * its changes in arrival order (unlike `debounce`, which would keep only the last one). When the upstream completes,
 * a pending batch is emitted at once. Works on virtual time in tests.
 *
 * No change is ever lost: the next change and the end of the quiet period are awaited in one `select`, which takes
 * an element only in the branch that wins (a receive cancelled by a timeout could drop the element just handed to it).
 */
internal fun Flow<FileChange>.batchedBy(quiet: Duration): Flow<List<FileChange>> {
    val upstream = this
    return channelFlow {
        val changes = upstream.buffer(Channel.UNLIMITED).produceIn(this)
        var open = true
        while (open) {
            val first = changes.receiveCatching().getOrNull() ?: break
            val batch = arrayListOf(first)
            while (true) {
                val next = changes.receiveWithin(quiet) ?: break
                val change = next.getOrNull()
                if (change == null) {
                    open = false
                    break
                }
                batch += change
            }
            send(batch.toList())
        }
    }
}

/** The next element (or the channel's close) if one arrives within [quiet], else null; atomic with respect to the element. */
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun <E> ReceiveChannel<E>.receiveWithin(quiet: Duration): ChannelResult<E>? = select {
    onReceiveCatching { it }
    onTimeout(quiet) { null }
}
