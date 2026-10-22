package io.karpfen.ide.core.workspace.analysis

import io.karpfen.ide.core.document.session.ExternalDiagnosticSources
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.document.OpenDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Pushes the project diagnostics of the latest analysis into the open documents' sessions as external source
 * `ExternalDiagnosticSources.PROJECT`, whenever an analysis completes or documents are opened.
 *
 * Each document receives `ProjectAnalysis.diagnosticsFor(path)` for the snapshot the analysis used:
 * - `AnalyzedFile.snapshot` when it is a version of the document's text (same lineage); the session ignores a
 *   snapshot of another lineage, e.g. of an analysis that started before the document was closed and reopened;
 * - the document's current snapshot when the analysis read the file from disk and the text is still identical (a
 *   document just opened gets its DSL_TOOLS findings at once instead of after the next analysis);
 * - nothing otherwise (the next analysis, triggered by the change, catches up).
 *
 * A document the analysis did not see at all (e.g. a file outside every context and the tree) has its project
 * diagnostics cleared.
 */
internal class ProjectDiagnosticsPublisher {

    /** Pushes the latest of [analyses] into every document of [documents] whenever either changes. */
    fun start(scope: CoroutineScope, analyses: StateFlow<ProjectAnalysis>, documents: StateFlow<List<OpenDocument>>): Job =
        scope.launch {
            combine(analyses, documents) { analysis, open -> analysis to open }
                .collect { (analysis, open) -> if (analysis.generation > 0) open.forEach { push(analysis, it) } }
        }

    /** Pushes the project diagnostics of [analysis] into [document]'s session (see the class documentation). */
    fun push(analysis: ProjectAnalysis, document: OpenDocument) {
        val session = document.session
        val current = session.text.snapshot.value
        val analyzed = analysis.file(document.path)
        val target: TextSnapshot = when {
            analyzed == null -> current
            analyzed.snapshot.lineage === current.lineage -> analyzed.snapshot
            analyzed.snapshot.text == current.text -> current
            else -> return
        }
        session.setExternalDiagnostics(ExternalDiagnosticSources.PROJECT, target, analysis.diagnosticsFor(document.path))
    }
}
