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
package io.karpfen.ide.core.workspace.fixtures

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs everything that is due within [time] of virtual time. The default is longer than every debounce of the
 * workspace. Workspaces and documents of the tests run in `backgroundScope`, and `advanceUntilIdle` does not wait for
 * that work. `advanceTimeBy` and `runCurrent` run it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public fun TestScope.settle(time: Duration = 5.seconds) {
    advanceTimeBy(time)
    runCurrent()
}
