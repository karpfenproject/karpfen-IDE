package io.karpfen.ide.app.crash

import java.awt.EventQueue

/** Where the JVM reports uncaught exceptions; the seam that keeps `CrashHandling` testable without touching the JVM. */
internal fun interface UncaughtExceptionHooks {
    /** Routes uncaught exceptions to [handler]. */
    fun install(handler: Thread.UncaughtExceptionHandler)
}

/**
 * The JVM's hooks: the default uncaught-exception handler (every thread without its own, worker threads included) and
 * the AWT event-dispatch thread's own handler (set on that thread, so exceptions thrown by event handlers reach
 * [handler] even if a library installed a thread-specific one; the EDT keeps running after reporting them).
 */
internal class JvmUncaughtExceptionHooks : UncaughtExceptionHooks {
    override fun install(handler: Thread.UncaughtExceptionHandler) {
        Thread.setDefaultUncaughtExceptionHandler(handler)
        EventQueue.invokeLater { Thread.currentThread().uncaughtExceptionHandler = handler }
    }
}
