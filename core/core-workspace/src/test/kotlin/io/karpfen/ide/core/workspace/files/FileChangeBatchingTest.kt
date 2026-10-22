package io.karpfen.ide.core.workspace.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.locks.LockSupport
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/** Covers [batchedBy], the 200 ms watch debounce that collects every change of a burst, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileChangeBatchingTest {

    /** A change of [name] below `/ws`. */
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
    fun `a change that arrives exactly when the quiet time ends is not lost`() = runTest {
        val batches = ArrayList<List<FileChange>>()
        val upstream = flow {
            emit(change("a"))
            delay(200)
            emit(change("b"))
            delay(100_000)
        }
        backgroundScope.launch { upstream.batchedBy(200.milliseconds).collect { batches += it } }

        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(listOf(change("a"), change("b")), batches.flatten(), "batches: $batches")
    }

    @Test
    fun `no change is lost when changes and the end of the quiet time race on real threads`() {
        val sent = List(RACE_CHANGES) { change("f$it") }
        val spacing = Random(RACE_SEED)
        val upstream = flow {
            for (change in sent) {
                emit(change)
                LockSupport.parkNanos(spacing.nextLong(700_000, 1_300_000))
            }
        }.flowOn(Dispatchers.IO)
        val consumer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

        val received = consumer.use { runBlocking(it) { upstream.batchedBy(1.milliseconds).toList() } }.flatten()

        assertEquals(emptyList(), sent - received.toSet(), "lost changes")
        assertEquals(sent, received, "in arrival order")
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

    private companion object {
        const val RACE_CHANGES = 400
        const val RACE_SEED = 2026L
    }
}
