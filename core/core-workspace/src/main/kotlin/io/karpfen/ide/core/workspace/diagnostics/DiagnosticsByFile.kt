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
 * Collects diagnostics per file and hands them out in the form the workspace's values promise
 * (`ContextResolution.diagnostics`, `ProjectAnalysis.diagnostics`): every list sorted with [Diagnostic.ORDER], without
 * duplicates, files without diagnostics absent. A mutable builder confined to the computation that owns it.
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

    /** The collected diagnostics: sorted, distinct, non-empty lists in the order the files were first added. */
    fun build(): Map<Path, List<Diagnostic>> =
        byFile.mapValues { (_, list) -> list.distinct().sortedWith(Diagnostic.ORDER) }.filterValues { it.isNotEmpty() }
}
