package io.karpfen.ide.core.document.session

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.language.LanguageSupport
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Default [DocumentSession]: tokenize pipeline (mapLatest), debounced parse scheduler, diagnostics merger (maps every
 * source to the newest snapshot, then [mergeDiagnostics]; never a merge rule of its own).
 */
internal class DefaultDocumentSession(
    override val id: DocumentId,
    override val support: LanguageSupport,
    override val text: EditableText,
    private val parentScope: CoroutineScope,
    private val config: DocumentSessionConfig,
) : DocumentSession {

    override val tokens: StateFlow<TokenizeResult>
        get() = TODO("WP-TOKENS: tokenize pipeline")

    override val parse: StateFlow<ParseOutcome?>
        get() = TODO("WP-TOKENS: debounced parse scheduler")

    override val lastGoodParse: StateFlow<ParseOutcome?>
        get() = TODO("WP-TOKENS: last good parse")

    override val diagnostics: StateFlow<VersionedDiagnostics>
        get() = TODO("WP-TOKENS: merged diagnostics")

    override fun setExternalDiagnostics(source: String, snapshot: TextSnapshot, diagnostics: List<Diagnostic>): Unit =
        TODO("WP-TOKENS: external diagnostics")

    override fun close(): Unit = TODO("WP-TOKENS: cancel the session scope")
}
