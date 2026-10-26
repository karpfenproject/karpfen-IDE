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
 * Each document receives `ProjectAnalysis.diagnosticsFor(path)` for the text the diagnostics were computed from — the
 * `AnalyzedFile.snapshot` of a karpfen source, the descriptor text (`DescriptorSource.snapshot` of
 * `PublishedAnalysis.descriptors`) of an environment descriptor:
 * - that snapshot when it is a version of the document's text (same lineage); the session ignores a snapshot of
 *   another lineage, e.g. of an analysis that started before the document was closed and reopened;
 * - the document's current snapshot when the diagnostics were computed from the disk and the text is still identical
 *   (a document just opened gets its findings at once instead of after the next analysis);
 * - nothing otherwise (the next analysis, triggered by the change, catches up).
 *
 * Project diagnostics never hold a file's syntax errors, which its session computes from its own text; so nothing is
 * reported twice, and syntax errors follow the editor text without waiting for an analysis. A document the analysis
 * did not see at all (e.g. a file outside every context and the tree) has its project diagnostics cleared.
 */
internal class ProjectDiagnosticsPublisher {

    /** Pushes the latest of [analyses] into every document of [documents] whenever either changes. */
    fun start(scope: CoroutineScope, analyses: StateFlow<PublishedAnalysis>, documents: StateFlow<List<OpenDocument>>): Job =
        scope.launch {
            combine(analyses, documents) { published, open -> published to open }
                .collect { (published, open) -> if (published.analysis.generation > 0) open.forEach { push(published, it) } }
        }

    /** Pushes the project diagnostics of [published] into [document]'s session (see the class documentation). */
    fun push(published: PublishedAnalysis, document: OpenDocument) {
        val analysis = published.analysis
        val session = document.session
        val current = session.text.snapshot.value
        val computedFrom: TextSnapshot? = analysis.file(document.path)?.snapshot ?: published.descriptors[document.path]?.snapshot
        val target: TextSnapshot = when {
            computedFrom == null -> current
            computedFrom.lineage === current.lineage -> computedFrom
            computedFrom.text == current.text -> current
            else -> return
        }
        session.setExternalDiagnostics(ExternalDiagnosticSources.PROJECT, target, analysis.diagnosticsFor(document.path))
    }
}
