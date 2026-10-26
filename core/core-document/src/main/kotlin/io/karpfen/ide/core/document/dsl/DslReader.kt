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
package io.karpfen.ide.core.document.dsl

import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.meta.ClassType
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.reader.kmeta.KmetaReader
import io.karpfen.dsl.reader.kmodel.KmodelReader
import io.karpfen.dsl.reader.kstates.KstatesReader
import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextSnapshot

/**
 * Adapter over the tolerant readers of dsl-tools (`KmetaReader`, `KmodelReader` and `KstatesReader` below the package
 * `io.karpfen.dsl.reader`), the only way IDE code reaches them: foreign APIs stay behind adapters, and the architecture
 * tests allow the dsl-tools readers only in this package. Used for the standalone semantics of KMeta and KStates in
 * core-document and for the project analysis in core-workspace.
 *
 * A reader reports every problem where it occurs and keeps reading, so one read yields every finding of the file and,
 * unless the text yields no model at all, a model: the complete one for a text without errors, a partial one otherwise
 * (see [ReadResult]).
 *
 * Contract (MUST):
 * - Callers read only syntax-clean text (`ParseOutcome.isSyntaxClean` of the same snapshot). The IDE's own parser
 *   already reports the syntax errors, which a reader would report a second time, and the partial model of broken
 *   text rests on the guesses of the parser's error recovery. `ComposedDocumentParser` (standalone semantics) and the
 *   project analysis of core-workspace follow this rule; the adapter itself does not re-parse to check it.
 * - Every finding becomes one [Diagnostic] of source [io.karpfen.ide.core.document.DiagnosticSource.DSL_TOOLS] at the
 *   range the reader reports, in the coordinates of the read snapshot; its code is the stable id of the dsl-tools
 *   diagnostic code, such as `kmodel.unknown-property`.
 * - Never throws for any input, except fatal VM errors: should a reader fail nevertheless, the failure becomes a
 *   `reader.failure` error at the start of the text and no model, as the readers report their own failures.
 * - Thread-safe: the readers keep no state between reads.
 * - Logging: the readers report what they find through diagnostics and log nothing for it, nor for a read that
 *   succeeds. A read that fails is a `reader.failure`, and dsl-tools also logs it below the parent logger of
 *   [DslToolsLoggers]: as a warning without a stack trace when the text nests deeper than the reader's parser can
 *   recurse on the stack of the reading thread, which syntax-clean text within the nesting limit of the parsers
 *   (`NestingLimit`) does only on a stack far smaller than the default one, and as an error with its stack trace for a
 *   defect of the reader. The IDE shows these events in its Language Tools Output and keeps them in its logs; the
 *   command-line tool shows them with `--verbose`.
 * - The source text is passed unchanged, so the offsets the readers report are offsets into the snapshot.
 */
public interface DslReader {

    /**
     * Reads a `.kmeta` file. [baseTypes] are types of another metamodel that the file may refer to but does not define
     * (how `EVENTS.kmeta` reuses the types of the domain metamodel for its event payloads); they become part of the
     * metamodel read.
     */
    public fun kmeta(source: TextSnapshot, baseTypes: List<ClassType> = emptyList()): ReadResult<Metamodel>

    /** Reads a `.kmodel` file against [metamodel]. */
    public fun kmodel(source: TextSnapshot, metamodel: Metamodel): ReadResult<Model>

    /** Reads a `.kstates` file. */
    public fun kstates(source: TextSnapshot): ReadResult<StateMachine>
}

/** The default [DslReader] over the readers of dsl-tools. */
public fun DslReader(): DslReader = DefaultDslReader(
    kmetaReader = ::KmetaReader,
    kmodelReader = ::KmodelReader,
    kstatesReader = KstatesReader(),
)

/**
 * What a [DslReader] read from one snapshot.
 *
 * @property model the model read: complete if [hasErrors] is false, partial otherwise, leaving out what the reader
 *   could not build (an object of an unknown type, a property of an unknown type, a rule with the wrong number of
 *   operands); null only if the text yields no model, such as a KMeta text without a type.
 * @property diagnostics DSL_TOOLS diagnostics in the coordinates of the read snapshot, sorted with [Diagnostic.ORDER].
 */
public data class ReadResult<out T : Any>(public val model: T?, public val diagnostics: List<Diagnostic>) {
    init {
        require(model != null || hasErrors) { "a read without a model must report an error" }
    }

    /** True if the text has at least one error, which makes [model] partial or absent. */
    public val hasErrors: Boolean get() = diagnostics.any { it.severity == Severity.ERROR }
}
