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
 * Pushes the project diagnostics of the latest analysis into the sessions of the open documents, as the external
 * source `ExternalDiagnosticSources.PROJECT`. It does so whenever an analysis completes or documents are opened.
 *
 * Each document receives `ProjectAnalysis.diagnosticsFor(path)`. The diagnostics are tied to the text they were
 * computed from. For a karpfen source that is its `AnalyzedFile.snapshot`. For an environment descriptor it is the
 * descriptor text, the `DescriptorSource.snapshot` in `PublishedAnalysis.descriptors`. The publisher picks the target
 * snapshot like this.
 * - If that snapshot is a version of the document's text, with the same lineage, it is used. The session ignores a
 *   snapshot of another lineage, for example from an analysis that started before the document was closed and
 *   reopened.
 * - If the diagnostics were computed from the disk and the text is still identical, the document's current snapshot
 *   is used. So a document that was just opened gets its findings at once, not after the next analysis.
 * - Otherwise nothing is pushed. The next analysis, which the change triggers, catches up.
 *
 * Project diagnostics never hold the syntax errors of a file, because its session computes those from its own text.
 * So nothing is reported twice, and syntax errors follow the editor text without waiting for an analysis. A document
 * that the analysis did not see at all gets the project diagnostics of its path on its current snapshot. An example is
 * a file outside every context and outside the tree. Such a path usually has no project diagnostics, so its project
 * diagnostics are cleared.
 */
internal class ProjectDiagnosticsPublisher {

    /**
     * Pushes the latest of [analyses] into every document of [documents] whenever either changes. Nothing is pushed
     * before the first analysis, whose generation is 0.
     */
    fun start(scope: CoroutineScope, analyses: StateFlow<PublishedAnalysis>, documents: StateFlow<List<OpenDocument>>): Job =
        scope.launch {
            combine(analyses, documents) { published, open -> published to open }
                .collect { (published, open) -> if (published.analysis.generation > 0) open.forEach { push(published, it) } }
        }

    /** Pushes the project diagnostics of [published] into the session of [document] by the rules on the class. */
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
