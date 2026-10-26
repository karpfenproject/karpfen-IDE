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
package io.karpfen.ide.core.workspace.analysis

import io.karpfen.ide.core.workspace.context.ContextState
import io.karpfen.ide.core.workspace.context.DescriptorSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.time.Duration

/**
 * An analysis together with the descriptors it was resolved from (taken from the same [ContextState] as its
 * resolution): the texts their project diagnostics refer to and the diagnostics of those texts themselves, as the
 * diagnostics publisher and the problems pipeline need them.
 */
internal data class PublishedAnalysis(val analysis: ProjectAnalysis, val descriptors: Map<Path, DescriptorSource>) {
    companion object {
        /** Before the first analysis. */
        val EMPTY: PublishedAnalysis = PublishedAnalysis(ProjectAnalysis.EMPTY, emptyMap())
    }
}

/**
 * Runs the project analysis of a workspace: debounced by [debounce] after the last [schedule] call,
 * cancellable (a newer request cancels a pending or running analysis, latest wins; a cancelled run publishes nothing),
 * immediate for [runNow]. Each run analyzes the [contexts] current at its start with the [analyzer] on [dispatcher],
 * reading texts through [sources], and publishes its result to [analysis] and [published] (in that order; consumers
 * such as the diagnostics publisher and the problems pipeline collect them). Generations increase with every started
 * run. An analyzer failure (the default one never throws) is logged, fails the waiting [runNow] calls and keeps the
 * scheduler serving.
 *
 * [isAnalyzing] is true from the first request until a run completes without a newer request pending.
 */
internal class AnalysisScheduler(
    private val analyzer: ProjectAnalyzer,
    private val sources: AnalysisSources,
    private val contexts: StateFlow<ContextState>,
    private val dispatcher: CoroutineDispatcher,
    private val debounce: Duration,
) {

    /** Counts the requests; every new count restarts the serving loop, which cancels a pending or running run. */
    private val requests = MutableStateFlow(0L)
    private val analyzing = MutableStateFlow(false)
    private val analysisState = MutableStateFlow(ProjectAnalysis.EMPTY)
    private val publishedState = MutableStateFlow(PublishedAnalysis.EMPTY)
    /** The waiting [runNow] calls; while there are any, a run starts without the debounce. Guarded by itself. */
    private val waiters = ArrayList<CompletableDeferred<ProjectAnalysis>>()

    /** The generation of the latest started run. */
    private var generation = 0L

    /** True while an analysis is scheduled or running. */
    val isAnalyzing: StateFlow<Boolean> = analyzing.asStateFlow()

    /** The latest completed analysis (`Workspace.analysis`). */
    val analysis: StateFlow<ProjectAnalysis> = analysisState.asStateFlow()

    /** The latest completed analysis with its descriptors. */
    val published: StateFlow<PublishedAnalysis> = publishedState.asStateFlow()

    /** Requests an analysis after the debounce; thread-safe. */
    fun schedule() {
        analyzing.value = true
        requests.update { it + 1 }
    }

    /**
     * Requests an analysis without debounce (superseding a scheduled one) and returns the first analysis that started
     * after this call once it is published. Fails with [CancellationException] if the scheduler is closed first.
     */
    suspend fun runNow(): ProjectAnalysis {
        val waiter = CompletableDeferred<ProjectAnalysis>()
        synchronized(waiters) { waiters += waiter }
        schedule()
        return waiter.await()
    }

    /** Starts serving requests in [scope]; the returned job ends with the scope. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        requests.collectLatest {
            analyzing.value = true
            if (!hasWaiters()) delay(debounce)
            val served = synchronized(waiters) { waiters.toList() }
            val outcome = runCatchingFailures { analyze() }
            analyzing.value = false
            synchronized(waiters) { waiters.removeAll(served.toSet()) }
            served.forEach { waiter -> outcome.fold(waiter::complete, waiter::completeExceptionally) }
        }
    }

    /** Analyzes the contexts current now as the next generation and publishes the result. */
    private suspend fun analyze(): ProjectAnalysis {
        val state = contexts.value
        val number = ++generation
        val result = withContext(dispatcher) { analyzer.analyze(state.resolution, sources, number) }
        analysisState.value = result
        publishedState.value = PublishedAnalysis(result, state.descriptors)
        return result
    }

    /** The result of [block]; a failure other than a cancellation is logged and returned as a failed result. */
    private inline fun runCatchingFailures(block: () -> ProjectAnalysis): Result<ProjectAnalysis> =
        try {
            Result.success(block())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            logger.error("The project analysis failed", e)
            Result.failure(e)
        }

    /** Fails every waiting [runNow] (the workspace is closing). */
    fun close() {
        val pending = synchronized(waiters) { waiters.toList().also { waiters.clear() } }
        pending.forEach { it.cancel(CancellationException("the workspace was closed")) }
        analyzing.value = false
    }

    private fun hasWaiters(): Boolean = synchronized(waiters) { waiters.isNotEmpty() }

    private companion object {
        private val logger = LoggerFactory.getLogger(AnalysisScheduler::class.java)
    }
}
