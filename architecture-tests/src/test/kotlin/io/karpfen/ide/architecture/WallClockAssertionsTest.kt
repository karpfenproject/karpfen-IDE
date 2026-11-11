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
package io.karpfen.ide.architecture

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [WallClockAssertions], the judgement behind the budget tripwire. It must recognize the shapes of timing
 * assertions, whatever the limit is named. It must leave alone the functional tests that read the wall clock without
 * asserting on a measured duration. Those hold a duration in a message or a report, a timestamp, a deadline or a
 * generous timeout.
 *
 * Each input is a function declaration as written in a test source file, and most of them are built by [test]. Helpers
 * of the same file are passed along as further declarations.
 */
class WallClockAssertionsTest {

    /** True if the first of [declarations] asserts on a wall-clock measurement, the others being its helpers. */
    private fun assertsOnWallClock(vararg declarations: String): Boolean {
        val functions = declarations.map { FunctionSource(NAME.find(it)?.groupValues?.get(1).orEmpty(), it) }
        return WallClockAssertions(functions).assertsOnWallClock(functions.first())
    }

    /** A test function named `t` whose body holds [lines], one per line. */
    private fun test(vararg lines: String): String = lines.joinToString("\n", prefix = "fun t() {\n", postfix = "\n}") { "    $it" }

    @Test
    fun `an assertion against a literal limit is a timing assertion`() {
        assertTrue(assertsOnWallClock("fun t() { val elapsed = measureTime { work() }; assertTrue(elapsed < 200.milliseconds) }"))
        assertTrue(assertsOnWallClock(test("assertTrue(measureTime { work() } < 200.milliseconds)")))
    }

    @Test
    fun `a measurement is followed through declarations, derived values and multi-line assertions`() {
        val average = """
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
        val millis = test(
            "val start = System.nanoTime()",
            "work()",
            "val millis = (System.nanoTime() - start) / 1e6",
            "assertTrue(millis < 50.0)",
        )
        assertTrue(assertsOnWallClock(average))
        assertTrue(assertsOnWallClock(millis))
    }

    @Test
    fun `a measurement is followed through lambdas, collections and loops`() {
        val runs = test(
            "val runs = List(5) {",
            "    val started = TimeSource.Monotonic.markNow()",
            "    work()",
            "    started.elapsedNow()",
            "}",
            "val median = runs.sorted()[2]",
            "assertWithinLimit(median)",
        )
        val pairs = test(
            "val durations = mapOf(\"a\" to measureTime { work() })",
            "durations.forEach { (name, duration) -> assertTrue(duration < LIMIT, name) }",
        )
        val loop = test("val durations = listOf(measureTime { work() })", "for (duration in durations) check(duration < LIMIT)")
        assertTrue(assertsOnWallClock(runs))
        assertTrue(assertsOnWallClock(pairs))
        assertTrue(assertsOnWallClock(loop))
    }

    @Test
    fun `measuring and asserting helpers of the same file count at their calls`() {
        val measure = "private fun measure(runs: Int, block: () -> Unit): Duration {\n    val mark = TimeSource.Monotonic.markNow()\n" +
            "    repeat(runs) { block() }\n    return mark.elapsedNow() / runs\n}"
        val average = "private fun average(block: () -> Unit) = measureTime { block() } / 3"
        val check = "private fun check(name: String, measured: Duration, budget: Duration) {\n" +
            "    assertTrue(measured < budget * 5, name)\n}"
        val assertFast = "private fun assertFast(block: () -> Unit) {\n    assertTrue(measureTime(block) < 1.seconds)\n}"

        assertTrue(assertsOnWallClock(test("check(\"x\", measure(10) { work() }, 1.milliseconds)"), measure, check))
        assertTrue(assertsOnWallClock(test("val took = average { work() }", "assertTrue(took < 1.seconds)"), average))
        assertTrue(assertsOnWallClock(test("assertFast { work() }"), assertFast))
        assertFalse(assertsOnWallClock(test("check(\"x\", 3.milliseconds, 1.milliseconds)"), measure, check))
    }

    @Test
    fun `a measurement is followed through scope functions, assignments, imports and qualified names`() {
        listOf(
            test("measureTime { work() }.let { assertTrue(it < 200.milliseconds) }"),
            test("measureTimedValue { work() }", "    .also { assertTrue(it.duration < 200.milliseconds) }"),
            test("var elapsed = Duration.ZERO", "elapsed = measureTime { work() }", "assertTrue(elapsed < 1.seconds)"),
            test("var total = Duration.ZERO", "repeat(3) { total += measureTime { work() } }", "assertTrue(total < 1.seconds)"),
            test("val mark = Monotonic.markNow()", "work()", "assertTrue(mark.elapsedNow() < 1.seconds)"),
            test("val ms = kotlin.system.measureTimeMillis { work() }", "assertTrue(ms < 200)"),
            test("val start = System.currentTimeMillis()", "work()", "assertTrue(System.currentTimeMillis() - start < 200)"),
            test("val start = Instant.now()", "work()", "assertTrue(Duration.between(start, Instant.now()).toMillis() < 200)"),
        ).forEach { test -> assertTrue(assertsOnWallClock(test), test) }
    }

    @Test
    fun `failing in a branch whose condition holds a measurement is a timing assertion`() {
        val failing = test("val elapsed = measureTime { work() }", "if (elapsed > 200.milliseconds) fail(\"too slow\")")
        val throwing = test("val elapsed = measureTime { work() }", "if (elapsed > LIMIT) {", "    throw AssertionError()", "}")
        val reporting = test("val elapsed = measureTime { work() }", "if (elapsed > LIMIT) println(elapsed)")
        assertTrue(assertsOnWallClock(failing))
        assertTrue(assertsOnWallClock(throwing))
        assertFalse(assertsOnWallClock(reporting))
    }

    @Test
    fun `timeouts are timing assertions when their literal limit is tight`() {
        assertTrue(assertsOnWallClock(test("assertTimeout(Duration.ofMillis(100)) { work() }")))
        assertTrue(assertsOnWallClock("fun t() = runTest(timeout = 1.milliseconds) {\n    work()\n}"))
        assertFalse(assertsOnWallClock(test("assertTimeout(Duration.ofSeconds(30)) { work() }")))
        assertFalse(assertsOnWallClock("fun t() = runTest(timeout = 60.seconds) {\n    work()\n}"))
        assertFalse(assertsOnWallClock(test("assertTimeoutPreemptively(LIMIT) { work() }")))
    }

    @Test
    fun `a duration in a message, a timestamp and a deadline are no timing assertions`() {
        listOf(
            test(
                "val mark = TimeSource.Monotonic.markNow()",
                "val result = work()",
                "assertEquals(1, result, \"after \${mark.elapsedNow()}\")",
                "check(result == 1) { \"took \${mark.elapsedNow()}\" }",
                "require(result == 1) { \"took \" + mark.elapsedNow() }",
            ),
            test("val stamp = Instant.now()", "store.save(stamp)", "assertEquals(stamp, store.load())"),
            test("val stamp = System.currentTimeMillis()", "store.save(stamp)", "assertEquals(stamp, store.load())"),
            test(
                "val deadline = TimeSource.Monotonic.markNow() + 10.seconds",
                "while (!done()) {",
                "    check(deadline.hasNotPassedNow()) { \"timed out\" }",
                "    Thread.sleep(10)",
                "}",
            ),
            test(
                "val deadline = System.nanoTime() + 10_000_000_000",
                "while (!done()) {",
                "    if (System.nanoTime() > deadline) fail(\"timed out\")",
                "    Thread.sleep(10)",
                "}",
            ),
        ).forEach { test -> assertFalse(assertsOnWallClock(test), test) }
    }

    @Test
    fun `the parameters of a lambda take their values from the receiver of its call, inside the lambda only`() {
        val sameLine = test("val elapsed = measureTime { items.forEach { item -> assertTrue(item.isValid) } }", "report(elapsed)")
        val nested = test("measureTime { work() }.let { report(it); items.forEach { assertTrue(it.isValid) } }")
        val outside = test("measureTime { work() }.let { report(it) }", "items.forEach { assertTrue(it.isValid) }")
        assertFalse(assertsOnWallClock(sameLine))
        assertFalse(assertsOnWallClock(nested))
        assertFalse(assertsOnWallClock(outside))
    }

    @Test
    fun `reading the wall clock without asserting on it is no timing assertion`() {
        val reportOnly = "fun t(reporter: TestReporter) {\n    val mark = TimeSource.Monotonic.markNow()\n    val result = work()\n" +
            "    reporter.publishEntry(\"took\", \"\${mark.elapsedNow()}\")\n    assertEquals(1, result)\n}"
        val deadline = test(
            "val deadline = System.nanoTime() + 10_000",
            "while (System.nanoTime() < deadline && !done()) Thread.sleep(20)",
            "assertTrue(done())",
        )
        val uniqueText = test("val text = \"content \${System.nanoTime()}\"", "write(text)", "assertTrue(events.isNotEmpty())")
        assertFalse(assertsOnWallClock(reportOnly))
        assertFalse(assertsOnWallClock(deadline))
        assertFalse(assertsOnWallClock(uniqueText))
    }

    @Test
    fun `virtual time, comments and strings are no wall-clock reads`() {
        val virtual = test(
            "val time = TestTimeSource()",
            "val mark = time.markNow()",
            "time += 1.seconds",
            "assertEquals(1.seconds, mark.elapsedNow())",
            "assertTrue(time.measureTime { work() } < 1.seconds)",
        )
        val mentioned = test("/** measureTime */", "assertTrue(work() < 5, \"measureTime { } took System.nanoTime()\")")
        assertFalse(assertsOnWallClock(virtual))
        assertFalse(assertsOnWallClock(mentioned))
    }

    private companion object {
        /** The name of a declared function, after the keyword fun and an optional receiver type. */
        val NAME = Regex("""\bfun\s+(?:\w+\.)?(\w+)""")
    }
}
