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
package io.karpfen.ide.ui.workbench.tabs

import io.karpfen.ide.core.document.frame.FramedDocument
import io.karpfen.ide.core.document.session.DocumentId
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.services.framedLanguageServices
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextFraming
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.frame.launchFramedChecks
import io.karpfen.ide.notation.diagram.edit.DiagramCell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * The document of one open code cell of a diagram, wired to the language of its file. It is the adapter between a
 * [DiagramCell], which knows how its code stands in the text of its file, and the language core, which knows
 * documents and languages but no diagrams.
 *
 * The cell's code lives in [session], a document of its own that starts with the code of [cell] and is never written to
 * disk. It is named after the file of [file] with `.cell.` put in front, next to that file. Its [FramedDocument] puts
 * each text of the session in its place in the file through `DiagramCell.frame`, so the session is coloured and parsed
 * as part of the file: it gets the semantic colours and the syntax errors that the code has there. [services] are the
 * language services of the workspace, [workspaceServices], seen through that document, so completion and hover answer
 * as in the text of the file. With a [workspace], the checks of the project analysis follow the session too, as
 * `launchFramedChecks` describes. A cell that cannot be framed is coloured but not checked, as a piece on its own.
 *
 * @param file the session of the file whose diagram shows the cell.
 * @param scope the scope of the work of the session and of its checks. [close] ends that work.
 * @param config the tuning of the session.
 */
internal class CodeCellSession(
    file: DocumentSession,
    cell: DiagramCell,
    workspaceServices: LanguageServices,
    workspace: Workspace?,
    scope: CoroutineScope,
    config: DocumentSessionConfig = DocumentSessionConfig(),
) : AutoCloseable {

    /** The cell's code in its place in the file. */
    val document: FramedDocument = FramedDocument(file.id.path, file.support, TextFraming { cell.frame(it) })

    /** The session that edits the code of the cell. */
    val session: DocumentSession = DocumentSession(
        DocumentId(file.id.path.resolveSibling(CELL_PREFIX + file.id.path.fileName)),
        document.support,
        EditableText(cell.code),
        scope,
        config,
    )

    /** The language services of [session]. */
    val services: LanguageServices = framedLanguageServices(workspaceServices, document)

    /** The checks of the project analysis that follow [session], or null without a workspace. */
    private val checks: Job? = workspace?.let { launchFramedChecks(scope, it, document, session) }

    /** Ends the checks and closes [session]. */
    override fun close() {
        checks?.cancel()
        session.close()
    }

    private companion object {
        /** What stands in front of the name of a file in the name of the document of one of its code cells. */
        const val CELL_PREFIX = ".cell."
    }
}
