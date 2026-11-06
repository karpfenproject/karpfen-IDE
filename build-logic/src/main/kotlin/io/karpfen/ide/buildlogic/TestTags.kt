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
package io.karpfen.ide.buildlogic

/** JUnit tags with a meaning for the build. */
object TestTags {
    /**
     * Time-budget tests: tests that compare a wall-clock duration with a limit, such as the tokenize, parse, language
     * service, find, editor frame and project tree budgets of phase 1. They carry `@Tag("budget")` on the class or on
     * the test functions, and every test task excludes the tag, those of the modules through the `karpfen.kotlin-jvm`
     * convention and that of build-logic through its own build script, so no build, check or CI run executes them.
     * There is no opt-in switch.
     *
     * Wall-clock time depends on machine load (the build runs many test tasks in parallel), garbage collection, CPU
     * throttling and sleep/wake cycles, so such tests failed although the code had not changed. They stay in the code as
     * executable documentation of the performance targets; re-enabling or replacing them, for example by a dedicated,
     * serial benchmark task or by deterministic operation counts, is part of later performance work. Until then
     * performance regressions are not detected automatically.
     *
     * The architecture test `BudgetTestTaggingTest` is a tripwire for missing tags, in the tests of the modules and of
     * build-logic, and checks that every test task excludes the tag. Functional tests that merely wait for asynchronous
     * events with a generous timeout (file watcher, folder chooser, a search that gives up at its own time limit) are
     * not time-budget tests and keep running.
     */
    const val BUDGET: String = "budget"

    /**
     * Tests against a real karpfen runtime, reached at the URL that [LiveRuntimeTesting.URL_PROPERTY] names. They carry
     * the tag through the `@LiveRuntime` annotation of runtime-protocol's test fixtures, and every test task excludes
     * it except the `liveRuntimeTest` task of the runtime modules ([LiveRuntimeTesting.TASK_NAME]), which runs only
     * these tests and is never part of `check` or `build`.
     *
     * Unlike the budget tag, this tag has an opt-in task on purpose: a live test asserts behaviour, not time, and it
     * runs whenever someone hands the task a runtime, usually through `runtime/live-runtime.sh test`, which starts a
     * throwaway runtime in Docker. The architecture test `LiveRuntimeTaggingTest` checks the wiring.
     */
    const val LIVE_RUNTIME: String = "live-runtime"

    /**
     * Tests against the local Docker engine: those of runtime-host that build the IDE's runtime image, start its
     * container and remove it again. They carry `@Tag("live-docker")`, and every test task excludes the tag except the
     * `liveDockerTest` task of runtime-host ([LiveDockerTesting.TASK_NAME]), which runs only these tests and is never
     * part of `check` or `build`: the first image build takes minutes, and only Docker resources labelled for the IDE are
     * touched, so they run on purpose only.
     */
    const val LIVE_DOCKER: String = "live-docker"
}
