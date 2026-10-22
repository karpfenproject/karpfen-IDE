package io.karpfen.ide.buildlogic

/** JUnit tags with a meaning for the build. */
object TestTags {
    /**
     * Time-budget tests: tests that compare a wall-clock duration with a limit, such as the tokenize, parse, language
     * service, find, editor frame and project tree budgets of phase 1. They carry `@Tag("budget")` on the class or on
     * the test functions, and the `karpfen.kotlin-jvm` convention excludes the tag from every test task, so no build,
     * check or CI run executes them. There is no opt-in switch.
     *
     * Wall-clock time depends on machine load (the build runs many test tasks in parallel), garbage collection, CPU
     * throttling and sleep/wake cycles, so such tests failed although the code had not changed. They stay in the code as
     * executable documentation of the performance targets; re-enabling or replacing them, for example by a dedicated,
     * serial benchmark task or by deterministic operation counts, is part of later performance work. Until then
     * performance regressions are not detected automatically.
     *
     * The architecture test `BudgetTestTaggingTest` is a tripwire for missing tags. Functional tests that merely wait
     * for asynchronous events with a generous timeout (file watcher, folder chooser, a search that gives up at its own
     * time limit) are not time-budget tests and keep running.
     */
    const val BUDGET: String = "budget"
}
