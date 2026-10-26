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
import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.fixtures.SyntheticKstates
import io.karpfen.ide.core.document.parse.DslModel
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Covers [KmetaStandaloneSemantics] and [KstatesStandaloneSemantics]: wrapping the models read, partial ones included,
 * and passing on the diagnostics of the reads.
 */
class StandaloneSemanticsTest {

    private val snapshot = TextSnapshot("text", 0)
    private val failure = Diagnostic(TextRange(0, 4), Severity.ERROR, "bad", "kmeta.no-types", DiagnosticSource.DSL_TOOLS)

    /** A reader answering with fixed results and recording what it was asked. */
    private class FakeReader(
        private val metamodel: ReadResult<Metamodel>? = null,
        private val machine: ReadResult<StateMachine>? = null,
    ) : DslReader {
        val calls = ArrayList<String>()
        override fun kmeta(source: TextSnapshot, baseTypes: List<ClassType>): ReadResult<Metamodel> {
            calls += "kmeta(${source.text}, baseTypes=${baseTypes.size})"
            return metamodel!!
        }
        override fun kmodel(source: TextSnapshot, metamodel: Metamodel): ReadResult<Model> = error("not used")
        override fun kstates(source: TextSnapshot): ReadResult<StateMachine> {
            calls += "kstates(${source.text})"
            return machine!!
        }
    }

    @Test
    fun `KMeta reads without base types and wraps the metamodel`() {
        val metamodel = Metamodel(mutableListOf(), ClassType("Root", "", mutableListOf(), mutableListOf()))
        val reader = FakeReader(metamodel = ReadResult(metamodel, emptyList()))

        val result = KmetaStandaloneSemantics(reader).convert(snapshot)

        assertSame(metamodel, assertIs<DslModel.Kmeta>(result.model).metamodel)
        assertEquals(emptyList(), result.diagnostics)
        assertEquals(listOf("kmeta(text, baseTypes=0)"), reader.calls)
    }

    @Test
    fun `KStates wraps the state machine of a real read`() {
        val text = SyntheticKstates(12)

        val result = KstatesStandaloneSemantics(DslReader()).convert(TextSnapshot(text, 0))

        assertEquals(SyntheticKstates.ATTACHED_TYPE, assertIs<DslModel.Kstates>(result.model).stateMachine.attachedToClass)
        assertEquals(emptyList(), result.diagnostics)
    }

    @Test
    fun `a partial model is wrapped next to the errors that make it partial`() {
        val text = "STATEMACHINE ATTACHED TO \"T\" {\n STATES {\n  STATE \"a\" { }\n }\n TRANSITIONS { }\n}"

        val result = KstatesStandaloneSemantics(DslReader()).convert(TextSnapshot(text, 0))

        assertEquals(listOf("a"), assertIs<DslModel.Kstates>(result.model).stateMachine.states.map { it.name })
        assertEquals(listOf("kstates.initial-state-count"), result.diagnostics.map { it.code })
    }

    @Test
    fun `reads without a model pass their diagnostics on`() {
        val reader = FakeReader(metamodel = ReadResult(null, listOf(failure)), machine = ReadResult(null, listOf(failure)))

        val kmeta = KmetaStandaloneSemantics(reader).convert(snapshot)
        val kstates = KstatesStandaloneSemantics(reader).convert(snapshot)

        assertNull(kmeta.model)
        assertNull(kstates.model)
        assertEquals(listOf(failure), kmeta.diagnostics)
        assertEquals(listOf(failure), kstates.diagnostics)
    }
}
