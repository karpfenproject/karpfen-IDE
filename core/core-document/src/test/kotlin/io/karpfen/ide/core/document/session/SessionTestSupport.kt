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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

// Test support of the session tests (plan WP-TOKENS, Fakes): real tokenizers through the public registry, a fake
// DocumentParser (WP-PARSE is developed in parallel) returning hand-built outcomes, virtual time through
// StandardTestDispatchers on the test scheduler.

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
        DiagnosticSource.DSL_TOOLS -> DiagnosticCodes.DSL_TOOLS_ERROR
        DiagnosticSource.IDE -> DiagnosticCodes.INTERNAL_ERROR
    }
    return Diagnostic(range, Severity.ERROR, message, code, source)
}

/**
 * A [DocumentParser] fake: answers [outcomeFor] (by default a clean outcome of the snapshot's version) and records the
 * parsed versions and modes. Used on the test thread only.
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

/** A tokenizer delegating to [delegate] that records the tokenized versions (test thread only). */
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

/** A config whose dispatchers run on the virtual time of this test. */
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
    support = LanguageSupport(KarpfenLanguage.KSTATES, tokenizer, parser),
    text = text,
    scope = backgroundScope,
    config = config,
)

/**
 * Runs everything due within the next second of virtual time. The session runs in `backgroundScope`, whose work
 * `advanceUntilIdle` does not wait for; `advanceTimeBy` and `runCurrent` run it.
 */
internal fun TestScope.settle() {
    advanceTimeBy(1.seconds)
    runCurrent()
}

/**
 * A dispatcher that holds back what is dispatched to it until [runHeld], like a busy background thread (used to let
 * one pipeline lag behind the others). Test thread only.
 */
internal class HeldDispatcher : CoroutineDispatcher() {
    private val held = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        held.addLast(block)
    }

    /** Runs the held tasks (and those they dispatch here) on the calling thread. */
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
