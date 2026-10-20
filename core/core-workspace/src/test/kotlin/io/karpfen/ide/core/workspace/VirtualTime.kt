package io.karpfen.ide.core.workspace

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs everything due within [time] of virtual time (more than every debounce of the workspace). Workspaces and
 * documents of the tests run in `backgroundScope`, whose work `advanceUntilIdle` does not wait for; `advanceTimeBy`
 * and `runCurrent` run it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.settle(time: Duration = 5.seconds) {
    advanceTimeBy(time)
    runCurrent()
}
