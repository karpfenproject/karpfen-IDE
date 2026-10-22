package io.karpfen.ide.core.document.dsl

import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.meta.ClassType
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.text.TextSnapshot

/**
 * Adapter over the dsl-tools converters (`KmetaDSLConverter`, `KmodelDSLConverter` and `KstatesDSLConverter` of the
 * package `io.karpfen.dsl.dsl.textual`), the only way IDE code reaches them: foreign APIs stay behind adapters, and the
 * architecture tests allow the dsl-tools converter packages only in this package. Used for standalone semantics in
 * core-document and for the project analysis in core-workspace.
 *
 * Contract (MUST):
 * - Callers convert only syntax-clean text (`ParseOutcome.isSyntaxClean` of the same snapshot): dsl-tools' own file
 *   parsers only log syntax errors, and their visitors read missing or conjured tokens, so broken text yields logged
 *   warnings and cryptic messages. `ComposedDocumentParser` (standalone semantics) and the project analyzer of
 *   core-workspace follow this rule; the adapter itself does not re-parse to check it.
 * - Never throws for any input: every exception raised by dsl-tools becomes one DSL_TOOLS ERROR [Diagnostic] (code
 *   [io.karpfen.ide.core.document.DiagnosticCodes.DSL_TOOLS_ERROR]) whose range is located by a [DslErrorLocator].
 * - Thread-safe; the converters keep no state between calls. dsl-tools logs through SLF4J, the converters what they
 *   convert at DEBUG and the file parsers syntax errors as warnings, and the adapter leaves that logging to the
 *   application's configuration: for now the CLI discards everything written to the console around its whole
 *   workspace run (`StandardOutputGuard`), while the IDE application keeps INFO and above in its log file and shows
 *   warnings also on its console. Both are interim: the decision that dsl-tools output stays off the primary console wants it off both
 *   consoles, available on demand and with its errors forwarded, which the second increment after phase 1 builds.
 * - The source text is passed unchanged (the converters apply `trimIndent()` themselves; ranges never come from them).
 */
public interface DslConverter {

    /**
     * Converts a `.kmeta` file. [baseTypes] are types of another metamodel that the file may reference but does not
     * define (how `EVENTS.kmeta` reuses the types of the domain metamodel for its event payloads).
     *
     * @param index the syntax index of [source], used to locate error messages.
     */
    public fun kmeta(source: TextSnapshot, index: SyntaxIndex, baseTypes: List<ClassType> = emptyList()): Conversion<Metamodel>

    /** Converts a `.kmodel` file against [metamodel]. */
    public fun kmodel(source: TextSnapshot, index: SyntaxIndex, metamodel: Metamodel): Conversion<Model>

    /** Converts a `.kstates` file. */
    public fun kstates(source: TextSnapshot, index: SyntaxIndex): Conversion<StateMachine>
}

/** The default [DslConverter], locating errors with [locator]. */
public fun DslConverter(locator: DslErrorLocator = DslErrorLocator()): DslConverter = DefaultDslConverter(locator)

/**
 * Result of a dsl-tools conversion: the converted [value], or null together with at least one diagnostic.
 *
 * @property value the converted model, or null if the conversion failed.
 * @property diagnostics DSL_TOOLS diagnostics in the coordinates of the converted snapshot, sorted with
 *   [Diagnostic.ORDER].
 */
public data class Conversion<out T : Any>(public val value: T?, public val diagnostics: List<Diagnostic>) {
    init {
        require(value != null || diagnostics.isNotEmpty()) { "a failed conversion must report at least one diagnostic" }
    }

    /** True if the conversion produced a value. */
    public val isSuccess: Boolean get() = value != null
}
