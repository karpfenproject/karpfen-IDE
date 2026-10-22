package io.karpfen.ide.core.workspace.analysis

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.context.ContextResolution
import java.nio.file.Path

/**
 * Analyzes every modeling context with dsl-tools, through the core-document [DslReader] adapter only: metamodel via
 * `kmeta`; events via `kmeta` with the metamodel's types as base types; each model via `kmodel` against the metamodel;
 * each state machine via `kstates`. What the dsl-tools readers find becomes DSL_TOOLS diagnostics on the file they read,
 * at the ranges they report, and the models they read, partial ones included, become the [ContextAnalysis]. No IDE-side
 * validation rules beyond what dsl-tools reports.
 *
 * Syntax first (MUST): a file is read only when its `AnalyzedFile.parse.isSyntaxClean` (a reader would report the
 * PARSER errors a second time, see [DslReader]). A read that needs a metamodel which was not read without errors
 * (missing, not syntax-clean, or with DSL_TOOLS errors) is skipped: the events file and the models. A skipped read
 * reports nothing of its own — the metamodel reports its own errors — so while the user types, Problems shows PARSER
 * errors only, and DSL_TOOLS errors return once the syntax is clean.
 *
 * Every context file and every loose file (`ContextResolution.looseFiles`) becomes an [AnalyzedFile]: tokenized unless
 * [SourceText.tokens] are given, parsed with `SemanticMode.SYNTAX_ONLY` unless [SourceText.parse] is given, with the
 * source's [SourceText.fileDiagnostics]. Loose files are never converted. A file that cannot be read (see
 * [AnalysisSources.load]) is an ERROR `unreadable` in [ProjectAnalysis.diagnostics], so nothing is skipped silently. The
 * result follows the conventions documented on [ProjectAnalysis] (paths, snapshots, parse modes).
 *
 * Cancellable (checks for cancellation between files) and never throws otherwise.
 */
public fun interface ProjectAnalyzer {
    /**
     * Analyzes the contexts of [resolution], reading files through [sources]; the result carries [generation] and the
     * resolution's diagnostics merged into [ProjectAnalysis.diagnostics].
     */
    public suspend fun analyze(resolution: ContextResolution, sources: AnalysisSources, generation: Long): ProjectAnalysis
}

/** The default [ProjectAnalyzer]; parses files that [AnalysisSources] did not supply a current parse for. */
public fun ProjectAnalyzer(languages: LanguageRegistry, reader: DslReader): ProjectAnalyzer =
    DefaultProjectAnalyzer(languages, reader)

/**
 * Supplies the text to analyze: the editor text of open documents (the session's current snapshot, with its lineage,
 * its tokens from `DocumentSession.tokensFor` and its parse when the parse has that version), the disk text otherwise,
 * loaded through the workspace's `SourceLoader` into a fresh `TextSnapshot(text, 0)` (a lineage of its own per load).
 * Implemented by the workspace; tests supply texts from fixtures the same way.
 */
public fun interface AnalysisSources {
    /**
     * The source of [path], or null if it does not exist (any more: the analyzer skips it, a rescan follows).
     * @throws java.io.IOException if it exists but cannot be read; the analyzer reports an ERROR `unreadable` on it.
     */
    public suspend fun load(path: Path): SourceText?
}

/**
 * Text to analyze. [parse] (syntax-only) and [tokens] are results for exactly [snapshot] if they are at hand (open
 * documents), so the analyzer does not compute them again; null otherwise.
 *
 * @property fileDiagnostics the findings of loading the file (`LoadedSource.diagnostics`); empty for open documents.
 */
public data class SourceText(
    public val snapshot: TextSnapshot,
    public val parse: ParseOutcome?,
    public val tokens: TokenizeResult? = null,
    public val fileDiagnostics: List<Diagnostic> = emptyList(),
) {
    init {
        require(parse == null || parse.version == snapshot.version) {
            "parse version ${parse?.version} does not match snapshot version ${snapshot.version}"
        }
        require(tokens == null || tokens.version == snapshot.version) {
            "tokens version ${tokens?.version} does not match snapshot version ${snapshot.version}"
        }
    }
}
