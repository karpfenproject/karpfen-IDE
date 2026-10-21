package io.karpfen.ide.buildlogic

/** JUnit tags with a meaning for the build (docs/adr/0011-time-budget-tests-are-not-run.md). */
object TestTags {
    /**
     * Time-budget tests: they assert wall-clock durations, which machine load and sleep/wake cycles make unreliable.
     * They stay in the code for later performance work and are excluded from every test task.
     */
    const val BUDGET: String = "budget"
}
