package io.karpfen.ide.core.workspace.files

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Debounces raw file changes into batches (spec §4, watch debounce): a batch starts with the first change after a
 * quiet period and collects every further change until no change arrived for [quiet]; it is then emitted with all of
 * its changes in arrival order (unlike `debounce`, which would keep only the last one). When the upstream completes,
 * a pending batch is emitted at once. Works on virtual time in tests.
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
                val next = withTimeoutOrNull(quiet) { changes.receiveCatching() } ?: break
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
