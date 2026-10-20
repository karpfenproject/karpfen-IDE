package io.karpfen.ide.core.document.dsl

import instance.Model
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.text.TextSnapshot
import meta.ClassType
import meta.Metamodel
import states.StateMachine

/**
 * Adapter over the dsl-tools converters (`dsl.textual.KmetaDSLConverter`, `KmodelDSLConverter`,
 * `KstatesDSLConverter`), the only way IDE code reaches them (spec §1a, Adapters). Used for standalone semantics in
 * core-document and for project analysis in core-workspace.
 *
 * Contract (MUST):
 * - Never throws for any input: every exception raised by dsl-tools becomes one DSL_TOOLS ERROR [Diagnostic] (code
 *   [io.karpfen.ide.core.document.DiagnosticCodes.DSL_TOOLS_ERROR]) whose range is located by a [DslErrorLocator].
 * - Thread-safe; the converters keep no state between calls. They print to `System.out` while parsing; the adapter
 *   does not redirect it (the CLI does, spec §9).
 * - The source text is passed unchanged (the converters apply `trimIndent()` themselves; ranges never come from them).
 */
public interface DslConverter {

    /**
     * Converts a `.kmeta` file. [baseTypes] are types of another metamodel that the file may reference but does not
     * define (how `EVENTS.kmeta` reuses domain types, spec §4).
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
