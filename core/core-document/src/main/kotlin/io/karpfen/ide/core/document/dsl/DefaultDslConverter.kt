package io.karpfen.ide.core.document.dsl

import io.karpfen.dsl.dsl.textual.KmetaDSLConverter
import io.karpfen.dsl.dsl.textual.KmodelDSLConverter
import io.karpfen.dsl.dsl.textual.KstatesDSLConverter
import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.meta.ClassType
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.text.TextSnapshot

/**
 * Calls the dsl-tools converters and turns their exceptions into located diagnostics (see [DslConverter] for the
 * contract). Every throwable a conversion raises, except fatal VM errors (a `StackOverflowError` of deeply nested input
 * is not fatal), becomes one DSL_TOOLS ERROR diagnostic: the converters signal invalid input with
 * `IllegalArgumentException`/`IllegalStateException`, but also with `NotImplementedError` and, on broken input, with
 * arbitrary runtime exceptions of their visitors. The message is the exception's message (its class name when it has
 * none); its range comes from [locator].
 *
 * Stateless and thread-safe.
 */
internal class DefaultDslConverter(private val locator: DslErrorLocator) : DslConverter {

    override fun kmeta(source: TextSnapshot, index: SyntaxIndex, baseTypes: List<ClassType>): Conversion<Metamodel> =
        convert(source, index) { KmetaDSLConverter.parseKmetaString(source.text, baseTypes) }

    override fun kmodel(source: TextSnapshot, index: SyntaxIndex, metamodel: Metamodel): Conversion<Model> =
        convert(source, index) { KmodelDSLConverter.parseKmodelString(source.text, metamodel) }

    override fun kstates(source: TextSnapshot, index: SyntaxIndex): Conversion<StateMachine> =
        convert(source, index) { KstatesDSLConverter.parseKstatesString(source.text) }

    /** Runs [conversion]; any throwable but a fatal VM error becomes the failed [Conversion] with one diagnostic. */
    private inline fun <T : Any> convert(source: TextSnapshot, index: SyntaxIndex, conversion: () -> T): Conversion<T> =
        try {
            Conversion(conversion(), emptyList())
        } catch (e: StackOverflowError) {
            Conversion(null, listOf(diagnostic(e, source, index)))
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            Conversion(null, listOf(diagnostic(e, source, index)))
        }

    /** The DSL_TOOLS ERROR diagnostic for [e], located in [source] by its message. */
    private fun diagnostic(e: Throwable, source: TextSnapshot, index: SyntaxIndex): Diagnostic =
        Diagnostic(
            range = locator.locate(e.message, source, index),
            severity = Severity.ERROR,
            message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.name,
            code = DiagnosticCodes.DSL_TOOLS_ERROR,
            source = DiagnosticSource.DSL_TOOLS,
        )
}
