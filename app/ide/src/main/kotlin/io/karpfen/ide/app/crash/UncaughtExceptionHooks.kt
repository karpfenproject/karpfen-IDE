package io.karpfen.ide.app.crash

import java.awt.EventQueue

/** Where the JVM reports uncaught exceptions; the seam that keeps `CrashHandling` testable without touching the JVM. */
internal fun interface UncaughtExceptionHooks {
    /** Routes uncaught exceptions to [handler]. */
    fun install(handler: Thread.UncaughtExceptionHandler)
}

/** The JVM's hooks: the default uncaught-exception handler and the AWT event-dispatch thread's own handler. */
internal class JvmUncaughtExceptionHooks : UncaughtExceptionHooks {

    /**
     * Makes [handler] the default uncaught-exception handler, which every thread without its own uses (worker threads
     * included), and the event-dispatch thread's own handler. The latter is set on that thread, so exceptions thrown by
     * event handlers reach [handler] even if a library installed a thread-specific one; the event-dispatch thread keeps
     * running after reporting them.
     */
    override fun install(handler: Thread.UncaughtExceptionHandler) {
        Thread.setDefaultUncaughtExceptionHandler(handler)
        EventQueue.invokeLater { Thread.currentThread().uncaughtExceptionHandler = handler }
    }
}
