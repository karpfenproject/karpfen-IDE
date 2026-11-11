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
package io.karpfen.ide.app.crash

import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/** File name of the IDE log in `SettingsLocations.logDirectory()`. The app's `logback.xml` writes the same file. */
internal const val IDE_LOG_FILE_NAME: String = "ide.log"

/** Title of the dialog that reports an uncaught error. */
internal const val ERROR_DIALOG_TITLE: String = "Unexpected Error"

/**
 * The error dialog for an uncaught [error]. It names what failed, with the exception's simple class name and its
 * message, or with the full class name when the message is blank. It also names [logFile], which holds the details,
 * such as the stack trace.
 */
internal fun errorDialogFor(error: Throwable, logFile: Path): DialogRequest.Error {
    val what = error.message?.takeIf { it.isNotBlank() }?.let { "${error::class.java.simpleName}: $it" }
        ?: error::class.java.name
    return DialogRequest.Error(ERROR_DIALOG_TITLE, "$what\n\nThe details were written to $logFile.")
}

/**
 * Shows uncaught errors in the workbench's error dialog once the workbench runs. [attach] passes the dialog port,
 * `WorkbenchState.dialogs.ask`. Before that, and while an error dialog is open, [show] declines, because
 * `CrashHandling` logs the error anyway. So a burst of errors, for example one per frame, never queues one dialog per
 * error.
 *
 * The presenter is thread-safe. [show] may be called from any thread, and the dialog is asked in [scope], the UI
 * scope. A failure of the dialog itself is logged and never rethrown, so it cannot report itself again.
 *
 * @param logFile named in the dialog as the place of the details.
 */
internal class ErrorDialogPresenter(private val scope: CoroutineScope, private val logFile: Path) {
    private val ask = AtomicReference<(suspend (DialogRequest.Error) -> Unit)?>(null)
    private val dialogOpen = AtomicBoolean(false)

    /** From now on, errors are shown through [ask], which suspends until the dialog is answered. */
    fun attach(ask: suspend (DialogRequest.Error) -> Unit) {
        this.ask.set(ask)
    }

    /**
     * Shows [error] unless no dialog port is attached yet or an error dialog is already open. Returns whether it is
     * shown.
     */
    fun show(error: Throwable): Boolean {
        val target = ask.get() ?: return false
        if (!dialogOpen.compareAndSet(false, true)) return false
        val request = errorDialogFor(error, logFile)
        scope.launch {
            try {
                target(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("The error dialog could not be shown", e)
            }
        }.invokeOnCompletion { dialogOpen.set(false) }
        return true
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ErrorDialogPresenter::class.java)
    }
}
