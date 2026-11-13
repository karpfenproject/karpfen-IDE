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
package io.karpfen.ide.app.headless

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * The stop of a headless run, between the thread that asks for it and the thread that drives the run.
 *
 * Ctrl+C and a terminate signal end the process. The virtual machine then runs its shutdown hook, whose step for the
 * runs calls [requestAndAwait]. That asks the run to stop and waits until it reports through [finished] that it shut
 * down its bridges, its environment and the runtime, or until the bound passed. The run waits for [requested] next to
 * its own end, and whichever comes first ends it. A run that ends on its own reports [finished] as well, so a later
 * request returns at once.
 *
 * Every function may be called from any thread.
 */
internal class HeadlessStop {

    private val request = CompletableDeferred<Unit>()
    private val done = CountDownLatch(1)

    /** Completes once a stop was asked for. */
    val requested: Deferred<Unit> get() = request

    /** Asks the run to stop. Asking again changes nothing. */
    fun request() {
        request.complete(Unit)
    }

    /** Reports that the run is over and everything it started is shut down. */
    fun finished() {
        done.countDown()
    }

    /**
     * Asks the run to stop and waits until it [finished], for at most [bound]. Returns true if it finished in time.
     * The waiting thread blocks.
     */
    fun requestAndAwait(bound: Duration): Boolean {
        request()
        return done.await(bound.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }
}
