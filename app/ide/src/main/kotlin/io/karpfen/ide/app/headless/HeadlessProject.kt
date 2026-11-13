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
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.ui.workbench.state.WorkbenchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.nio.file.Path

/**
 * A project folder opened for a headless command: the prepared [session] of the IDE with its open [workspace], and
 * the [dialogs] that answer the workbench's questions on the console. Closing it stops answering and closes the
 * session, which closes the workspace.
 */
internal class HeadlessProject(
    val session: IdeSession,
    val workspace: Workspace,
    val dialogs: HeadlessDialogs,
    private val answering: Job,
) : AutoCloseable {

    /** The workbench state of the session. */
    val state: WorkbenchState get() = session.state

    override fun close() {
        answering.cancel()
        session.close()
    }

    companion object {
        /**
         * Opens [folder] through [prepare], which is `IdeApplication.prepare`, in [scope], and starts answering the
         * dialogs on [console], with the validation reports in [format]. Returns the project, or null if the folder
         * could not be opened. The dialog that said why is written then, and the session is closed again.
         */
        suspend fun open(
            scope: CoroutineScope,
            folder: Path,
            console: HeadlessConsole,
            prepare: suspend (CoroutineScope, Path) -> IdeSession,
            format: OutputFormat = OutputFormat.TEXT,
        ): HeadlessProject? {
            val session = prepare(scope, folder)
            val dialogs = HeadlessDialogs(session.state.dialogs, console, ReportFormatter(format))
            val answering = dialogs.start(scope)
            val workspace = session.state.workspace.value
            if (workspace == null) {
                answering.cancel()
                session.close()
                console.message("$folder could not be opened as a project")
                return null
            }
            return HeadlessProject(session, workspace, dialogs, answering)
        }
    }
}
