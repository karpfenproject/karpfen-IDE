package io.karpfen.ide.core.workspace.analysis

import instance.Model
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.session.mergeDiagnostics
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.context.ModelingContext
import io.karpfen.ide.core.workspace.files.FileKind
import meta.Metamodel
import states.StateMachine
import java.nio.file.Path

/**
 * Result of analyzing every modeling context of a workspace with dsl-tools. Immutable snapshot; the dsl-tools model
 * objects inside are never mutated: they are derived from the text files, which stay the single source of truth.
 *
 * Conventions of the analyzer (MUST; consumers such as core-lang and tests building analyses by hand rely on them):
 * - every path is absolute and normalized (`Path.toAbsolutePath().normalize()`), like `DocumentId`;
 * - an open document is analyzed with its session's snapshot (so its `TextSnapshot.lineage` and version are the
 *   editor's, and ranges map into the live text); a file read from disk gets a fresh snapshot at version 0 with a
 *   lineage of its own (one per load);
 * - every [AnalyzedFile.parse] is a `SemanticMode.SYNTAX_ONLY` parse (open documents are parsed that way too, see
 *   `DocumentManager`); conversions happen only through the analyzer's `DslConverter`, their diagnostics are in
 *   [diagnostics].
 *
 * @property generation increases with every analysis run of a workspace (0 for [EMPTY]).
 * @property contexts one entry per resolved context, in resolution order.
 * @property diagnostics project-level diagnostics per file: DSL_TOOLS errors from the conversions, the
 *   descriptor/context diagnostics of the resolution (directories that cannot be read included) and the `unreadable`
 *   errors of files that could not be loaded, each list sorted with [Diagnostic.ORDER] and without duplicates.
 *   A file's own diagnostics are not repeated here (see [AnalyzedFile.ownDiagnostics]); these lists are what the
 *   workspace pushes into open sessions as external source `ExternalDiagnosticSources.PROJECT`.
 * @property looseFiles the karpfen sources of the tree that belong to no context (`ContextResolution.looseFiles`),
 *   analyzed syntax-only: they are never converted, but their syntax errors still appear in the problems.
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

    /**
     * Every analyzed file by path, iterating context files first (contexts in order, a file named by several contexts
     * under its first one, as [file] answers) and then loose files.
     */
    public val files: Map<Path, AnalyzedFile>
        get() = LinkedHashMap<Path, AnalyzedFile>().apply {
            contexts.forEach { context -> context.files.forEach { (path, file) -> putIfAbsent(path, file) } }
            looseFiles.forEach { (path, file) -> putIfAbsent(path, file) }
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
 * @property metamodel the converted domain metamodel, or null if missing, not syntax-clean or rejected.
 * @property events the converted event metamodel, or null. Converted with the domain types as base types, and
 *   dsl-tools appends those base types to the result (`Metamodel(classTypes + baseTypes, …)`), so `events.types` also
 *   contains every domain type. The event types proper are the TYPE declarations of the events file itself (its
 *   [AnalyzedFile.parse] syntax index); consumers listing events (core-lang's `TypeOrigin.EVENTS`) filter by them.
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
 * @property snapshot the analyzed text (see the conventions on [ProjectAnalysis]: the session's snapshot for open
 *   documents, a fresh lineage at version 0 for files read from disk).
 * @property parse the `SemanticMode.SYNTAX_ONLY` parse outcome of [snapshot]: the open document's own one (supplied
 *   through `SourceText`), or one made by the analyzer.
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
     * The file's own diagnostics, merged by the one rule of `DocumentSession.diagnostics` (`mergeDiagnostics` of
     * core-document): [lexicalDiagnostics], the PARSER diagnostics of [parse] (its LEXER ones repeat the tokenizer's),
     * its standalone DSL_TOOLS diagnostics (none for the analyzer's syntax-only parses) and [fileDiagnostics]; sorted
     * with [Diagnostic.ORDER], without duplicates. Project diagnostics are not included.
     */
    public val ownDiagnostics: List<Diagnostic>
        get() = mergeDiagnostics(lexicalDiagnostics, parse, listOf(fileDiagnostics))
}
