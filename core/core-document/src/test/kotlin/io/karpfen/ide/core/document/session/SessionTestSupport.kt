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
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.language.LanguageSupport
import io.karpfen.ide.core.document.parse.DocumentParser
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.parse.SemanticMode
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.syntax.Tokenizer
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

/** The real tokenizer of [language], reached like every consumer reaches it: through the registry. */
internal fun realTokenizer(language: KarpfenLanguage = KarpfenLanguage.KSTATES): Tokenizer =
    defaultLanguageRegistry().support(language).tokenizer

/** A parse outcome of [version] with the given diagnostics and nothing else. */
internal fun outcome(
    version: Long,
    syntax: List<Diagnostic> = emptyList(),
    standalone: List<Diagnostic> = emptyList(),
): ParseOutcome = ParseOutcome(
    language = KarpfenLanguage.KSTATES,
    version = version,
    syntaxDiagnostics = syntax,
    outline = emptyList(),
    index = SyntaxIndex.EMPTY,
    folding = emptyList(),
    semanticSpans = emptyList(),
    standaloneModel = null,
    standaloneDiagnostics = standalone,
)

/** An ERROR diagnostic over [range] from [source]. */
internal fun diagnostic(
    range: TextRange,
    source: DiagnosticSource = DiagnosticSource.PARSER,
    message: String = "problem at $range",
): Diagnostic {
    val code = when (source) {
        DiagnosticSource.LEXER -> DiagnosticCodes.LEXER_ERROR
        DiagnosticSource.PARSER -> DiagnosticCodes.SYNTAX_ERROR
        DiagnosticSource.DSL_TOOLS -> "kmodel.unknown-property"
        DiagnosticSource.IDE -> DiagnosticCodes.INTERNAL_ERROR
    }
    return Diagnostic(range, Severity.ERROR, message, code, source)
}

/**
 * A [DocumentParser] fake that returns hand-built outcomes, so the session tests control every parse result. It
 * answers with [outcomeFor], which by default gives a clean outcome of the snapshot's version. It records the parsed
 * versions and modes. Use it on the test thread only.
 */
internal class FakeParser(private val outcomeFor: (TextSnapshot) -> ParseOutcome = { outcome(it.version) }) : DocumentParser {

    /** The versions parsed so far, in order. */
    val versions = ArrayList<Long>()

    /** The modes of the calls so far, in order. */
    val modes = ArrayList<SemanticMode>()

    override fun parse(snapshot: TextSnapshot, mode: SemanticMode): ParseOutcome {
        versions += snapshot.version
        modes += mode
        return outcomeFor(snapshot)
    }
}

/** A tokenizer that delegates to [delegate] and records the tokenized versions. Use it on the test thread only. */
internal class RecordingTokenizer(
    private val delegate: Tokenizer = realTokenizer(),
    private val onTokenize: (TextSnapshot) -> Unit = {},
) : Tokenizer {

    /** The versions tokenized so far, in order. */
    val versions = ArrayList<Long>()

    override fun tokenize(snapshot: TextSnapshot) = delegate.tokenize(snapshot).also {
        versions += snapshot.version
        onTokenize(snapshot)
    }
}

/** A config whose dispatchers are `StandardTestDispatcher`s on this test's scheduler, so they run on virtual time. */
internal fun TestScope.testConfig(semanticMode: SemanticMode = SemanticMode.SYNTAX_ONLY): DocumentSessionConfig {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return DocumentSessionConfig(semanticMode = semanticMode, tokenizeDispatcher = dispatcher, parseDispatcher = dispatcher)
}

/** A session over [text] in this test's background scope with [tokenizer] and [parser]. */
internal fun TestScope.session(
    text: EditableText,
    parser: DocumentParser = FakeParser(),
    tokenizer: Tokenizer = realTokenizer(),
    config: DocumentSessionConfig = testConfig(),
): DocumentSession = DocumentSession(
    id = DocumentId.of(Path.of("robot.kstates")),
    support = LanguageSupport(KarpfenLanguage.KSTATES, tokenizer, parser, lineComment = "//"),
    text = text,
    scope = backgroundScope,
    config = config,
)

/**
 * Runs everything due within the next second of virtual time. The session runs in `backgroundScope`, whose work
 * `advanceUntilIdle` does not wait for. `advanceTimeBy` and `runCurrent` do run it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.settle() {
    advanceTimeBy(1.seconds)
    runCurrent()
}

/**
 * A dispatcher that holds back what is dispatched to it until [runHeld], like a busy background thread. Tests use it
 * to let one pipeline lag behind the others. Use it on the test thread only.
 */
internal class HeldDispatcher : CoroutineDispatcher() {
    private val held = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        held.addLast(block)
    }

    /** Runs the held tasks, and those they dispatch here, on the calling thread. */
    fun runHeld() {
        while (held.isNotEmpty()) held.removeFirst().run()
    }
}

/** Inserts [inserted] at [offset] as typing. */
internal fun EditableText.type(offset: Int, inserted: String) {
    apply(listOf(TextEdit.insert(offset, inserted)), EditOrigin.Typing)
}

/** Replaces [range] by [replacement] as a paste. */
internal fun EditableText.replace(range: TextRange, replacement: String) {
    apply(listOf(TextEdit.replace(range, replacement)), EditOrigin.Paste)
}
