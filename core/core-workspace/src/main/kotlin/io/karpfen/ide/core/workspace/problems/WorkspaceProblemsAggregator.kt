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
package io.karpfen.ide.core.workspace.problems

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.session.VersionedDiagnostics
import io.karpfen.ide.core.document.session.mergeDiagnostics
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.context.DescriptorSource
import io.karpfen.ide.core.workspace.files.FileKind
import java.nio.file.Path

/**
 * Applies the rule described on [WorkspaceProblems]. It has no state and no side effects. The workspace calls it with
 * the latest analysis, the current diagnostics of its open documents and the read descriptors whenever one of them
 * changes.
 *
 * For each file the first matching case wins.
 * - An open document gets the merged diagnostics and the snapshot of its session.
 * - An analyzed karpfen file, in a context or loose, gets `mergeDiagnostics` of its own lexical, parse and file
 *   diagnostics plus its project diagnostics, on the analyzed snapshot.
 * - A read descriptor gets `mergeDiagnostics` of the diagnostics of its text plus its project diagnostics, on its
 *   text.
 * - Any other path with project diagnostics, such as a file that cannot be read, gets those diagnostics without a
 *   text.
 */
internal class WorkspaceProblemsAggregator {

    /** The problems for [analysis], [openDocuments] and the read [descriptors], by path. */
    fun aggregate(
        analysis: ProjectAnalysis,
        openDocuments: List<OpenDocumentDiagnostics>,
        descriptors: Map<Path, DescriptorSource>,
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
        for ((path, descriptor) in descriptors) {
            if (path in byPath) continue
            val diagnostics = mergeDiagnostics(emptyList(), parse = null, listOf(descriptor.ownDiagnostics, analysis.diagnosticsFor(path)))
            byPath[path] = problemsOf(path, FileKind.ENV_DESCRIPTOR, diagnostics, descriptor.snapshot, isOpen = false)
        }
        for ((path, diagnostics) in analysis.diagnostics) {
            if (path in byPath) continue
            val name = path.fileName?.toString().orEmpty()
            byPath[path] = problemsOf(path, FileKind.of(name), diagnostics, snapshot = null, isOpen = false)
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

/** The current diagnostics of an open document, with the snapshot their ranges refer to. */
internal data class OpenDocumentDiagnostics(
    val path: Path,
    val kind: FileKind,
    val diagnostics: VersionedDiagnostics,
)
