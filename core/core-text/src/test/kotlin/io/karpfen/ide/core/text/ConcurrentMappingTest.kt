package io.karpfen.ide.core.text

import org.junit.jupiter.api.TestReporter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `mapOffset` and `mapRange` called from other threads while the single writer applies edits (including undo and
 * redo) never throw, and every answer is the one some published version gives: re-computed on the writer's thread
 * afterwards, it is the same.
 */
class ConcurrentMappingTest {

    /** One lookup made by a reader: the arguments and the answer it got. */
    private sealed interface Lookup {
        data class Point(val offset: Int, val from: Long, val to: Long, val bias: Bias, val answer: Int?) : Lookup

        /** A range mapped to "the current version"; [lowest]..[highest] bound the versions current during the call. */
        data class Range(val range: TextRange, val from: Long, val lowest: Long, val highest: Long, val answer: TextRange?) : Lookup
    }

    @Test
    fun `readers map consistently with some published version while the writer edits`(reporter: TestReporter) {
        val text = EditableText("fn main() {\n}\n", historyLimit = 100_000)
        val lookups = ConcurrentLinkedQueue<Lookup>()
        val failure = AtomicReference<Throwable>()
        val writing = AtomicBoolean(true)
        val started = CountDownLatch(READERS + 1)

        val readers = List(READERS) { index ->
            thread(name = "mapping-reader-$index") {
                val random = Random(100 + index)
                started.countDown()
                started.await()
                try {
                    var from = text.snapshot.value
                    var count = 0
                    while (writing.get()) {
                        // Keep an older snapshot for a while, so lookups cross versions the writer publishes meanwhile.
                        if (++count % 100 == 0) from = text.snapshot.value
                        lookups += randomLookup(text, from, random)
                    }
                } catch (throwable: Throwable) {
                    failure.compareAndSet(null, throwable)
                }
            }
        }
        val writer = thread(name = "writer") {
            val random = Random(7)
            val edits = RandomEdits(random)
            started.countDown()
            started.await()
            try {
                repeat(WRITES) {
                    when (random.nextInt(10)) {
                        0 -> text.undo.undo()
                        1 -> text.undo.redo()
                        else -> text.apply(edits.edits(text.snapshot.value.length, softLimit = 300), EditOrigin.Paste)
                    }
                }
            } catch (throwable: Throwable) {
                failure.compareAndSet(null, throwable)
            } finally {
                writing.set(false)
            }
        }
        writer.join()
        readers.forEach { it.join() }
        failure.get()?.let { throw AssertionError("a thread failed", it) }

        // Single-threaded now: every answer must equal the mapping of the version the reader asked for.
        var points = 0
        var ranges = 0
        var racing = 0
        for (lookup in lookups) when (lookup) {
            is Lookup.Point -> {
                assertEquals(text.mapOffset(lookup.offset, lookup.from, lookup.to, lookup.bias), lookup.answer, "$lookup")
                points++
                if (lookup.to > lookup.from) racing++
            }
            is Lookup.Range -> {
                val candidates = (lookup.lowest..lookup.highest).map { text.mapRange(lookup.range, lookup.from, text.lineage, it) }
                assertTrue(lookup.answer in candidates, "$lookup not in $candidates")
                ranges++
                if (lookup.highest > lookup.from) racing++
            }
        }
        assertTrue(points > 0 && ranges > 0 && racing > 0, "the readers mapped across versions while the writer edited")
        reporter.publishEntry(
            "concurrent lookups",
            "$WRITES writes, $points point and $ranges range lookups verified, $racing of them across versions written meanwhile",
        )
    }

    @Test
    fun `readers never fail while the writer evicts the history they map from`() {
        val text = EditableText("", historyLimit = 4)
        val failure = AtomicReference<Throwable>()
        val writing = AtomicBoolean(true)
        val reader = thread(name = "evicting-reader") {
            val random = Random(3)
            try {
                while (writing.get()) {
                    val old = text.snapshot.value
                    val answer = text.mapRange(TextRange(0, old.length), old)
                    // Every write inserts at 0, outside the range: a mapped range keeps its length inside the text.
                    if (answer != null) {
                        assertEquals(old.length, answer.length)
                        assertTrue(answer.end <= text.snapshot.value.length)
                    }
                    text.mapOffset(random.nextInt(old.length + 1), old.version, bias = Bias.LEFT)
                }
            } catch (throwable: Throwable) {
                failure.compareAndSet(null, throwable)
            }
        }
        repeat(WRITES) { text.apply(listOf(TextEdit.insert(0, "x")), EditOrigin.Programmatic("fill")) }
        writing.set(false)
        reader.join()
        failure.get()?.let { throw AssertionError("the reader failed", it) }

        assertNull(text.mapOffset(0, 0))
        assertNotNull(text.mapOffset(0, WRITES - 4L))
    }

    private fun randomLookup(text: EditableText, from: TextSnapshot, random: Random): Lookup {
        val to = text.snapshot.value
        val toVersion = random.nextLong(from.version, to.version + 1)
        return if (random.nextBoolean()) {
            val offset = random.nextInt(from.length + 1)
            val bias = if (random.nextBoolean()) Bias.LEFT else Bias.RIGHT
            val answer = text.mapOffset(offset, from.version, toVersion, bias)
            assertNotNull(answer, "nothing is evicted in this test")
            Lookup.Point(offset, from.version, toVersion, bias, answer)
        } else {
            val a = random.nextInt(from.length + 1)
            val b = random.nextInt(from.length + 1)
            val range = TextRange(minOf(a, b), maxOf(a, b))
            val lowest = to.version
            val answer = text.mapRange(range, from)
            val highest = text.snapshot.value.version
            assertNotNull(answer, "nothing is evicted in this test")
            Lookup.Range(range, from.version, lowest, highest, answer)
        }
    }

    private companion object {
        const val READERS = 2
        const val WRITES = 20_000
    }
}
