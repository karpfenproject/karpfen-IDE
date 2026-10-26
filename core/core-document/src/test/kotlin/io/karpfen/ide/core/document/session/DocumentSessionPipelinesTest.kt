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

import io.karpfen.ide.core.document.parse.SemanticMode
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.document.syntax.Tokenizer
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextRange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Covers the token and parse pipelines of [DocumentSession] on virtual time with a fake parser. */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentSessionPipelinesTest {

    private val source = "STATEMACHINE ATTACHED TO \"Robot\" {\n    STATES { }\n    TRANSITIONS { }\n}\n"

    @Test
    fun `the initial tokens are computed synchronously by the factory`() = runTest {
        val text = EditableText(source)

        val session = session(text)

        assertEquals(realTokenizer().tokenize(text.snapshot.value), session.tokens.value)
        assertSame(text.snapshot.value, session.diagnostics.value.snapshot)
        assertNull(session.parse.value)
        assertEquals(text.lineage, session.text.lineage)
    }

    @Test
    fun `the initial snapshot is parsed without debounce`() = runTest {
        val parser = FakeParser()
        val session = session(EditableText(source), parser)

        runCurrent()

        assertEquals(0L, session.parse.value?.version)
        assertEquals(0L, currentTime)
        assertEquals(listOf(0L), parser.versions)
    }

    @Test
    fun `the first parse is undebounced also when the text changed before the pipelines ran`() = runTest {
        val text = EditableText(source)
        val parser = FakeParser()
        val session = session(text, parser)

        text.type(0, " ")
        runCurrent()

        assertEquals(1L, session.parse.value?.version)
        assertEquals(1L, session.tokens.value.version)
        assertEquals(0L, currentTime)
        assertEquals(listOf(1L), parser.versions)
    }

    @Test
    fun `every version is tokenized without debounce`() = runTest {
        val text = EditableText(source)
        val session = session(text)
        runCurrent()

        text.type(0, "// c\n")
        runCurrent()

        assertEquals(1L, session.tokens.value.version)
        assertEquals(realTokenizer().tokenize(text.snapshot.value), session.tokens.value)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `tokenizing is conflated to the latest version`() = runTest {
        val text = EditableText(source)
        val tokenizer = RecordingTokenizer()
        val session = session(text, tokenizer = tokenizer)
        runCurrent()

        repeat(3) { text.type(0, " ") }
        runCurrent()

        assertEquals(3L, session.tokens.value.version)
        assertEquals(listOf(0L, 3L), tokenizer.versions)
    }

    /** The tokenizer itself makes the edit that arrives while version 1 is being tokenized. */
    @Test
    fun `a newer version cancels the tokenizing of an older one`() = runTest {
        val text = EditableText(source)
        val tokenizer = RecordingTokenizer { snapshot -> if (snapshot.version == 1L) text.type(0, " ") }
        val session = session(text, tokenizer = tokenizer)
        val published = recordValues(session.tokens) { it.version }
        runCurrent()

        text.type(0, " ")
        runCurrent()

        assertEquals(listOf(0L, 1L, 2L), tokenizer.versions)
        assertEquals(listOf(0L, 2L), published)
    }

    @Test
    fun `parsing is debounced after edits and restarted by every edit`() = runTest {
        val text = EditableText(source)
        val parser = FakeParser()
        val session = session(text, parser)
        runCurrent()

        text.type(0, " ")
        advanceTimeBy(100.milliseconds)
        text.type(0, " ")
        advanceTimeBy(119.milliseconds)
        runCurrent()
        assertEquals(0L, session.parse.value?.version)

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(2L, session.parse.value?.version)
        assertEquals(listOf(0L, 2L), parser.versions)
        assertEquals(220L, currentTime)
    }

    @Test
    fun `the debounce follows the configuration`() = runTest {
        val text = EditableText(source)
        val session = session(text, config = testConfig().copy(parseDebounce = 500.milliseconds))
        runCurrent()

        text.type(0, " ")
        advanceTimeBy(499.milliseconds)
        runCurrent()
        assertEquals(0L, session.parse.value?.version)
        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(1L, session.parse.value?.version)
    }

    /** The parser itself makes the edit that arrives while version 1 is being parsed. */
    @Test
    fun `a newer version cancels a running parse whose outcome is never published`() = runTest {
        val text = EditableText(source)
        val parser = FakeParser { snapshot ->
            if (snapshot.version == 1L) text.type(0, " ")
            outcome(snapshot.version)
        }
        val session = session(text, parser)
        val published = recordValues(session.parse) { it?.version }
        runCurrent()

        text.type(0, " ")
        settle()

        assertEquals(listOf(0L, 1L, 2L), parser.versions)
        assertEquals(listOf(null, 0L, 2L), published)
    }

    @Test
    fun `the last good parse is the latest outcome without syntax diagnostics`() = runTest {
        val text = EditableText(source)
        val parser = FakeParser { snapshot ->
            if (snapshot.version == 1L) outcome(1, syntax = listOf(diagnostic(TextRange(0, 1)))) else outcome(snapshot.version)
        }
        val session = session(text, parser)
        runCurrent()
        assertEquals(0L, session.lastGoodParse.value?.version)

        text.type(0, " ")
        settle()
        assertEquals(1L, session.parse.value?.version)
        assertEquals(0L, session.lastGoodParse.value?.version)

        text.type(0, " ")
        settle()
        assertEquals(2L, session.lastGoodParse.value?.version)
    }

    @Test
    fun `the last good parse is null while no outcome was clean`() = runTest {
        val session = session(EditableText(source), FakeParser { outcome(it.version, syntax = listOf(diagnostic(TextRange(0, 1)))) })

        settle()

        assertEquals(0L, session.parse.value?.version)
        assertNull(session.lastGoodParse.value)
    }

    @Test
    fun `the configured semantic mode reaches the parser`() = runTest {
        val syntaxOnly = FakeParser()
        session(EditableText(source), syntaxOnly, config = testConfig())
        val standalone = FakeParser()
        session(EditableText(source), standalone, config = testConfig(SemanticMode.STANDALONE))

        runCurrent()

        assertEquals(SemanticMode.SYNTAX_ONLY, DocumentSessionConfig().semanticMode)
        assertEquals(listOf(SemanticMode.SYNTAX_ONLY), syntaxOnly.modes)
        assertEquals(listOf(SemanticMode.STANDALONE), standalone.modes)
    }

    @Test
    fun `tokensFor of a lagging session equals a fresh tokenization`() = runTest {
        val text = EditableText(source)
        val session = session(text)
        runCurrent()

        text.type(source.indexOf("STATES"), "EVAL { x // y\n}\n")
        val lagging = text.snapshot.value

        assertEquals(0L, session.tokens.value.version)
        assertEquals(realTokenizer().tokenize(lagging), session.tokensFor(lagging))
        runCurrent()
        assertSame(session.tokens.value, session.tokensFor(lagging))
    }

    @Test
    fun `a failing tokenizer or parser does not stop the pipelines`() = runTest {
        val text = EditableText(source)
        val tokenizer = Tokenizer { snapshot ->
            check(snapshot.version != 1L) { "tokenizer defect" }
            realTokenizer().tokenize(snapshot)
        }
        val parser = FakeParser { snapshot ->
            check(snapshot.version != 1L) { "parser defect" }
            outcome(snapshot.version)
        }
        val session = session(text, parser, tokenizer)
        settle()

        text.type(0, " ")
        settle()
        assertEquals(TokenizeResult.empty(1), session.tokens.value)
        assertEquals(0L, session.parse.value?.version)

        text.type(0, " ")
        settle()
        assertEquals(2L, session.tokens.value.version)
        assertEquals(2L, session.parse.value?.version)
    }

    @Test
    fun `close cancels the pipelines, keeps the last values and spares the parent scope`() = runTest {
        val text = EditableText(source)
        val parser = FakeParser()
        val session = session(text, parser)
        settle()
        val tokens = session.tokens.value
        val diagnostics = session.diagnostics.value

        session.close()
        session.close()
        text.type(0, " ")
        session.setExternalDiagnostics(ExternalDiagnosticSources.PROJECT, text.snapshot.value, listOf(diagnostic(TextRange(0, 1))))
        settle()

        assertSame(tokens, session.tokens.value)
        assertSame(diagnostics, session.diagnostics.value)
        assertEquals(0L, session.parse.value?.version)
        assertEquals(listOf(0L), parser.versions)
        assertTrue(backgroundScope.isActive)
    }

    @Test
    fun `a session closed before its pipelines ran parses nothing`() = runTest {
        val text = EditableText(source)
        val parser = FakeParser()
        val session = session(text, parser)
        session.close()

        settle()

        assertTrue(parser.versions.isEmpty())
        assertFalse(session.tokens.value.tokens.isEmpty())
    }

    /** Records every value of [flow] (mapped with [select]) from now on, in the background, without conflation. */
    private fun <T, R> TestScope.recordValues(flow: StateFlow<T>, select: (T) -> R): List<R> {
        val values = ArrayList<R>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { values += select(it) } }
        return values
    }
}
