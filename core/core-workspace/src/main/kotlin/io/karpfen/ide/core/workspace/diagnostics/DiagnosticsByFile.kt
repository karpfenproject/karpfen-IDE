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
