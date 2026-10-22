package io.karpfen.ide.core.workspace.problems

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.session.VersionedDiagnostics
import io.karpfen.ide.core.document.session.mergeDiagnostics
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.files.FileKind
import java.nio.file.Path

/**
 * Applies the rule documented on [WorkspaceProblems]. Pure: the workspace calls it with the latest analysis, the
 * current diagnostics of its open documents and the loaded descriptor texts whenever one of them changes.
 *
 * Per file, the first matching case wins: an open document (its session's merged diagnostics and snapshot); an
 * analyzed karpfen file, in a context or loose (`mergeDiagnostics` of its own lexical, parse and file diagnostics plus
 * its project diagnostics, on the analyzed snapshot); any other file with project diagnostics, e.g. a descriptor (its
 * project diagnostics on the descriptor text, if loaded).
 */
internal class WorkspaceProblemsAggregator {

    /** The problems for [analysis], [openDocuments] and the descriptor texts by path ([descriptorTexts]). */
    fun aggregate(
        analysis: ProjectAnalysis,
        openDocuments: List<OpenDocumentDiagnostics>,
        descriptorTexts: Map<Path, TextSnapshot>,
    ): WorkspaceProblems {
        val byPath = LinkedHashMap<Path, FileProblems?>()
        for (open in openDocuments) {
            byPath[open.path] = problemsOf(open.path, open.kind, open.diagnostics.diagnostics, open.diagnostics.snapshot, isOpen = true)
        }
        for ((path, file) in analysis.files) {
            if (path in byPath) continue
            val diagnostics = mergeDiagnostics(
                file.lexicalDiagnostics,
                file.parse,
                listOf(file.fileDiagnostics, analysis.diagnosticsFor(path)),
            )
            byPath[path] = problemsOf(path, file.kind, diagnostics, file.snapshot, isOpen = false)
        }
        for ((path, diagnostics) in analysis.diagnostics) {
            if (path in byPath) continue
            val name = path.fileName?.toString().orEmpty()
            byPath[path] = problemsOf(path, FileKind.of(name), diagnostics, descriptorTexts[path], isOpen = false)
        }
        return WorkspaceProblems(analysis.generation, byPath.values.filterNotNull().sortedBy { it.path })
    }

    /** The problems of one file, or null if it has no [diagnostics]. */
    private fun problemsOf(
        path: Path,
        kind: FileKind,
        diagnostics: List<Diagnostic>,
        snapshot: TextSnapshot?,
        isOpen: Boolean,
    ): FileProblems? = if (diagnostics.isEmpty()) null else FileProblems(path, kind, diagnostics, snapshot, isOpen)
}

/** The current diagnostics of an open document (with the snapshot their ranges refer to). */
internal data class OpenDocumentDiagnostics(
    val path: Path,
    val kind: FileKind,
    val diagnostics: VersionedDiagnostics,
)
