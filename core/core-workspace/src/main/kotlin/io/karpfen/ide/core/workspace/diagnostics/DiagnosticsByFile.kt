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
package io.karpfen.ide.core.workspace.diagnostics

import io.karpfen.ide.core.document.Diagnostic
import java.nio.file.Path

/**
 * Collects diagnostics per file and hands them out in the form that `ContextResolution.diagnostics` and
 * `ProjectAnalysis.diagnostics` promise. Every list is sorted with [Diagnostic.ORDER] and has no duplicates, and files
 * without diagnostics are absent. It is a mutable builder that only the computation owning it uses.
 */
internal class DiagnosticsByFile {

    private val byFile = LinkedHashMap<Path, MutableList<Diagnostic>>()

    /** Adds [diagnostics] of [path]. */
    fun add(path: Path, diagnostics: Collection<Diagnostic>) {
        if (diagnostics.isNotEmpty()) byFile.getOrPut(path) { ArrayList() } += diagnostics
    }

    /** Adds one [diagnostic] of [path]. */
    fun add(path: Path, diagnostic: Diagnostic) {
        byFile.getOrPut(path) { ArrayList() } += diagnostic
    }

    /** Adds every entry of [diagnostics]. */
    fun addAll(diagnostics: Map<Path, List<Diagnostic>>) {
        diagnostics.forEach { (path, list) -> add(path, list) }
    }

    /**
     * The collected diagnostics. The lists are sorted, distinct and not empty, and the files keep the order in which
     * they were first added.
     */
    fun build(): Map<Path, List<Diagnostic>> =
        byFile.mapValues { (_, list) -> list.distinct().sortedWith(Diagnostic.ORDER) }.filterValues { it.isNotEmpty() }
}
