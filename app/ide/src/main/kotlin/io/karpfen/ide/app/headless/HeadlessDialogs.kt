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
package io.karpfen.ide.app.headless

import io.karpfen.ide.core.workspace.validation.ProjectValidation
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.dialog.DialogState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The dialogs of the workbench as the headless mode answers them. The workbench asks through its [DialogState] and
 * waits for an answer, and in the window the dialog layer shows the question and gives the user's answer. Here this
 * class takes that place: it writes what a dialog has to say to the console and answers at once, so no flow of the
 * workbench ever waits for a user who is not there.
 *
 * - A validation report, from Validate or from a run that the project's errors block, is written as
 *   [ValidationLines] says, kept as [lastReport] and acknowledged.
 * - An error is written to standard error as a message of the mode, with its title and its text, and acknowledged.
 * - Any other question has no answer on the command line. It is named on standard error and cancelled. The headless
 *   commands are built so that none comes up.
 *
 * Use the class from the thread that drives the workbench state.
 *
 * @param dialogs the dialogs of the workbench state.
 * @param console where the dialogs are written.
 */
internal class HeadlessDialogs(private val dialogs: DialogState, private val console: HeadlessConsole) {

    /** The report of the last validation that asked to be shown, or null if none did. */
    var lastReport: ProjectValidation? = null
        private set

    /** Answers every dialog from now on, in [scope], until the returned job is cancelled. */
    fun start(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        var answered: DialogRequest<*>? = null
        while (true) {
            val request = dialogs.current.first { it != null && it !== answered } ?: continue
            answered = request
            dialogs.respond(answerTo(request))
        }
    }

    /** Writes what [request] has to say and returns the answer it gets. */
    private fun answerTo(request: DialogRequest<*>): DialogResponse = when (request) {
        is DialogRequest.ValidationReport -> {
            lastReport = request.validation
            ValidationLines.of(request.validation).forEach(console::line)
            DialogResponse.Confirm
        }
        is DialogRequest.Error -> {
            console.message("${request.title}: ${request.message}")
            DialogResponse.Confirm
        }
        else -> {
            console.message("the question ${request::class.simpleName} cannot be answered without a window; it is cancelled")
            DialogResponse.Cancel
        }
    }
}
