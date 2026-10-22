package io.karpfen.ide.core.document.dsl

import instance.Model
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.fixtures.SyntheticKstates
import io.karpfen.ide.core.document.parse.DslModel
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import meta.ClassType
import meta.Metamodel
import states.StateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Covers [KmetaStandaloneSemantics] and [KstatesStandaloneSemantics]: wrapping the converted models and passing on the
 * diagnostics of failed conversions.
 */
class StandaloneSemanticsTest {

    private val snapshot = TextSnapshot("text", 0)
    private val index = SyntaxIndex.EMPTY
    private val failure = Diagnostic(TextRange(0, 4), Severity.ERROR, "bad", DiagnosticCodes.DSL_TOOLS_ERROR, DiagnosticSource.DSL_TOOLS)

    /** A converter answering with fixed conversions and recording what it was asked. */
    private class FakeConverter(
        private val metamodel: Conversion<Metamodel>? = null,
        private val machine: Conversion<StateMachine>? = null,
    ) : DslConverter {
        val calls = ArrayList<String>()
        override fun kmeta(source: TextSnapshot, index: SyntaxIndex, baseTypes: List<ClassType>): Conversion<Metamodel> {
            calls += "kmeta(${source.text}, baseTypes=${baseTypes.size})"
            return metamodel!!
        }
        override fun kmodel(source: TextSnapshot, index: SyntaxIndex, metamodel: Metamodel): Conversion<Model> = error("not used")
        override fun kstates(source: TextSnapshot, index: SyntaxIndex): Conversion<StateMachine> {
            calls += "kstates(${source.text})"
            return machine!!
        }
    }

    @Test
    fun `KMeta converts without base types and wraps the metamodel`() {
        val metamodel = Metamodel(mutableListOf(), ClassType("Root", "", mutableListOf(), mutableListOf()))
        val converter = FakeConverter(metamodel = Conversion(metamodel, emptyList()))

        val result = KmetaStandaloneSemantics(converter).convert(snapshot, index)

        assertSame(metamodel, assertIs<DslModel.Kmeta>(result.model).metamodel)
        assertEquals(emptyList(), result.diagnostics)
        assertEquals(listOf("kmeta(text, baseTypes=0)"), converter.calls)
    }

    @Test
    fun `KStates wraps the state machine of a real conversion`() {
        val text = SyntheticKstates(12)

        val result = KstatesStandaloneSemantics(DslConverter()).convert(TextSnapshot(text, 0), index)

        assertEquals(SyntheticKstates.ATTACHED_TYPE, assertIs<DslModel.Kstates>(result.model).stateMachine.attachedToClass)
    }

    @Test
    fun `failed conversions pass their diagnostics on without a model`() {
        val converter = FakeConverter(metamodel = Conversion(null, listOf(failure)), machine = Conversion(null, listOf(failure)))

        val kmeta = KmetaStandaloneSemantics(converter).convert(snapshot, index)
        val kstates = KstatesStandaloneSemantics(converter).convert(snapshot, index)

        assertNull(kmeta.model)
        assertNull(kstates.model)
        assertEquals(listOf(failure), kmeta.diagnostics)
        assertEquals(listOf(failure), kstates.diagnostics)
    }
}
