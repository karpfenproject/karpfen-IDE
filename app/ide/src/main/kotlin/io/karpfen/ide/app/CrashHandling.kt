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
package io.karpfen.ide.app

import io.karpfen.ide.app.crash.JvmUncaughtExceptionHooks
import io.karpfen.ide.app.crash.UncaughtExceptionHooks
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Logs uncaught exceptions through SLF4J and shows them in an error dialog instead of terminating the application. It
 * installs the default uncaught-exception handler and the AWT event-queue handler.
 *
 * The composition root routes two other sources of unexpected failures to [handle] as well. One is the
 * `CoroutineExceptionHandler` of the application scope, which receives failed intent flows. The other is the Compose
 * `WindowExceptionHandler` of the main window, which receives failures while composing, laying out, drawing or
 * dispatching input. By default that handler would close the window.
 *
 * @param showError shows the error to the user. The app passes the error-dialog presenter, which uses
 *   `WorkbenchState.dialogs` once the workbench runs. It is called on the failing thread, so it must be thread-safe
 *   and should not block.
 */
public class CrashHandling internal constructor(
    private val showError: (Throwable) -> Unit,
    private val hooks: UncaughtExceptionHooks,
) {
    /** Crash handling through the JVM's hooks. */
    public constructor(showError: (Throwable) -> Unit) : this(showError, JvmUncaughtExceptionHooks())

    private val installed = AtomicBoolean(false)

    /** Installs the handlers. Calling it again does nothing. */
    public fun install() {
        if (installed.compareAndSet(false, true)) {
            hooks.install { thread, error -> report("in thread '${thread.name}'", error) }
        }
    }

    /**
     * Logs [error] at ERROR with its stack trace and passes it to `showError`. It never throws. A failure of
     * `showError` is logged, so a broken dialog cannot turn one error into a crash or a loop.
     */
    public fun handle(error: Throwable) {
        report("in thread '${Thread.currentThread().name}'", error)
    }

    /** Logs [error] from [origin] and shows it. A failure while doing so is only logged. */
    private fun report(origin: String, error: Throwable) {
        try {
            log.error("Uncaught exception {}", origin, error)
            showError(error)
        } catch (failure: Throwable) {
            runCatching { log.error("The uncaught exception could not be shown", failure) }
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(CrashHandling::class.java)
    }
}
