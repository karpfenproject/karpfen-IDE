package io.karpfen.ide.core.workspace.files

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/** [batchedBy]: the watch debounce (spec §4, 200 ms) collects every change of a burst, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileChangeBatchingTest {

    private fun change(name: String, kind: FileChangeKind = FileChangeKind.MODIFIED) =
        FileChange(Path.of("/ws", name).toAbsolutePath(), kind)

    @Test
    fun `a burst becomes one batch after the quiet time, in arrival order`() = runTest {
        val raw = Channel<FileChange>(Channel.UNLIMITED)
        val batches = ArrayList<List<FileChange>>()
        backgroundScope.launch { raw.consumeAsFlow().batchedBy(200.milliseconds).collect { batches += it } }

        raw.send(change("a"))
        advanceTimeBy(150)
        raw.send(change("b", FileChangeKind.CREATED))
        advanceTimeBy(150)
        raw.send(change("a", FileChangeKind.DELETED))
        advanceTimeBy(199)
        runCurrent()
        assertEquals(emptyList(), batches, "still within the quiet time of the last change")

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(listOf(change("a"), change("b", FileChangeKind.CREATED), change("a", FileChangeKind.DELETED))), batches)

        raw.send(change("c"))
        advanceTimeBy(201)
        runCurrent()
        assertEquals(listOf(change("c")), batches.last(), "a later change starts a new batch")
        assertEquals(2, batches.size)
    }

    @Test
    fun `a pending batch is emitted when the upstream completes`() = runTest {
        val batches = flowOf(change("a"), change("b")).batchedBy(200.milliseconds).toList()

        assertEquals(listOf(listOf(change("a"), change("b"))), batches)
    }

    @Test
    fun `no changes, no batches`() = runTest {
        assertEquals(emptyList(), flowOf<FileChange>().batchedBy(200.milliseconds).toList())
    }
}
