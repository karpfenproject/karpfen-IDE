package io.karpfen.ide.core.document.session

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.language.LanguageSupport
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextSnapshot
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

    /**
     * Tokenizer and parser of the document's language. Identifies the language bundle; consumers never tokenize or
     * parse the session's text with it themselves — tokens of a given version come from [tokensFor].
     */
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
     * The tokens of exactly [snapshot], a version of [text]: [tokens]' value when its version matches, otherwise
     * tokenized synchronously with the session's tokenizer (e.g. right after an edit, before the tokenize pipeline
     * caught up). The one answer to "tokens of this text version" (decision 38 of the work-package plan):
     * `LanguageDocument.of`, the editor's action context (toggle comment) and the workspace's analysis sources use it,
     * never a tokenizer of their own. Thread-safe (tokenizers are stateless).
     *
     * @throws IllegalArgumentException if [snapshot] is not a version of [text] (another lineage; programming error).
     */
    public fun tokensFor(snapshot: TextSnapshot): TokenizeResult {
        require(snapshot.lineage === text.lineage) { "the snapshot is not a version of the session's text" }
        return tokens.value.takeIf { it.version == snapshot.version } ?: support.tokenizer.tokenize(snapshot)
    }

    /**
     * Lexer diagnostics (from [tokens]), PARSER diagnostics and standalone DSL_TOOLS diagnostics (from [parse]) and
     * every external set, merged by [mergeDiagnostics] (the one merge rule, shared with core-workspace) after mapping
     * every range to the newest snapshot the session has seen with `EditableText.mapRange` (the one range-mapping rule;
     * diagnostics whose range does not map — evicted history — are dropped). An external set is mapped from the
     * snapshot of the previous merge, so a set reported once (e.g. the FILE findings) lives until its source replaces
     * or removes it, however long the edit history grows. [VersionedDiagnostics.snapshot] is that snapshot.
     */
    public val diagnostics: StateFlow<VersionedDiagnostics>

    /**
     * Replaces the external diagnostics of [source] (see [ExternalDiagnosticSources]), computed for [snapshot]. An
     * empty list removes the source's diagnostics. A [snapshot] of another lineage than [text]
     * (`TextSnapshot.lineage`, e.g. an analysis of the file that started before it was closed and reopened) is ignored,
     * because its version cannot be mapped into this text. Thread-safe: may be called from any thread (core-workspace
     * calls it after an analysis on its analysis dispatcher); the merged [diagnostics] are recomputed in the session's
     * scope.
     */
    public fun setExternalDiagnostics(source: String, snapshot: TextSnapshot, diagnostics: List<Diagnostic>)

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

    /**
     * Loading the file: malformed UTF-8 (WARNING) and mixed line separators (INFO), source IDE. Set on open and replaced
     * on every reload; cleared by a successful save (the written file is valid UTF-8 with one separator).
     */
    public const val FILE: String = "file"
}
