package io.karpfen.ide.core.document.session

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.language.LanguageSupport
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.EditableText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Facade over one open document (spec §1a, §3.4): keeps tokens, parse outcome and diagnostics of its [text] up to
 * date. It coordinates collaborators (tokenize pipeline, parse scheduler, diagnostics merger) and owns no editing
 * logic; the text is edited through [text] directly.
 *
 * Lifecycle: all work runs in a child scope of the scope given to the factory; [close] cancels it. Flows keep their
 * last values after closing.
 *
 * Threading: [text] has a single writer (spec §1); the session only reads it (snapshots and the change flow) and never
 * edits it. Tokenizing and parsing run on the dispatchers of [DocumentSessionConfig].
 */
public interface DocumentSession : AutoCloseable {

    /** The document's identity (its path). */
    public val id: DocumentId

    /** Tokenizer and parser of the document's language. */
    public val support: LanguageSupport

    /** The document's language. */
    public val language: KarpfenLanguage get() = support.language

    /** The edited text. */
    public val text: EditableText

    /**
     * Tokens of the latest text version: recomputed for every version, conflated (latest wins), no debounce. The
     * initial value is the result for the initial snapshot, computed synchronously by the factory.
     */
    public val tokens: StateFlow<TokenizeResult>

    /**
     * Parse outcome: debounced by `DocumentSessionConfig.parseDebounce`, cancellable, latest wins. Null until the
     * first parse completes; the initial snapshot is parsed without debounce.
     */
    public val parse: StateFlow<ParseOutcome?>

    /** The latest parse outcome without syntax diagnostics ([ParseOutcome.isSyntaxClean]), or null if there was none. */
    public val lastGoodParse: StateFlow<ParseOutcome?>

    /**
     * Lexer diagnostics (from [tokens]), PARSER diagnostics and standalone DSL_TOOLS diagnostics (from [parse]) and
     * every external set, merged, deduplicated and sorted, with ranges mapped to the newest version the session has
     * seen (via `EditableText.mapOffset`; diagnostics whose range can no longer be mapped are dropped).
     */
    public val diagnostics: StateFlow<VersionedDiagnostics>

    /**
     * Replaces the external diagnostics of [source] (see [ExternalDiagnosticSources]), computed for text [version]. An
     * empty list removes the source's diagnostics. Thread-safe: may be called from any thread (core-workspace calls it
     * after an analysis on its analysis dispatcher); the merged [diagnostics] are recomputed in the session's scope.
     */
    public fun setExternalDiagnostics(source: String, version: Long, diagnostics: List<Diagnostic>)

    /** Cancels all background work of this session. Idempotent. */
    override fun close()
}

/**
 * Creates a [DocumentSession] for [text] and starts its pipelines in a child scope of [scope].
 *
 * @param id the document identity.
 * @param support tokenizer and parser to use.
 * @param text the document text; the session never edits it.
 */
public fun DocumentSession(
    id: DocumentId,
    support: LanguageSupport,
    text: EditableText,
    scope: CoroutineScope,
    config: DocumentSessionConfig = DocumentSessionConfig(),
): DocumentSession = DefaultDocumentSession(id, support, text, scope, config)

/** The external diagnostic sources used by the core modules ([DocumentSession.setExternalDiagnostics]). */
public object ExternalDiagnosticSources {
    /** Project analysis of core-workspace (DSL_TOOLS conversions, descriptor and context findings). */
    public const val PROJECT: String = "project"

    /** Loading the file: malformed UTF-8 (WARNING) and mixed line separators (INFO), source IDE. */
    public const val FILE: String = "file"
}
