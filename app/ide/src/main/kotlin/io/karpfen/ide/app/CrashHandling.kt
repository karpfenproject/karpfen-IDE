package io.karpfen.ide.app

/**
 * Uncaught exceptions are logged (SLF4J) and shown in an error dialog instead of terminating the application
 * (spec §9). Installs the default uncaught-exception handler and the AWT event-queue handler.
 */
public class CrashHandling(private val showError: (Throwable) -> Unit) {
    /** Installs the handlers; idempotent. */
    public fun install(): Unit = TODO("WP-APP: uncaught exception handling")
}
