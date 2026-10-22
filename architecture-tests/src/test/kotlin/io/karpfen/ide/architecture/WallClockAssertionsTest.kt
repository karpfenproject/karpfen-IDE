package io.karpfen.ide.architecture

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [WallClockAssertions], the judgement behind the budget tripwire: the shapes of timing assertions it must
 * recognize, whatever the limit is named, and the functional tests that read the wall clock without asserting on it,
 * which it must leave alone.
 *
 * Each input is a function declaration as written in a test source file; helpers of the same file are passed along as
 * further declarations.
 */
class WallClockAssertionsTest {

    /** True if the first of [declarations] asserts on a wall-clock measurement, the others being its helpers. */
    private fun assertsOnWallClock(vararg declarations: String): Boolean {
        val functions = declarations.map { FunctionSource(NAME.find(it)?.groupValues?.get(1).orEmpty(), it) }
        return WallClockAssertions(functions).assertsOnWallClock(functions.first())
    }

    @Test
    fun `an assertion against a literal limit is a timing assertion`() {
        assertTrue(assertsOnWallClock("fun t() { val elapsed = measureTime { work() }; assertTrue(elapsed < 200.milliseconds) }"))
        assertTrue(assertsOnWallClock("fun t() {\n    assertTrue(measureTime { work() } < 200.milliseconds)\n}"))
    }

    @Test
    fun `a measurement is followed through declarations, derived values and multi-line assertions`() {
        val test = """
            fun t() {
                val mark = TimeSource.Monotonic.markNow()
                repeat(RUNS) { work() }
                val average: Duration =
                    mark.elapsedNow() / RUNS
                assertTrue(
                    average < LIMIT * 5,
                    "too slow",
                )
            }
        """
        assertTrue(assertsOnWallClock(test))
        assertTrue(assertsOnWallClock("fun t() {\n    val start = System.nanoTime()\n    work()\n    val millis = (System.nanoTime() - start) / 1e6\n    assertTrue(millis < 50.0)\n}"))
    }

    @Test
    fun `a measurement is followed through lambdas, collections and loops`() {
        val runs = "fun t() {\n    val runs = List(5) {\n        val started = TimeSource.Monotonic.markNow()\n        work()\n        started.elapsedNow()\n    }\n" +
            "    val median = runs.sorted()[2]\n    assertWithinLimit(median)\n}"
        val pairs = "fun t() {\n    val durations = mapOf(\"a\" to measureTime { work() })\n" +
            "    durations.forEach { (name, duration) -> assertTrue(duration < LIMIT, name) }\n}"
        val loop = "fun t() {\n    val durations = listOf(measureTime { work() })\n    for (duration in durations) check(duration < LIMIT)\n}"
        assertTrue(assertsOnWallClock(runs))
        assertTrue(assertsOnWallClock(pairs))
        assertTrue(assertsOnWallClock(loop))
    }

    @Test
    fun `measuring and asserting helpers of the same file count at their calls`() {
        val measure = "private fun measure(runs: Int, block: () -> Unit): Duration {\n    val mark = TimeSource.Monotonic.markNow()\n" +
            "    repeat(runs) { block() }\n    return mark.elapsedNow() / runs\n}"
        val average = "private fun average(block: () -> Unit) = measureTime { block() } / 3"
        val check = "private fun check(name: String, measured: Duration, budget: Duration) {\n    assertTrue(measured < budget * 5, name)\n}"
        val assertFast = "private fun assertFast(block: () -> Unit) {\n    assertTrue(measureTime(block) < 1.seconds)\n}"

        assertTrue(assertsOnWallClock("fun t() {\n    check(\"x\", measure(10) { work() }, 1.milliseconds)\n}", measure, check))
        assertTrue(assertsOnWallClock("fun t() {\n    val took = average { work() }\n    assertTrue(took < 1.seconds)\n}", average))
        assertTrue(assertsOnWallClock("fun t() {\n    assertFast { work() }\n}", assertFast))
        assertFalse(assertsOnWallClock("fun t() {\n    check(\"x\", 3.milliseconds, 1.milliseconds)\n}", measure, check))
    }

    @Test
    fun `JUnit's timeout assertions measure the wall clock themselves`() {
        assertTrue(assertsOnWallClock("fun t() {\n    assertTimeout(Duration.ofMillis(100)) { work() }\n}"))
    }

    @Test
    fun `reading the wall clock without asserting on it is no timing assertion`() {
        val reportOnly = "fun t(reporter: TestReporter) {\n    val mark = TimeSource.Monotonic.markNow()\n    val result = work()\n" +
            "    reporter.publishEntry(\"took\", \"\${mark.elapsedNow()}\")\n    assertEquals(1, result)\n}"
        val deadline = "fun t() {\n    val deadline = System.nanoTime() + 10_000\n    while (System.nanoTime() < deadline && !done()) Thread.sleep(20)\n" +
            "    assertTrue(done())\n}"
        val uniqueText = "fun t() {\n    val text = \"content \${System.nanoTime()}\"\n    write(text)\n    assertTrue(events.isNotEmpty())\n}"
        assertFalse(assertsOnWallClock(reportOnly))
        assertFalse(assertsOnWallClock(deadline))
        assertFalse(assertsOnWallClock(uniqueText))
    }

    @Test
    fun `virtual time, comments and strings are no wall-clock reads`() {
        val virtual = "fun t() {\n    val time = TestTimeSource()\n    val mark = time.markNow()\n    time += 1.seconds\n" +
            "    assertEquals(1.seconds, mark.elapsedNow())\n    assertTrue(time.measureTime { work() } < 1.seconds)\n}"
        val mentioned = "fun t() {\n    /** measureTime */\n    assertTrue(work() < 5, \"measureTime { } took System.nanoTime()\")\n}"
        assertFalse(assertsOnWallClock(virtual))
        assertFalse(assertsOnWallClock(mentioned))
    }

    private companion object {
        /** The name of a declared function, after the keyword fun and an optional receiver type. */
        val NAME = Regex("""\bfun\s+(?:\w+\.)?(\w+)""")
    }
}
