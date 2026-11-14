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

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.context.ContextResolution
import java.nio.file.Path

/**
 * Analyzes every modeling context with dsl-tools. It goes through the core-document [DslReader] adapter only. The
 * metamodel is read with `kmeta`. The events are read with `kmeta` and the types of the metamodel as base types. Each
 * model is read with `kmodel` against the metamodel. Each state machine is read with `kstates` and the optional features
 * of KStates switched on for the run, so that each construct of another feature is an error `kstates.feature-off`. The
 * metamodel and the events are read with the same features, so that a `behaves after` while dynamic attachment is off
 * is an error `kmeta.feature-off`. What
 * the dsl-tools
 * readers find becomes DSL_TOOLS diagnostics on the file they read, at the ranges they report. The models they read,
 * partial ones included, become the [ContextAnalysis]. The IDE adds no validation rules of its own beyond what
 * dsl-tools reports.
 *
 * Syntax comes first. A file is read only when its `AnalyzedFile.parse.isSyntaxClean` is true. Otherwise a reader
 * would report the PARSER errors a second time, as described on [DslReader]. The events file and the models need a
 * metamodel that was read without errors. If the metamodel is missing, has syntax errors or has DSL_TOOLS errors,
 * their reads are skipped. A skipped read reports nothing of its own, because the metamodel reports its own errors. So
 * while the user types, the problems show PARSER errors only, and the DSL_TOOLS errors return once the syntax is clean.
 *
 * Every context file and every loose file from `ContextResolution.looseFiles` becomes an [AnalyzedFile]. It is
 * tokenized unless [SourceText.tokens] are given, and parsed with `SemanticMode.SYNTAX_ONLY` unless [SourceText.parse]
 * is given. It keeps the [SourceText.fileDiagnostics] of its source. A loose state machine that is free of syntax
 * errors is read on its own, with the same features as those of the contexts. Its findings become DSL_TOOLS
 * diagnostics on it like those of a context file. A loose model or metamodel is not read, because only a context
 * tells which metamodel it belongs to or builds on. A file that cannot be read, as described on
 * [AnalysisSources.load], becomes an ERROR `unreadable` in
 * [ProjectAnalysis.diagnostics], so nothing is skipped silently. The result follows the conventions on
 * [ProjectAnalysis] for paths, snapshots and parse modes.
 *
 * The default analyzer remembers its reads. A file whose text and metamodel, or for a state machine whose text and set
 * of features, are the same as at its read in an earlier run is not read again. So an unchanged file that dsl-tools cannot read is reported on every run, but it is read, and
 * logged by dsl-tools, only once. Whatever dsl-tools logs while it reads a file names that file under
 * `DslToolsLoggers.FILE_KEY`.
 *
 * The analysis can be cancelled and checks for cancellation between files. Apart from that it never throws.
 */
public interface ProjectAnalyzer {
    /**
     * Analyzes the contexts of [resolution] and reads the files through [sources]. The state machines may use the
     * optional features in [languageFeatures], every one by default. The result carries [generation] and
     * [languageFeatures], and the diagnostics of the resolution are merged into its [ProjectAnalysis.diagnostics].
     */
    public suspend fun analyze(
        resolution: ContextResolution,
        sources: AnalysisSources,
        generation: Long,
        languageFeatures: Set<KstatesFeature> = KstatesFeature.ALL,
    ): ProjectAnalysis

    /**
     * The DSL_TOOLS diagnostics that the file at [path] would get in [analysis] if its text were [text], in the
     * coordinates of [text]. The text is read by the rules of [analyze], in the role its file has in its context and
     * against the reads of the other files of that context in [analysis], or on its own if the file is a loose state
     * machine. State machines and metamodels are read with the `ProjectAnalysis.languageFeatures` of [analysis]. A text with syntax
     * errors, and a file that [analysis] would not read, give an empty list. No other file is read again, and
     * [analysis] stays as it is. It suspends only to check for cancellation and never throws otherwise.
     */
    public suspend fun readInPlace(analysis: ProjectAnalysis, path: Path, text: TextSnapshot): List<Diagnostic>
}

/** The default [ProjectAnalyzer]. It parses the files for which [AnalysisSources] supplied no current parse. */
public fun ProjectAnalyzer(languages: LanguageRegistry, reader: DslReader): ProjectAnalyzer =
    DefaultProjectAnalyzer(languages, reader)

/**
 * Supplies the text to analyze. For an open document that is the editor text: the session's current snapshot with its
 * lineage, its tokens from `DocumentSession.tokensFor`, and its parse when the parse has that version. For any other
 * file it is the disk text, loaded through the workspace's `SourceLoader` into a fresh `TextSnapshot(text, 0)` with a
 * lineage of its own for each load. The workspace implements it, and tests supply texts from fixtures the same way.
 */
public fun interface AnalysisSources {
    /**
     * The source of [path], or null if the file does not exist or no longer exists. The analyzer skips such a file,
     * and a rescan follows.
     *
     * @throws java.io.IOException if the file exists but cannot be read. The analyzer reports an ERROR `unreadable` on
     *   it.
     */
    public suspend fun load(path: Path): SourceText?
}

/**
 * Text to analyze. [parse] and [tokens] are results for exactly [snapshot] when they are at hand, which is the case
 * for open documents. Then the analyzer does not compute them again. Otherwise they are null. [parse] is always a
 * syntax-only parse.
 *
 * @property fileDiagnostics the findings of loading the file, from `LoadedSource.diagnostics`. It is empty for open
 *   documents.
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
