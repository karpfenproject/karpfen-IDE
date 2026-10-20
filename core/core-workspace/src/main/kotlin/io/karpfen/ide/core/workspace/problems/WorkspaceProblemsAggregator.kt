package io.karpfen.ide.core.workspace.problems

import io.karpfen.ide.core.document.session.VersionedDiagnostics
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.files.FileKind
import java.nio.file.Path

/**
 * Applies the rule documented on [WorkspaceProblems]. Pure: the workspace calls it with the latest analysis, the
 * current diagnostics of its open documents and the loaded descriptor texts whenever one of them changes.
 */
internal class WorkspaceProblemsAggregator {

    /** The problems for [analysis], [openDocuments] and the descriptor texts by path ([descriptorTexts]). */
    fun aggregate(
        analysis: ProjectAnalysis,
        openDocuments: List<OpenDocumentDiagnostics>,
        descriptorTexts: Map<Path, TextSnapshot>,
    ): WorkspaceProblems = TODO("WP-WORKSPACE: merge ${openDocuments.size} open documents into analysis ${analysis.generation}")
}

/** The current diagnostics of an open document (with the snapshot their ranges refer to). */
internal data class OpenDocumentDiagnostics(
    val path: Path,
    val kind: FileKind,
    val diagnostics: VersionedDiagnostics,
)
