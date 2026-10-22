package io.karpfen.ide.core.text

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Covers the two-phase publication of [ChangePublisher]: queued changes become visible only with the flush, in version
 * order, also when an inline collector flushes reentrantly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangePublisherTest {

    private val lineage = TextLineage()
    private val initial = TextSnapshot("", 0, lineage)

    private fun changeAfter(before: TextSnapshot): TextChange {
        val after = TextSnapshot(before.text + "x", before.version + 1, lineage)
        return TextChange(before, after, listOf(TextEdit.insert(before.length, "x")), EditOrigin.Typing)
    }

    @Test
    fun `queued changes become visible with the flush, in order`() = runTest {
        val publisher = ChangePublisher(initial)
        val emitted = mutableListOf<TextChange>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { publisher.changes.toList(emitted) }
        val first = changeAfter(initial)
        val second = changeAfter(first.after)

        publisher.enqueue(first)
        publisher.enqueue(second)
        assertSame(initial, publisher.snapshot.value, "nothing is visible before the flush")
        assertEquals(emptyList(), emitted)

        publisher.flush()
        collector.cancel()

        assertSame(second.after, publisher.snapshot.value)
        assertEquals(listOf(first, second), emitted)
    }

    @Test
    fun `a flush from an inline collector leaves the publishing to the outer flush`() = runTest {
        val publisher = ChangePublisher(initial)
        val first = changeAfter(initial)
        val second = changeAfter(first.after)
        val emitted = mutableListOf<Long>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            publisher.changes.collect { change ->
                emitted += change.version
                if (change === first) {
                    publisher.enqueue(second)
                    publisher.flush()
                }
            }
        }

        publisher.enqueue(first)
        publisher.flush()
        collector.cancel()

        assertEquals(listOf(1L, 2L), emitted)
        assertSame(second.after, publisher.snapshot.value)
    }
}
