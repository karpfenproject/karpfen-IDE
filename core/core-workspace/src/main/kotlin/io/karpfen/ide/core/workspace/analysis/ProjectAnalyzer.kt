package io.karpfen.ide.core.workspace.analysis

import io.karpfen.ide.core.document.dsl.DslConverter
import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.context.ContextResolution
import java.nio.file.Path

/**
 * Analyzes every modeling context with dsl-tools (spec §4), through the core-document [DslConverter] adapter only:
 * metamodel via `kmeta`; events via `kmeta` with the metamodel's types as base types; each model via `kmodel`
 * against the metamodel; each state machine via `kstates`. Exceptions become DSL_TOOLS diagnostics on the right file
 * (ranges located through each file's syntax index). No IDE-side validation rules beyond what dsl-tools reports.
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
public fun ProjectAnalyzer(languages: LanguageRegistry, converter: DslConverter): ProjectAnalyzer =
    DefaultProjectAnalyzer(languages, converter)

/**
 * Supplies the text to analyze: the editor text of open documents, the disk text otherwise (spec §4). Implemented by
 * the workspace; tests supply texts from fixtures.
 */
public fun interface AnalysisSources {
    /** The source of [path], or null if it cannot be read (the analyzer then reports nothing for it). */
    public suspend fun load(path: Path): SourceText?
}

/**
 * Text to analyze. [parse] is a parse outcome for exactly [snapshot] if one is at hand (open documents), so the
 * analyzer does not parse again; null otherwise.
 */
public data class SourceText(public val snapshot: TextSnapshot, public val parse: ParseOutcome?) {
    init {
        require(parse == null || parse.version == snapshot.version) {
            "parse version ${parse?.version} does not match snapshot version ${snapshot.version}"
        }
    }
}
