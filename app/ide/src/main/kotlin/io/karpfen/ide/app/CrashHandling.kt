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
 * Uncaught exceptions are logged (SLF4J) and shown in an error dialog instead of terminating the application. Installs
 * the default uncaught-exception handler and the AWT event-queue handler.
 *
 * The composition root routes the other sources of unexpected failures to [handle] as well: the application scope's
 * `CoroutineExceptionHandler` (failed intent flows) and the main window's Compose `WindowExceptionHandler` (failures
 * while composing, laying out, drawing or dispatching input), which by default would close the window.
 *
 * @param showError shows the error to the user (the app passes the error-dialog presenter, which uses
 *   `WorkbenchState.dialogs` once the workbench runs); called on the failing thread, so it must be thread-safe and
 *   should not block.
 */
public class CrashHandling internal constructor(
    private val showError: (Throwable) -> Unit,
    private val hooks: UncaughtExceptionHooks,
) {
    /** Crash handling through the JVM's hooks. */
    public constructor(showError: (Throwable) -> Unit) : this(showError, JvmUncaughtExceptionHooks())

    private val installed = AtomicBoolean(false)

    /** Installs the handlers; idempotent. */
    public fun install() {
        if (installed.compareAndSet(false, true)) {
            hooks.install { thread, error -> report("in thread '${thread.name}'", error) }
        }
    }

    /**
     * Logs [error] at ERROR with its stack trace and passes it to `showError`. Never throws: a failure of `showError`
     * is logged, so a broken dialog cannot turn one error into a crash or a loop.
     */
    public fun handle(error: Throwable) {
        report("in thread '${Thread.currentThread().name}'", error)
    }

    /** Logs [error] from [origin] and shows it; a failure while doing so is only logged. */
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
