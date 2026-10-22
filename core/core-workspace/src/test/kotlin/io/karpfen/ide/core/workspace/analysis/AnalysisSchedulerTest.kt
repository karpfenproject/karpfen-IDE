package io.karpfen.ide.core.workspace.analysis

import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.context.ContextState
import io.karpfen.ide.core.workspace.context.ModelingContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Covers the scheduling of the project analysis: the 300 ms debounce, cancellation of a running analysis by a newer
 * request, immediate runs, the contexts and descriptor texts each run uses, failing analyzers and closing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisSchedulerTest {

    /** Records every run; runs may be held at a gate to test cancellation. */
    private class FakeAnalyzer : ProjectAnalyzer {
        val started = ArrayList<Long>()
        val resolutions = ArrayList<ContextResolution>()
        var gate: CompletableDeferred<Unit>? = null
        var failure: Exception? = null

        override suspend fun analyze(resolution: ContextResolution, sources: AnalysisSources, generation: Long): ProjectAnalysis {
            started += generation
            resolutions += resolution
            gate?.await()
            failure?.let { throw it }
            return ProjectAnalysis(generation, emptyList(), emptyMap())
        }
    }

    private val noSources = AnalysisSources { null }
    private val contexts = MutableStateFlow(ContextState.EMPTY)

    /** A started scheduler over [analyzer] with a 300 ms debounce on the test's virtual time. */
    private fun TestScope.scheduler(analyzer: ProjectAnalyzer): AnalysisScheduler =
        AnalysisScheduler(analyzer, noSources, contexts, StandardTestDispatcher(testScheduler), 300.milliseconds)
            .also { it.start(backgroundScope) }

    @Test
    fun `an analysis runs 300 ms after the last request and publishes its result`() = runTest {
        val analyzer = FakeAnalyzer()
        val scheduler = scheduler(analyzer)

        scheduler.schedule()
        assertTrue(scheduler.isAnalyzing.value, "scheduled counts as analyzing")
        advanceTimeBy(200.milliseconds)
        scheduler.schedule()
        advanceTimeBy(299.milliseconds)
        runCurrent()
        assertEquals(emptyList(), analyzer.started)

        advanceTimeBy(2.milliseconds)
        runCurrent()
        assertEquals(listOf(1L), analyzer.started)
        assertEquals(1, scheduler.analysis.value.generation)
        assertSame(scheduler.analysis.value, scheduler.published.value.analysis)
        assertFalse(scheduler.isAnalyzing.value)
    }

    @Test
    fun `a newer request cancels a running analysis, which publishes nothing`() = runTest {
        val analyzer = FakeAnalyzer().apply { gate = CompletableDeferred() }
        val scheduler = scheduler(analyzer)
        scheduler.schedule()
        advanceTimeBy(301.milliseconds)
        runCurrent()
        assertEquals(listOf(1L), analyzer.started)

        scheduler.schedule()
        runCurrent()
        analyzer.gate = null
        advanceTimeBy(301.milliseconds)
        runCurrent()

        assertEquals(listOf(1L, 2L), analyzer.started)
        assertEquals(2, scheduler.analysis.value.generation, "the cancelled first run never published")
        assertFalse(scheduler.isAnalyzing.value)
    }

    @Test
    fun `runNow bypasses the debounce and supersedes a scheduled analysis`() = runTest {
        val analyzer = FakeAnalyzer()
        val scheduler = scheduler(analyzer)
        scheduler.schedule()
        advanceTimeBy(100.milliseconds)

        val result = async { scheduler.runNow() }
        runCurrent()

        assertTrue(result.isCompleted, "no debounce for runNow")
        assertEquals(listOf(1L), analyzer.started, "the scheduled analysis was superseded")
        assertSame(scheduler.analysis.value, result.await())
        advanceTimeBy(1000.milliseconds)
        runCurrent()
        assertEquals(listOf(1L), analyzer.started)
    }

    @Test
    fun `each run analyzes the contexts current at its start and publishes their descriptor texts`() = runTest {
        val analyzer = FakeAnalyzer()
        val scheduler = scheduler(analyzer)
        val context = ModelingContext(Path.of("/ws").toAbsolutePath(), null, null, null, emptyList(), emptyList())
        val texts = mapOf(Path.of("/ws/a.kenv.json").toAbsolutePath() to TextSnapshot("{}", 0))
        contexts.value = ContextState(ContextResolution(listOf(context), emptyMap()), texts)

        scheduler.runNow()

        assertEquals(listOf(context), analyzer.resolutions.single().contexts)
        assertSame(texts, scheduler.published.value.descriptorTexts)
    }

    @Test
    fun `a failing analyzer fails the waiting runNow and the scheduler keeps serving`() = runTest {
        val analyzer = FakeAnalyzer().apply { failure = IllegalStateException("broken analyzer") }
        val scheduler = scheduler(analyzer)

        val failed = async { runCatching { scheduler.runNow() } }
        runCurrent()
        assertIs<IllegalStateException>(failed.await().exceptionOrNull())
        assertFalse(scheduler.isAnalyzing.value)

        analyzer.failure = null
        assertEquals(2, scheduler.runNow().generation)
    }

    @Test
    fun `closing fails pending runNow calls`() = runTest {
        val analyzer = FakeAnalyzer().apply { gate = CompletableDeferred() }
        val scheduler = scheduler(analyzer)
        val waiting = async { runCatching { scheduler.runNow() } }
        runCurrent()

        scheduler.close()
        runCurrent()

        assertIs<CancellationException>(waiting.await().exceptionOrNull())
        assertFalse(scheduler.isAnalyzing.value)
    }
}
