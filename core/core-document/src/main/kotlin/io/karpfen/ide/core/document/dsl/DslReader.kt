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
import io.karpfen.dsl.reader.ParseResult
import io.karpfen.dsl.reader.kmeta.KmetaReader
import io.karpfen.dsl.reader.kmodel.KmodelReader
import io.karpfen.dsl.reader.kstates.KstatesReader
import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextSnapshot

/**
 * Adapter over the tolerant readers of dsl-tools. These are `KmetaReader`, `KmodelReader` and `KstatesReader` below the
 * package `io.karpfen.dsl.reader`. The adapter is the only way IDE code reaches them, because foreign APIs stay behind
 * adapters, and the architecture tests allow the dsl-tools readers only in this package. core-document uses it for the
 * standalone semantics of KMeta and KStates, and core-workspace uses it for the project analysis.
 *
 * A reader reports every problem where it occurs and keeps reading. One read therefore yields every finding of the
 * file. Unless the text yields no model at all, it also yields a model. The model is complete for a text without
 * errors and partial otherwise, as described at [ReadResult].
 *
 * The adapter keeps these rules:
 * - Callers read only syntax-clean text, as `ParseOutcome.isSyntaxClean` reports it for the same snapshot. The IDE's
 *   own parser already reports the syntax errors, which a reader would report a second time. The partial model of
 *   broken text would also rest on the guesses of the parser's error recovery. `ComposedDocumentParser`, which
 *   computes the standalone semantics, and the project analysis of core-workspace follow this rule. The adapter itself
 *   does not parse again to check it.
 * - Every finding becomes one [Diagnostic] with source [io.karpfen.ide.core.document.DiagnosticSource.DSL_TOOLS] at the
 *   range the reader reports, in the coordinates of the read snapshot. Its code is the stable id of the dsl-tools
 *   diagnostic code, such as `kmodel.unknown-property`.
 * - It never throws for any input, except for fatal VM errors. Should a reader fail nevertheless, the failure becomes a
 *   `reader.failure` error at the start of the text and no model, just as the readers report their own failures.
 * - It is thread-safe, because the readers keep no state between reads.
 * - The source text is passed unchanged, so the offsets the readers report are offsets into the snapshot.
 *
 * The readers report what they find through diagnostics. They log nothing for a finding and nothing for a read that
 * succeeds. A read that fails is a `reader.failure`, and dsl-tools also logs it below the parent logger of
 * [DslToolsLoggers]. It logs a warning without a stack trace when the text nests deeper than the reader's parser can
 * recurse on the stack of the reading thread. Syntax-clean text within the nesting limit of the parsers,
 * `NestingLimit`, only does that on a stack far smaller than the default one. It logs an error with a stack trace for a
 * defect of the reader. The IDE shows these events in its Language Tools Output and keeps them in its logs. The
 * command-line tool shows them with `--verbose`.
 */
public interface DslReader {

    /**
     * Reads a `.kmeta` file. [baseTypes] are types of another metamodel that the file may refer to but does not define.
     * This is how `EVENTS.kmeta` reuses the types of the domain metamodel for its event payloads. The base types become
     * part of the metamodel read.
     */
    public fun kmeta(source: TextSnapshot, baseTypes: List<ClassType> = emptyList()): ReadResult<Metamodel>

    /** Reads a `.kmodel` file against [metamodel]. */
    public fun kmodel(source: TextSnapshot, metamodel: Metamodel): ReadResult<Model>

    /**
     * Reads a `.kstates` file. [features] are the optional features of KStates the text may use. The reader reports
     * every construct of another feature as an error with code `kstates.feature-off` at the keyword that introduces it,
     * and still builds the construct into the state machine. Every feature is on by default, which is how the
     * standalone semantics of a file read it.
     */
    public fun kstates(source: TextSnapshot, features: Set<KstatesFeature> = KstatesFeature.ALL): ReadResult<StateMachine>
}

/** The default [DslReader] over the readers of dsl-tools. */
public fun DslReader(): DslReader = DefaultDslReader(
    kmetaReader = ::KmetaReader,
    kmodelReader = ::KmodelReader,
    kstatesReader = ::KstatesReader,
)

/**
 * What a [DslReader] read from one snapshot.
 *
 * A read made by the readers of dsl-tools also keeps what dsl-tools needs to verify the read together with the other
 * files of its project: the reader's own result with its source map, and the length of the text that was read.
 * [DslVerifier] uses it, and nothing outside this package sees it. A read that a test builds with the public
 * constructor keeps nothing of the kind, and the verifier leaves it out. Two reads are equal if their [model] and their
 * [diagnostics] are equal.
 *
 * @property model the model read. It is complete if [hasErrors] is false and partial otherwise. A partial model leaves
 *   out what the reader could not build, such as an object of an unknown type, a property of an unknown type or a rule
 *   with the wrong number of operands. It is null only if the text yields no model, such as a KMeta text without a
 *   type.
 * @property diagnostics DSL_TOOLS diagnostics in the coordinates of the read snapshot, sorted with [Diagnostic.ORDER].
 */
public class ReadResult<out T : Any> internal constructor(
    public val model: T?,
    public val diagnostics: List<Diagnostic>,
    internal val source: DslSource<T>?,
) {
    /** A read of [model] with [diagnostics] that keeps nothing for the verification. Tests and fakes build it. */
    public constructor(model: T?, diagnostics: List<Diagnostic>) : this(model, diagnostics, null)

    init {
        require(model != null || hasErrors) { "a read without a model must report an error" }
    }

    /** True if the text has at least one error, which makes [model] partial or absent. */
    public val hasErrors: Boolean get() = diagnostics.any { it.severity == Severity.ERROR }

    /** True if [other] is a read with an equal [model] and equal [diagnostics]. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is ReadResult<*> && model == other.model && diagnostics == other.diagnostics)

    /** The hash of [model] and [diagnostics]. */
    override fun hashCode(): Int = 31 * (model?.hashCode() ?: 0) + diagnostics.hashCode()

    /** The [model] and the [diagnostics] in words, for logs and test messages. */
    override fun toString(): String = "ReadResult(model=$model, diagnostics=$diagnostics)"
}

/**
 * What a read of dsl-tools keeps for [DslVerifier].
 *
 * @property parse the result of the dsl-tools reader, with its model, its diagnostics and its source map.
 * @property textLength the length of the text that was read, to which the verifier's ranges are clamped.
 */
internal class DslSource<out T>(val parse: ParseResult<T>, val textLength: Int)
