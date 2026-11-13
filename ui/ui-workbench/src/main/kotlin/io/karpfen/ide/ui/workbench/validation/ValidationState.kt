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
package io.karpfen.ide.ui.workbench.validation

import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.validation.ProjectValidation
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The validation of the whole open project, as the Validate button below the editor, the command Validate Project of
 * the Run menu and a run before it deploys ask for it. The work itself is `Workspace.validate`. This state only starts
 * it, tells whether it is busy, shows the report in a [DialogRequest.ValidationReport] and keeps the report of the last
 * finished validation as [lastReport].
 *
 * One validation runs at a time. While one runs, [isBusy] is true, the button shows that, and [validate] starts no
 * second one. A run that validates while the button's validation runs waits for it and then validates again, so the
 * run always checks the files it saved. Without an open folder there is nothing to validate, and [canValidate] is false.
 *
 * [lastReport] is state: the report of the last validation of the open folder, whoever asked for it. The Problems view
 * shows its global findings. [observeRunChecks] is an event: it hands over the report of each check before a run, and
 * only of such a check, before the run goes on. The headless mode prints the warnings of a run that way, so it never
 * has to tell a report of the run apart from another one.
 *
 * Use the class from the UI thread only. [validate] launches its work in [scope] and returns its job.
 *
 * @param workspace the workspace of the open folder, or null while none is open.
 * @param dialogs shows the report.
 */
public class ValidationState internal constructor(
    private val scope: CoroutineScope,
    private val workspace: StateFlow<Workspace?>,
    private val dialogs: DialogState,
) {
    private val busy = MutableStateFlow(false)
    private val one = Mutex()
    private val runCheckListeners = ArrayList<(ProjectValidation) -> Unit>()
    private val last = MutableStateFlow<ProjectValidation?>(null)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { workspace.collect { last.value = null } }
    }

    /** True while a validation runs. */
    public val isBusy: StateFlow<Boolean> get() = busy.asStateFlow()

    /**
     * The report of the last finished validation of the open folder, whether the Validate button, the command Validate
     * Project or a run asked for it. It is null before the first validation of the folder. A validation replaces it as
     * soon as its report is done, before any dialog shows it. Closing the folder or opening another one sets it back
     * to null, and a validation that finishes for a folder that is no longer open leaves it alone.
     */
    public val lastReport: StateFlow<ProjectValidation?> get() = last.asStateFlow()

    /** True while a folder is open, so there is a project to validate. */
    public val isAvailable: StateFlow<Boolean> =
        workspace.map { it != null }.stateIn(scope, SharingStarted.Eagerly, workspace.value != null)

    /** True if [validate] would start a validation: a folder is open and no validation runs. */
    public val canValidate: StateFlow<Boolean> =
        combine(isAvailable, busy) { available, running -> available && !running }
            .stateIn(scope, SharingStarted.Eagerly, isAvailable.value && !busy.value)

    /**
     * Validates the whole open project and shows the report in a dialog titled [TITLE]. Does nothing and returns a job
     * that is already complete without an open folder or while a validation runs. The validation starts before this
     * returns, so [isBusy] is true at once.
     */
    public fun validate(): Job {
        val opened = workspace.value ?: return DONE
        if (busy.value) return DONE
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val report = validated(opened) ?: return@launch
            dialogs.ask(DialogRequest.ValidationReport(TITLE, report))
        }
    }

    /**
     * Calls [listener] with the report of every check before a run from now on, until the returned handle is disposed.
     * The listener is called on the UI thread as soon as the report is done, before the run goes on and before a
     * report with errors is shown.
     */
    public fun observeRunChecks(listener: (ProjectValidation) -> Unit): DisposableHandle {
        runCheckListeners.add(listener)
        return DisposableHandle { runCheckListeners.remove(listener) }
    }

    /**
     * Validates the whole open project before a run and hands the report to the listeners of [observeRunChecks].
     * Returns true if the report has no error. If it has one, it shows the report in a dialog titled
     * [RUN_BLOCKED_TITLE], waits until it is closed and returns false. A report with warnings only opens no dialog.
     * Without an open folder, or if the workspace closes meanwhile, it returns false.
     */
    internal suspend fun checkBeforeRun(): Boolean {
        val opened = workspace.value ?: return false
        val report = validated(opened) ?: return false
        runCheckListeners.toList().forEach { it(report) }
        if (!report.hasErrors) return true
        dialogs.ask(DialogRequest.ValidationReport(RUN_BLOCKED_TITLE, report))
        return false
    }

    /**
     * The report of [opened], made while [isBusy] is true and no other validation runs. It becomes [lastReport] if
     * [opened] is still the open workspace. Returns null if the workspace was closed before the report was done.
     */
    private suspend fun validated(opened: Workspace): ProjectValidation? = one.withLock {
        busy.value = true
        try {
            opened.validate().also { report -> if (workspace.value === opened) last.value = report }
        } catch (closed: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (closed: IllegalStateException) {
            null
        } finally {
            busy.value = false
        }
    }

    public companion object {
        /** The title of the report that the Validate button and the command Validate Project show. */
        public const val TITLE: String = "Validate Project"

        /** The title of the report that a run shows when the project has errors and the run does not start. */
        public const val RUN_BLOCKED_TITLE: String = "Run Blocked: the Project Has Errors"

        /** The job of a request with nothing to do. */
        private val DONE: Job = Job().apply { complete() }
    }
}
