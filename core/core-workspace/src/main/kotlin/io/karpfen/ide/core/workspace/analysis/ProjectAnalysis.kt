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

import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.dsl.ReadResult
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.session.mergeDiagnostics
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.context.ModelingContext
import io.karpfen.ide.core.workspace.files.FileKind
import java.nio.file.Path

/**
 * Result of analyzing every modeling context of a workspace with dsl-tools. It is an immutable snapshot. The dsl-tools
 * model objects inside are never changed. They are derived from the text files, which stay the single source of
 * truth.
 *
 * The analyzer keeps the following conventions. Consumers such as core-lang, and tests that build analyses by hand,
 * rely on them.
 * - Every path is absolute and normalized with `Path.toAbsolutePath().normalize()`, like a `DocumentId`.
 * - An open document is analyzed with the snapshot of its session. So its `TextSnapshot.lineage` and its version are
 *   those of the editor, and its ranges map into the live text. A file read from disk gets a fresh snapshot at version
 *   0 with a lineage of its own for each load.
 * - Every [AnalyzedFile.parse] is a `SemanticMode.SYNTAX_ONLY` parse. Open documents are parsed that way too, as
 *   described on `DocumentManager`. dsl-tools reads files only through the analyzer's `DslReader`, and its diagnostics
 *   are in [diagnostics].
 *
 * @property generation grows with every analysis run of a workspace. It is 0 for [EMPTY].
 * @property contexts one entry per resolved context, in the order of the resolution.
 * @property diagnostics the project diagnostics of each file. They are the DSL_TOOLS findings of the reads, the
 *   descriptor and context diagnostics of the resolution, including directories that cannot be read, and the
 *   `unreadable` errors of files that could not be loaded. Each list is sorted with [Diagnostic.ORDER] and has no
 *   duplicates. The own diagnostics of a file, described on [AnalyzedFile.ownDiagnostics], are not repeated here. The
 *   syntax errors and loading findings of a descriptor also belong to its own text. The workspace pushes these lists
 *   into the open sessions as the external source `ExternalDiagnosticSources.PROJECT`.
 * @property looseFiles the karpfen sources of the tree that belong to no context, as listed in
 *   `ContextResolution.looseFiles`. Their syntax errors appear in the problems. dsl-tools reads a loose state machine
 *   on its own, and its findings are part of [diagnostics]. A loose model or metamodel is analyzed for syntax only.
 * @property languageFeatures the optional features of KStates that were switched on for this analysis. dsl-tools
 *   reported every construct of another feature in a state machine it read as an error `kstates.feature-off` in
 *   [diagnostics]. A deployment switches on the runtime features of these, and completion offers their constructs.
 */
public data class ProjectAnalysis(
    public val generation: Long,
    public val contexts: List<ContextAnalysis>,
    public val diagnostics: Map<Path, List<Diagnostic>>,
    public val looseFiles: Map<Path, AnalyzedFile> = emptyMap(),
    public val languageFeatures: Set<KstatesFeature> = emptySet(),
) {
    /** The first context containing [path], or null. */
    public fun contextOf(path: Path): ContextAnalysis? = contexts.firstOrNull { path in it.context }

    /** The project diagnostics of [path], or an empty list if there are none. */
    public fun diagnosticsFor(path: Path): List<Diagnostic> = diagnostics[path].orEmpty()

    /** The analyzed file at [path] in any context, else among the [looseFiles], or null. */
    public fun file(path: Path): AnalyzedFile? = contexts.firstNotNullOfOrNull { it.files[path] } ?: looseFiles[path]

    /**
     * Every analyzed file by path. The context files come first, with the contexts in order. A file that several
     * contexts name appears under its first context, the same one that [file] answers with. The loose files follow.
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
 * Every model here is what a dsl-tools reader read from its file. It is complete if the file has no DSL_TOOLS error in
 * [ProjectAnalysis.diagnostics]. Otherwise it is partial and leaves out what the reader could not build.
 *
 * @property files every context file that could be read, with the snapshot and parse outcome the analysis used. That
 *   is the editor text for open documents and the disk text otherwise.
 * @property metamodel the domain metamodel, possibly partial. It is null if the file is missing, has syntax errors or
 *   yields no model, as a text without types does.
 * @property events the event metamodel, or null. It is read only when the domain metamodel was read without errors.
 *   It is read with the domain types as base types, and dsl-tools appends those base types to the result. So
 *   `events.types` also contains every domain type. The real event types are the TYPE declarations of the events file
 *   itself, found in the syntax index of its [AnalyzedFile.parse]. Consumers that list events filter by them, as
 *   core-lang does for `TypeOrigin.EVENTS`.
 * @property models the models by path, possibly partial. It is empty unless the domain metamodel was read without
 *   errors.
 * @property stateMachines the state machines by path, possibly partial.
 * @property reads the reads the models above come from, with their diagnostics. A validation of the project hands them
 *   to the verifier of dsl-tools. An analysis built by hand may leave them out.
 */
public data class ContextAnalysis(
    public val context: ModelingContext,
    public val files: Map<Path, AnalyzedFile>,
    public val metamodel: Metamodel?,
    public val events: Metamodel?,
    public val models: Map<Path, Model>,
    public val stateMachines: Map<Path, StateMachine>,
    public val reads: ContextReads = ContextReads.NONE,
)

/**
 * The reads of the files of one context, as the `DslReader` of the analysis returned them. Each read holds the same
 * model as the matching property of [ContextAnalysis] and the DSL_TOOLS diagnostics of its file. A file that was not
 * read, by the rules on `ProjectAnalyzer`, has no read here.
 *
 * @property metamodel the read of the domain metamodel, or null.
 * @property events the read of the event metamodel, or null.
 * @property models the reads of the models by path.
 * @property stateMachines the reads of the state machines by path.
 */
public data class ContextReads(
    public val metamodel: ReadResult<Metamodel>?,
    public val events: ReadResult<Metamodel>?,
    public val models: Map<Path, ReadResult<Model>>,
    public val stateMachines: Map<Path, ReadResult<StateMachine>>,
) {
    public companion object {
        /** No reads at all. */
        public val NONE: ContextReads = ContextReads(null, null, emptyMap(), emptyMap())
    }
}

/**
 * A karpfen file as one analysis run saw it. It holds the file's kind, the text version used, and the results of the
 * tokenizer and the parser.
 *
 * @property snapshot the analyzed text. Following the conventions on [ProjectAnalysis], it is the session's snapshot
 *   for an open document and a fresh lineage at version 0 for a file read from disk.
 * @property parse the `SemanticMode.SYNTAX_ONLY` parse outcome of [snapshot]. For an open document it is the
 *   document's own outcome, supplied through `SourceText`. Otherwise the analyzer made it.
 * @property lexicalDiagnostics the tokenizer's diagnostics of [snapshot], sorted. These are LEXER errors.
 * @property fileDiagnostics the findings of loading the file, from `LoadedSource.diagnostics`. It is empty for open
 *   documents, whose sessions carry these findings as the external source `ExternalDiagnosticSources.FILE`.
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
     * The file's own diagnostics. They are merged by the same rule as `DocumentSession.diagnostics`, which is
     * `mergeDiagnostics` of core-document. The merge takes [lexicalDiagnostics], the PARSER diagnostics of [parse],
     * the standalone DSL_TOOLS diagnostics of [parse] and [fileDiagnostics]. The LEXER diagnostics of [parse] are left
     * out because they repeat those of the tokenizer. The analyzer's syntax-only parses have no DSL_TOOLS diagnostics.
     * The result is sorted with [Diagnostic.ORDER] and has no duplicates. Project diagnostics are not included.
     */
    public val ownDiagnostics: List<Diagnostic>
        get() = mergeDiagnostics(lexicalDiagnostics, parse, listOf(fileDiagnostics))
}
