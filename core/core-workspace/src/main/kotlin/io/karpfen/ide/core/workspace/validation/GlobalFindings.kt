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
package io.karpfen.ide.core.workspace.validation

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.workspace.analysis.AnalyzedFile
import io.karpfen.ide.core.workspace.problems.FileProblems
import java.nio.file.Path

/**
 * Collects the findings that only a validation finds: the diagnostics of the verifier of dsl-tools that are not among
 * the problems of the analysis. A diagnostic is compared as the [ValidationFinding] it becomes, with its position in
 * the analyzed text. One that is among [known], or that was added before as the same finding, is left out. A state
 * machine that a context attaches to two objects is verified twice and can yield the same finding twice, and so can a
 * file that two contexts share.
 *
 * The collector keeps per file the analyzed file and the diagnostics it kept, so that [files] can hand them over in
 * the shape of the problems of the workspace. Use one instance for one validation.
 *
 * @param known the findings of the problems of the analysis.
 */
internal class GlobalFindings(private val known: Set<ValidationFinding>) {

    private val kept = LinkedHashSet<ValidationFinding>()
    private val byPath = LinkedHashMap<Path, KeptFile>()

    /** The findings kept so far, in the order they were added. None of them is among the known findings. */
    val findings: Set<ValidationFinding> get() = kept

    /**
     * Adds [diagnostic], which the verifier reported for [file], unless it is a known finding or was added before. Its
     * range refers to the snapshot of [file].
     */
    fun add(file: AnalyzedFile, diagnostic: Diagnostic) {
        val snapshot = file.snapshot
        val finding = ValidationFinding.of(file.path, diagnostic, snapshot.positionOf(diagnostic.range.start.coerceAtMost(snapshot.length)))
        if (finding in known || !kept.add(finding)) return
        byPath.getOrPut(file.path) { KeptFile(file) }.diagnostics += diagnostic
    }

    /**
     * One [FileProblems] per file with a kept diagnostic, sorted by path. Each lists the kept diagnostics in the order
     * of `Diagnostic.ORDER` with the snapshot the analysis read and is not open.
     */
    fun files(): List<FileProblems> = byPath.values
        .map { FileProblems(it.file.path, it.file.kind, it.diagnostics.sortedWith(Diagnostic.ORDER), it.file.snapshot, isOpen = false) }
        .sortedBy { it.path }

    /** The diagnostics kept for [file], in the order they were added. */
    private class KeptFile(val file: AnalyzedFile) {
        val diagnostics = ArrayList<Diagnostic>()
    }
}
