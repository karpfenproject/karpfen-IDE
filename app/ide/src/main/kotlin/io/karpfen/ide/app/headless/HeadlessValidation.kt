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

import io.karpfen.ide.app.IdeSession
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/**
 * `karpfen-ide validate <folder>`. It opens the folder as the window does and validates the whole project exactly as
 * Validate in the window does, through the workbench's validation. The report that the window shows in a dialog is
 * written to standard output by [HeadlessDialogs], one line per finding and a summary, as [ValidationLines] describes.
 *
 * The status is [HeadlessExitCodes.OK] for a project without errors, warnings or not, and
 * [HeadlessExitCodes.ERRORS_FOUND] for one with errors. A folder that cannot be opened, or a validation that gives no
 * report, ends with [HeadlessExitCodes.FAILURE].
 *
 * @param console where the report and the messages go.
 * @param prepare opens a folder, which is `IdeApplication.prepare`.
 */
internal class HeadlessValidation(
    private val console: HeadlessConsole,
    private val prepare: suspend (CoroutineScope, Path) -> IdeSession,
) {
    /** Validates the project in the folder of [command], driving the workbench in [scope], and returns the status. */
    suspend fun validate(scope: CoroutineScope, command: HeadlessCommand.Validate): Int {
        val project = HeadlessProject.open(scope, command.folder, console, prepare) ?: return HeadlessExitCodes.FAILURE
        project.use {
            it.state.validation.validate().join()
            val report = it.dialogs.lastReport
            if (report == null) {
                console.message("the project could not be validated")
                return HeadlessExitCodes.FAILURE
            }
            return if (report.hasErrors) HeadlessExitCodes.ERRORS_FOUND else HeadlessExitCodes.OK
        }
    }
}
