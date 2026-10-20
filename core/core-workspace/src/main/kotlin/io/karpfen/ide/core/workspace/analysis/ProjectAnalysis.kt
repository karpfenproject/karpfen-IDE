package io.karpfen.ide.core.workspace.analysis

import instance.Model
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.context.ModelingContext
import io.karpfen.ide.core.workspace.files.FileKind
import meta.Metamodel
import states.StateMachine
import java.nio.file.Path

/**
 * Result of analyzing every modeling context of a workspace with dsl-tools (spec §4). Immutable snapshot; the
 * dsl-tools model objects inside are never mutated (D1).
 *
 * @property generation increases with every analysis run of a workspace (0 for [EMPTY]).
 * @property contexts one entry per resolved context, in resolution order.
 * @property diagnostics project-level diagnostics per file: DSL_TOOLS errors from the conversions plus the
 *   descriptor/context diagnostics of the resolution, each list sorted with [Diagnostic.ORDER] and without duplicates.
 *   A file's own diagnostics are not repeated here (see [AnalyzedFile.ownDiagnostics]); these lists are what the
 *   workspace pushes into open sessions as external source `ExternalDiagnosticSources.PROJECT`.
 * @property looseFiles the karpfen sources of the tree that belong to no context (`ContextResolution.looseFiles`),
 *   analyzed syntax-only (decision 13 of the work-package plan: they still get syntax diagnostics).
 */
public data class ProjectAnalysis(
    public val generation: Long,
    public val contexts: List<ContextAnalysis>,
    public val diagnostics: Map<Path, List<Diagnostic>>,
    public val looseFiles: Map<Path, AnalyzedFile> = emptyMap(),
) {
    /** The first context containing [path], or null. */
    public fun contextOf(path: Path): ContextAnalysis? = contexts.firstOrNull { path in it.context }

    /** Project-level diagnostics of [path] (empty if none). */
    public fun diagnosticsFor(path: Path): List<Diagnostic> = diagnostics[path].orEmpty()

    /** The analyzed file at [path] in any context, else among the [looseFiles], or null. */
    public fun file(path: Path): AnalyzedFile? = contexts.firstNotNullOfOrNull { it.files[path] } ?: looseFiles[path]

    /** Every analyzed file (context files first, then loose files), by path. */
    public val files: Map<Path, AnalyzedFile>
        get() = buildMap {
            putAll(looseFiles)
            contexts.asReversed().forEach { putAll(it.files) }
        }

    public companion object {
        /** Nothing analyzed yet. */
        public val EMPTY: ProjectAnalysis = ProjectAnalysis(0, emptyList(), emptyMap())
    }
}

/**
 * Analysis of one [ModelingContext].
 *
 * @property files every context file that could be read, with the snapshot and parse outcome the analysis used (the
 *   editor text for open documents, the disk text otherwise).
 * @property metamodel the converted domain metamodel, or null if missing or invalid.
 * @property events the converted event metamodel (converted with the domain types as base types), or null.
 * @property models converted models by path (only those that converted).
 * @property stateMachines converted state machines by path (only those that converted).
 */
public data class ContextAnalysis(
    public val context: ModelingContext,
    public val files: Map<Path, AnalyzedFile>,
    public val metamodel: Metamodel?,
    public val events: Metamodel?,
    public val models: Map<Path, Model>,
    public val stateMachines: Map<Path, StateMachine>,
)

/**
 * A karpfen file as seen by one analysis run: its role, the text version used, and its tokenizer and parse results.
 *
 * @property parse the parse outcome of [snapshot]: the open document's own one (supplied through `SourceText`), or a
 *   [io.karpfen.ide.core.document.parse.SemanticMode.SYNTAX_ONLY] parse by the analyzer.
 * @property lexicalDiagnostics the tokenizer's diagnostics of [snapshot] (LEXER errors, `eval-double-slash`
 *   warnings), sorted.
 * @property fileDiagnostics the findings of loading the file (`LoadedSource.diagnostics`); empty for open documents,
 *   whose sessions carry them as external source `ExternalDiagnosticSources.FILE`.
 */
public data class AnalyzedFile(
    public val path: Path,
    public val kind: FileKind,
    public val snapshot: TextSnapshot,
    public val parse: ParseOutcome,
    public val lexicalDiagnostics: List<Diagnostic> = emptyList(),
    public val fileDiagnostics: List<Diagnostic> = emptyList(),
) {
    init {
        require(parse.version == snapshot.version) {
            "parse version ${parse.version} does not match snapshot version ${snapshot.version}"
        }
    }

    /**
     * The file's own diagnostics, merged by the rule of `DocumentSession.diagnostics`: [lexicalDiagnostics], the PARSER
     * diagnostics of [parse] (its LEXER ones repeat the tokenizer's), its standalone DSL_TOOLS diagnostics and
     * [fileDiagnostics]; sorted with [Diagnostic.ORDER], without duplicates. Project diagnostics are not included.
     */
    public val ownDiagnostics: List<Diagnostic>
        get() = (
            lexicalDiagnostics +
                parse.syntaxDiagnostics.filter { it.source == DiagnosticSource.PARSER } +
                parse.standaloneDiagnostics +
                fileDiagnostics
            ).distinct().sortedWith(Diagnostic.ORDER)
}
