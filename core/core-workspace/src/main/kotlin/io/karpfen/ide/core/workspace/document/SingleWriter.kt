package io.karpfen.ide.core.workspace.document

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The single writer of the documents' texts (spec §1, decision 21): runs every mutation of an open document — reload,
 * save point, conflict state — on the dispatcher of the workspace's [scope]. Callers already on that dispatcher (the
 * UI thread in the app, the test thread in tests) run in place without a dispatch; I/O always happens outside.
 */
internal class SingleWriter(scope: CoroutineScope) {

    private val context = scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext

    /** Runs [mutation] on the writer's dispatcher and returns its result. */
    suspend fun <T> run(mutation: () -> T): T = withContext(context) { mutation() }
}
