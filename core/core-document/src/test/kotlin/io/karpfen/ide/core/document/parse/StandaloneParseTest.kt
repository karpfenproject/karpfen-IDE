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
package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.tree.KmetaSyntaxAnalyzer
import io.karpfen.ide.core.document.antlr.tree.KmodelSyntaxAnalyzer
import io.karpfen.ide.core.document.antlr.tree.KstatesSyntaxAnalyzer
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.dsl.KmetaStandaloneSemantics
import io.karpfen.ide.core.document.dsl.KstatesStandaloneSemantics
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parsers as `registry/KarpfenLanguageSupports.kt` wires them (syntax analyzer + dsl-tools standalone semantics),
 * on the examples: zero PARSER diagnostics everywhere; with [SemanticMode.STANDALONE] every example `.kmeta` except
 * `EVENTS.kmeta` (whose payloads reference domain types) and every `.kstates` reads without findings, and a semantic
 * error is reported where the reader finds it, next to the partial model.
 */
class StandaloneParseTest {

    private val reader = DslReader()
    private val parsers: Map<KarpfenLanguage, DocumentParser> = mapOf(
        KarpfenLanguage.KMETA to ComposedDocumentParser(KarpfenLanguage.KMETA, KmetaSyntaxAnalyzer(), KmetaStandaloneSemantics(reader)),
        KarpfenLanguage.KMODEL to ComposedDocumentParser(KarpfenLanguage.KMODEL, KmodelSyntaxAnalyzer(), semantics = null),
        KarpfenLanguage.KSTATES to ComposedDocumentParser(KarpfenLanguage.KSTATES, KstatesSyntaxAnalyzer(), KstatesStandaloneSemantics(reader)),
    )

    @Test
    fun `every example parses syntax-only without diagnostics or a dsl-tools read`() {
        Examples.files().forEach { file ->
            val outcome = parsers.getValue(file.language).parse(TextSnapshot(file.text, 3))
            assertEquals(emptyList(), outcome.syntaxDiagnostics, file.path)
            assertEquals(3, outcome.version)
            assertEquals(file.language, outcome.language)
            assertNull(outcome.standaloneModel, file.path)
            assertTrue(outcome.standaloneDiagnostics.isEmpty())
        }
    }

    @Test
    fun `every example metamodel and state machine reads standalone without findings`() {
        val readable = Examples.files().filter { it.language != KarpfenLanguage.KMODEL && !it.isEventsMetamodel }
        assertEquals(11, readable.size, "6 metamodels and 5 state machines")
        readable.forEach { file ->
            val outcome = parsers.getValue(file.language).parse(TextSnapshot(file.text, 0), SemanticMode.STANDALONE)
            assertEquals(emptyList(), outcome.standaloneDiagnostics, file.path)
            when (file.language) {
                KarpfenLanguage.KMETA -> assertIs<DslModel.Kmeta>(outcome.standaloneModel, file.path)
                else -> assertIs<DslModel.Kstates>(outcome.standaloneModel, file.path)
            }
        }
    }

    @Test
    fun `models are never read on their own`() {
        Examples.files(KarpfenLanguage.KMODEL).forEach { file ->
            assertNull(parsers.getValue(KarpfenLanguage.KMODEL).parse(TextSnapshot(file.text, 0), SemanticMode.STANDALONE).standaloneModel)
        }
    }

    @Test
    fun `an events metamodel read on its own reports the domain type it needs where it is used`() {
        val events = Examples.file("karpfen-runtime/single_robot_example/EVENTS.kmeta").text

        val outcome = parsers.getValue(KarpfenLanguage.KMETA).parse(TextSnapshot(events, 0), SemanticMode.STANDALONE)

        assertTrue(outcome.isSyntaxClean)
        val diagnostic = outcome.standaloneDiagnostics.single()
        assertEquals(DiagnosticSource.DSL_TOOLS to "kmeta.unknown-target-type", diagnostic.source to diagnostic.code)
        assertEquals(events.indexOf("\"Vector\")") + 1, diagnostic.range.start, "in the has, not in the comment above it")
        assertEquals("Vector", events.substring(diagnostic.range.start, diagnostic.range.end))
        val partial = assertIs<DslModel.Kmeta>(outcome.standaloneModel).metamodel
        assertEquals(setOf("start", "setSpeed", "teleport"), partial.getClassTypes(), "the partial metamodel keeps every type")
    }

    @Test
    fun `a semantic error of a state machine is reported at the states and leaves the syntax outcome intact`() {
        val text = "STATEMACHINE ATTACHED TO \"T\" {\n STATES {\n  STATE \"a\" { }\n }\n TRANSITIONS { }\n}"

        val outcome = parsers.getValue(KarpfenLanguage.KSTATES).parse(TextSnapshot(text, 0), SemanticMode.STANDALONE)

        assertTrue(outcome.isSyntaxClean)
        val diagnostic = outcome.standaloneDiagnostics.single()
        assertEquals(DiagnosticSource.DSL_TOOLS to "kstates.initial-state-count", diagnostic.source to diagnostic.code)
        assertEquals("STATES", text.substring(diagnostic.range.start, diagnostic.range.end), "no initial state: the states as a whole")
        assertEquals(listOf("a"), assertIs<DslModel.Kstates>(outcome.standaloneModel).stateMachine.states.map { it.name })
        assertEquals(listOf("a"), outcome.index.declarationsOf(SymbolKind.STATE).map { it.name })
    }
}
