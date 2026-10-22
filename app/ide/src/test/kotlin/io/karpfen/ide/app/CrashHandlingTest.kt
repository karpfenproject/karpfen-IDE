package io.karpfen.ide.app

import io.karpfen.ide.app.crash.JvmUncaughtExceptionHooks
import java.awt.EventQueue
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Uncaught exceptions are logged and shown instead of killing the application. */
class CrashHandlingTest {

    private val shown: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())

    @Test
    fun `install hooks the handler in once, however often it is called`() {
        val installed = mutableListOf<Thread.UncaughtExceptionHandler>()
        val crashHandling = CrashHandling(shown::add) { handler -> installed += handler }

        crashHandling.install()
        crashHandling.install()

        assertEquals(1, installed.size)
    }

    @Test
    fun `an uncaught exception reaching the installed handler is shown`() {
        var handler: Thread.UncaughtExceptionHandler? = null
        CrashHandling(shown::add) { handler = it }.install()
        val error = IllegalStateException("boom")

        assertNotNull(handler).uncaughtException(Thread.currentThread(), error)

        assertEquals(listOf<Throwable>(error), shown)
    }

    @Test
    fun `handle shows the error and never throws, even when showing fails`() {
        val error = IllegalArgumentException("bad")
        val crashHandling = CrashHandling({ shown += it; throw IllegalStateException("dialog broken") }) { }

        crashHandling.handle(error)

        assertEquals(listOf<Throwable>(error), shown)
    }

    @Test
    fun `the JVM hooks route worker-thread and event-dispatch-thread exceptions to the handler`() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val latch = CountDownLatch(2)
        try {
            CrashHandling({ shown += it; latch.countDown() }, JvmUncaughtExceptionHooks()).install()
            val worker = IllegalStateException("worker")
            val edt = IllegalStateException("event dispatch")

            Thread { throw worker }.apply { start() }.join()
            EventQueue.invokeLater { throw edt }

            assertTrue(latch.await(10, TimeUnit.SECONDS), "both exceptions were reported: $shown")
            assertEquals(setOf<Throwable>(worker, edt), shown.toSet())
            EventQueue.invokeAndWait { assertSame(Thread.currentThread().uncaughtExceptionHandler, Thread.getDefaultUncaughtExceptionHandler()) }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            EventQueue.invokeAndWait { Thread.currentThread().uncaughtExceptionHandler = null }
        }
    }
}
