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
package io.karpfen.ide.core.document.session

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the merged, versioned diagnostics of [DocumentSession] on virtual time: every source is mapped to the newest
 * snapshot with the one range-mapping rule and combined by the one merge rule, [mergeDiagnostics].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentSessionDiagnosticsTest {

    private val source = """
        |STATEMACHINE ATTACHED TO "Robot" {
        |    STATES {
        |        STATE "a" {
        |            DO { SET("x", EVAL { return 1 // floor
        |            }) }
        |        }
        |    }
        |    TRANSITIONS { }
        |}
        |
    """.trimMargin()

    private fun rangeOf(word: String, text: String = source): TextRange =
        TextRange.ofLength(text.indexOf(word).also { require(it >= 0) { "'$word' not found" } }, word.length)

    private val parserError = diagnostic(rangeOf("STATES"))
    private val parserLexerCopy = diagnostic(rangeOf("TRANSITIONS"), DiagnosticSource.LEXER)
    private val standalone = diagnostic(rangeOf("Robot"), DiagnosticSource.DSL_TOOLS)
    private val project = diagnostic(rangeOf("STATE \"a\""), DiagnosticSource.DSL_TOOLS, "project finding")

    /** The parser outcome of version 0 with every kind of diagnostic; later versions are clean. */
    private fun richParser() = FakeParser { snapshot ->
        if (snapshot.version == 0L) outcome(0, syntax = listOf(parserError, parserLexerCopy), standalone = listOf(standalone)) else outcome(snapshot.version)
    }

    /** [diagnostics] valid in [from], mapped into the current version of [text] with the one range-mapping rule. */
    private fun mapped(text: EditableText, diagnostics: List<Diagnostic>, from: TextSnapshot): List<Diagnostic> =
        diagnostics.mapNotNull { d -> text.mapRange(d.range, from)?.let { d.copy(range = it) } }

    private fun ParseOutcome.mappedFrom(text: EditableText, from: TextSnapshot): ParseOutcome =
        copy(syntaxDiagnostics = mapped(text, syntaxDiagnostics, from), standaloneDiagnostics = mapped(text, standaloneDiagnostics, from))

    @Test
    fun `lexical, parser, standalone and external diagnostics are merged by the one rule`() = runTest {
        val text = EditableText(source)
        val v0 = text.snapshot.value
        val session = session(text, richParser())

        session.setExternalDiagnostics(ExternalDiagnosticSources.PROJECT, v0, listOf(project))
        runCurrent()

        val lexical = session.tokens.value.diagnostics
        assertEquals(listOf(DiagnosticCodes.EVAL_DOUBLE_SLASH), lexical.map { it.code })
        assertEquals(mergeDiagnostics(lexical, session.parse.value, listOf(listOf(project))), session.diagnostics.value.diagnostics)
        assertEquals(setOf(parserError, standalone, project) + lexical, session.diagnostics.value.diagnostics.toSet())
        assertSame(v0, session.diagnostics.value.snapshot)
    }

    /** The diagnostics are checked once the tokens caught up with the edits while the parse is still debounced. */
    @Test
    fun `sources of older versions are mapped to the newest snapshot with mapRange before merging`() = runTest {
        val text = EditableText(source)
        val v0 = text.snapshot.value
        val session = session(text, richParser())
        session.setExternalDiagnostics(ExternalDiagnosticSources.PROJECT, v0, listOf(project))
        runCurrent()
        val parseV0 = session.parse.value!!

        text.type(0, "// header\n")
        text.replace(rangeOf("TRANSITIONS", text.snapshot.value.text), "TRANSITION")
        runCurrent()

        val current = text.snapshot.value
        val merged = session.diagnostics.value
        assertSame(current, merged.snapshot)
        assertEquals(0L, session.parse.value?.version)
        assertEquals(
            mergeDiagnostics(session.tokens.value.diagnostics, parseV0.mappedFrom(text, v0), listOf(mapped(text, listOf(project), v0))),
            merged.diagnostics,
        )
        assertTrue(parserError.copy(range = parserError.range.shiftedBy(10)) in merged.diagnostics)
    }

    /** Tokenizing runs on a held dispatcher, so the tokens lag until the test releases it. */
    @Test
    fun `lagging tokens are mapped like every other source`() = runTest {
        val text = EditableText(source)
        val v0 = text.snapshot.value
        val tokenizing = HeldDispatcher()
        val session = session(text, FakeParser(), config = testConfig().copy(tokenizeDispatcher = tokenizing))
        runCurrent()
        val lexicalV0 = session.tokens.value.diagnostics

        text.type(0, "    ")
        runCurrent()

        assertEquals(0L, session.tokens.value.version)
        assertSame(text.snapshot.value, session.diagnostics.value.snapshot)
        assertEquals(mergeDiagnostics(mapped(text, lexicalV0, v0), session.parse.value), session.diagnostics.value.diagnostics)
        assertEquals(lexicalV0.single().range.shiftedBy(4), session.diagnostics.value.diagnostics.single().range)

        tokenizing.runHeld()
        runCurrent()
        assertEquals(1L, session.tokens.value.version)
        assertEquals(session.tokens.value.diagnostics, session.diagnostics.value.diagnostics)
    }

    @Test
    fun `the diagnostics follow a parse that caught up`() = runTest {
        val text = EditableText(source)
        val later = diagnostic(TextRange(0, 3), message = "found by the later parse")
        val session = session(text, FakeParser { snapshot -> outcome(snapshot.version, syntax = if (snapshot.version == 0L) emptyList() else listOf(later)) })
        settle()

        text.type(0, " ")
        settle()

        assertTrue(later in session.diagnostics.value.diagnostics)
        assertEquals(1L, session.diagnostics.value.version)
    }

    /**
     * The foreign snapshot has the same text and version but another lineage, like an analysis of a document that was
     * closed and reopened since.
     */
    @Test
    fun `an external set for a snapshot of another lineage is ignored`() = runTest {
        val text = EditableText(source)
        val session = session(text)
        session.setExternalDiagnostics(ExternalDiagnosticSources.PROJECT, text.snapshot.value, listOf(project))
        runCurrent()
        val before = session.diagnostics.value

        val stranger = TextSnapshot(source, 0)
        session.setExternalDiagnostics(ExternalDiagnosticSources.PROJECT, stranger, emptyList())
        session.setExternalDiagnostics(ExternalDiagnosticSources.FILE, stranger, listOf(diagnostic(TextRange(0, 1))))
        runCurrent()

        assertEquals(before.diagnostics, session.diagnostics.value.diagnostics)
        assertTrue(project in session.diagnostics.value.diagnostics)
    }

    @Test
    fun `an empty external list removes the source`() = runTest {
        val text = EditableText(source)
        val session = session(text)
        session.setExternalDiagnostics(ExternalDiagnosticSources.PROJECT, text.snapshot.value, listOf(project))
        runCurrent()
        assertTrue(project in session.diagnostics.value.diagnostics)

        session.setExternalDiagnostics(ExternalDiagnosticSources.PROJECT, text.snapshot.value, emptyList())
        runCurrent()

        assertTrue(project !in session.diagnostics.value.diagnostics)
    }

    @Test
    fun `external diagnostics may be set from any thread`() = runTest {
        val text = EditableText(source)
        val session = session(text)
        val snapshot = text.snapshot.value
        val start = CountDownLatch(1)

        val threads = List(THREADS) { index ->
            thread(name = "analysis-$index") {
                start.await()
                repeat(50) { round -> session.setExternalDiagnostics("source-$index", snapshot, listOf(diagnostic(TextRange(index, index + 1), message = "round $round"))) }
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        runCurrent()

        val external = session.diagnostics.value.diagnostics.filter { it.message.startsWith("round") }
        assertEquals(List(THREADS) { "round 49" }, external.map { it.message })
        assertEquals((0 until THREADS).map { TextRange(it, it + 1) }, external.map { it.range })
    }

    @Test
    fun `typing at the offset of an empty diagnostic keeps it as an empty range`() = runTest {
        val broken = "STATEMACHINE ATTACHED TO \"Robot\" {\n    STATES {"
        val text = EditableText(broken)
        val missingBrace = diagnostic(TextRange.empty(broken.length), message = "missing '}'")
        val session = session(text, FakeParser { outcome(it.version, syntax = if (it.version == 0L) listOf(missingBrace) else emptyList()) })
        runCurrent()

        repeat(3) {
            val end = text.snapshot.value.length
            text.type(end, "x")
            runCurrent()

            val kept = session.diagnostics.value.diagnostics.single { it.message == "missing '}'" }
            assertEquals(TextRange.empty(end + 1), kept.range)
        }
    }

    @Test
    fun `replacing a selection around a diagnostic keeps it as an empty range`() = runTest {
        val text = EditableText(source)
        val robot = rangeOf("Robot")
        val session = session(text, FakeParser { outcome(it.version, standalone = if (it.version == 0L) listOf(standalone) else emptyList()) })
        runCurrent()

        text.replace(TextRange(robot.start - 1, robot.end + 1), "\"Car\"")
        runCurrent()

        val kept = session.diagnostics.value.diagnostics.single { it.source == DiagnosticSource.DSL_TOOLS }
        assertTrue(kept.range.isEmpty, "${kept.range}")
        assertTrue(kept.range.start in robot.start - 1..robot.start + 4, "${kept.range}")
    }

    @Test
    fun `diagnostics whose history was evicted are dropped`() = runTest {
        val text = EditableText(source, historyLimit = 2)
        val v0 = text.snapshot.value
        val session = session(text, richParser())
        session.setExternalDiagnostics(ExternalDiagnosticSources.PROJECT, v0, listOf(project))
        runCurrent()

        repeat(3) { text.type(0, " ") }
        runCurrent()

        assertEquals(listOf(DiagnosticCodes.EVAL_DOUBLE_SLASH), session.diagnostics.value.diagnostics.map { it.code })
        assertEquals(3L, session.diagnostics.value.version)
    }

    @Test
    fun `an external set reported once outlives the retained edit history`() = runTest {
        val text = EditableText(source, historyLimit = 2)
        val v0 = text.snapshot.value
        val session = session(text, richParser())
        val finding = diagnostic(rangeOf("Robot"), DiagnosticSource.IDE, "file finding")
        session.setExternalDiagnostics(ExternalDiagnosticSources.FILE, v0, listOf(finding))
        runCurrent()

        repeat(5) {
            text.type(0, " ")
            runCurrent()
        }

        assertEquals(5L, session.diagnostics.value.version)
        assertEquals(
            listOf(finding.copy(range = finding.range.shiftedBy(5))),
            session.diagnostics.value.diagnostics.filter { it.message == "file finding" },
            "mapped edit by edit, although version 0 left the history after two edits",
        )
    }

    private companion object {
        const val THREADS = 8
    }
}
