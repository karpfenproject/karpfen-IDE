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
package io.karpfen.ide.core.workspace.frame

import io.karpfen.ide.core.document.frame.FramedDocument
import io.karpfen.ide.core.document.frame.PieceDiagnostics
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.document.session.ExternalDiagnosticSources
import io.karpfen.ide.core.workspace.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * The checks of the project analysis for the piece of a [FramedDocument] that [session] edits. After each parse of the
 * piece, and again after each analysis of [workspace], it lets [Workspace.readInPlace] read the whole text of the
 * piece in the place of the file of the document. The findings that lie in the piece go into [session] as the external
 * source [ExternalDiagnosticSources.PROJECT], at their places in the piece, as [PieceDiagnostics.inPiece] maps them. A
 * finding elsewhere in the file is left out.
 *
 * As in the project analysis, a whole text with syntax errors is not read, and the findings of the read go away while
 * it has them. A piece that the document cannot place gets no findings. A parse of an older version of the piece than
 * the current one is skipped, because the parse of the current one follows. A newer parse or analysis cancels a read
 * that is still running.
 */
internal class FramedChecks(
    private val workspace: Workspace,
    private val document: FramedDocument,
    private val session: DocumentSession,
) {

    /** Follows the parses of the piece and the analyses of the workspace in [scope] until [scope] ends. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        combine(session.parse.filterNotNull(), workspace.analysis) { outcome, _ -> outcome }
            .collectLatest { outcome -> check(outcome) }
    }

    /** Checks the piece if [outcome] is the parse of its current text, as the class describes. */
    private suspend fun check(outcome: ParseOutcome) {
        val piece = session.text.snapshot.value
        if (piece.version != outcome.version) return
        val framed = document.frame(piece)
        val found = if (framed == null || !outcome.isSyntaxClean) {
            emptyList()
        } else {
            PieceDiagnostics.inPiece(framed.frame, workspace.readInPlace(document.path, framed.snapshot))
        }
        session.setExternalDiagnostics(ExternalDiagnosticSources.PROJECT, piece, found)
    }
}

/**
 * Starts the checks of the project analysis of [workspace] for the piece of [document] that [session] edits, as
 * [FramedChecks] describes, in [scope]. They end with [scope] or with the returned job. The function only wires
 * objects.
 */
public fun launchFramedChecks(scope: CoroutineScope, workspace: Workspace, document: FramedDocument, session: DocumentSession): Job =
    FramedChecks(workspace, document, session).start(scope)
