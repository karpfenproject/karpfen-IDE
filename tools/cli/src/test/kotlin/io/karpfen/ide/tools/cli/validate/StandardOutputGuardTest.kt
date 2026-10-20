package io.karpfen.ide.tools.cli.validate

import io.karpfen.ide.tools.cli.ProcessStreams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StandardOutputGuardTest {

    @Test
    fun `the default streams are the process streams at construction`() {
        ProcessStreams().use {
            val guard = StandardOutputGuard()

            assertSame(System.out, guard.output)
            assertSame(System.err, guard.errorOutput)
        }
    }

    @Test
    fun `both process streams are silenced inside the block, also on other threads, and restored afterwards`() = runTest {
        ProcessStreams().use { streams ->
            val out = System.out
            val err = System.err
            val guard = StandardOutputGuard()

            val result = guard.silenced {
                assertNotSame(out, System.out)
                assertNotSame(err, System.err)
                System.out.println("from the caller")
                System.err.println("to stderr")
                delay(1_000)
                withContext(Dispatchers.Default) { System.out.println("from another thread") }
                guard.output.println("report")
                guard.errorOutput.println("message")
                42
            }

            assertEquals(42, result)
            assertSame(out, System.out)
            assertSame(err, System.err)
            assertEquals("report\n", streams.stdout())
            assertEquals("message\n", streams.stderr())
        }
    }

    @Test
    fun `the streams are restored when the block throws`() = runTest {
        ProcessStreams().use { streams ->
            val out = System.out
            val guard = StandardOutputGuard()

            assertFailsWith<IllegalStateException> {
                guard.silenced<Unit> {
                    System.out.println("lost")
                    error("conversion failed")
                }
            }

            assertSame(out, System.out)
            assertEquals("", streams.stdout())
        }
    }

    @Test
    fun `the streams are restored when the caller is cancelled`() = runTest {
        ProcessStreams().use {
            val out = System.out
            val err = System.err
            val guard = StandardOutputGuard()
            val job = launch { guard.silenced { awaitCancellation() } }
            runCurrent()
            assertNotSame(out, System.out)

            job.cancelAndJoin()

            assertSame(out, System.out)
            assertSame(err, System.err)
        }
    }

    @Test
    fun `silenced is not reentrant but can run again after a block finished`() = runTest {
        val guard = StandardOutputGuard(PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream()))
        val out = System.out

        val failure = guard.silenced { runCatching { guard.silenced { Unit } }.exceptionOrNull() }
        val second = async { guard.silenced { "again" } }.await()

        assertTrue(failure is IllegalStateException, "nested call: $failure")
        assertEquals("again", second)
        assertSame(out, System.out)
    }
}
