package io.karpfen.ide.core.workspace.analysis

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.dsl.DslConverter
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.context.ContextResolver
import io.karpfen.ide.core.workspace.document.SourceLoader
import io.karpfen.ide.core.workspace.files.WorkspaceScanner
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** The production analyzer. */
internal fun analyzer(): ProjectAnalyzer = ProjectAnalyzer(defaultLanguageRegistry(), DslConverter())

/** Sources reading every file from disk like the workspace does for unopened files; counts the loads. */
internal class DiskSources : AnalysisSources {
    /** The number of [load] calls so far. */
    val loads = AtomicInteger()
    private val loader = SourceLoader()

    override suspend fun load(path: Path): SourceText? {
        loads.incrementAndGet()
        return runCatching { loader.load(path) }.getOrNull()?.let {
            SourceText(TextSnapshot(it.text, 0), parse = null, tokens = null, fileDiagnostics = it.diagnostics)
        }
    }
}

/** The contexts of the folder [root] as the workspace resolves them (no descriptors). */
internal fun resolve(root: Path): ContextResolution {
    val tree = WorkspaceScanner(WorkspaceOptions.DEFAULT_IGNORED_DIRECTORIES).scan(root)
    return ContextResolver().resolve(tree, emptyMap())
}

/** Every DSL_TOOLS diagnostic of [analysis], by file. */
internal fun dslToolsDiagnostics(analysis: ProjectAnalysis): Map<Path, List<Diagnostic>> =
    analysis.diagnostics.mapValues { (_, list) -> list.filter { it.source == DiagnosticSource.DSL_TOOLS } }.filterValues { it.isNotEmpty() }

/**
 * Runs [block] with `System.err` captured and returns what was written to it. SLF4J is initialized first, so its
 * one-time "no provider" notice goes to the real `System.err` and never into the capture.
 */
internal inline fun capturingStandardError(block: () -> Unit): String {
    LoggerFactory.getLogger(DiskSources::class.java)
    val original = System.err
    val captured = ByteArrayOutputStream()
    System.setErr(PrintStream(captured, true, Charsets.UTF_8))
    try {
        block()
    } finally {
        System.setErr(original)
    }
    return captured.toString(Charsets.UTF_8)
}
