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
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.language.LanguageSupport
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Facade over one open document. It keeps the tokens, the parse outcome and the diagnostics of its [text] up to date.
 * It coordinates its collaborators, which are the tokenize pipeline, the parse scheduler and the diagnostics merger.
 * It owns no editing logic. The text is edited through [text] directly.
 *
 * All work runs in a child scope of the scope given to the factory, and [close] cancels it. The flows keep their last
 * values after closing.
 *
 * [text] has a single writer, which means that one thread edits it. The session only reads it, through snapshots and
 * the change flow, and never edits it. Tokenizing and parsing run on the dispatchers of [DocumentSessionConfig].
 */
public interface DocumentSession : AutoCloseable {

    /** The identity of the document, which is its path. */
    public val id: DocumentId

    /**
     * The tokenizer and parser of the document's language. It identifies the language bundle. Consumers never
     * tokenize or parse the session's text with it themselves. The tokens of a given version come from [tokensFor].
     */
    public val support: LanguageSupport

    /** The document's language. */
    public val language: KarpfenLanguage get() = support.language

    /** The edited text. */
    public val text: EditableText

    /**
     * The tokens of the latest text version. They are recomputed for every version without debounce. The flow is
     * conflated, so the latest result wins. The initial value is the result for the initial snapshot, which the
     * factory computes synchronously.
     */
    public val tokens: StateFlow<TokenizeResult>

    /**
     * The parse outcome. Parsing is debounced by [DocumentSessionConfig.parseDebounce] and cancellable, and the latest
     * outcome wins. The value is null until the first parse completes. The initial snapshot is parsed without debounce.
     */
    public val parse: StateFlow<ParseOutcome?>

    /**
     * The latest parse outcome without syntax diagnostics, as [ParseOutcome.isSyntaxClean] tells, or null if there was
     * none.
     */
    public val lastGoodParse: StateFlow<ParseOutcome?>

    /**
     * The tokens of exactly [snapshot], which is a version of [text]. They are the value of [tokens] when its version
     * matches. Otherwise the session's tokenizer tokenizes [snapshot] synchronously. That happens, for example, right
     * after an edit, before the tokenize pipeline has caught up.
     *
     * This is the one place that answers which tokens belong to a text version. `LanguageDocument.of`, the editor's
     * action context for toggle comment and the workspace's analysis sources all use it, and none has a tokenizer of
     * its own. So nobody compares current offsets with the tokens of an older version. It is thread-safe, because
     * tokenizers are stateless.
     *
     * @throws IllegalArgumentException if [snapshot] belongs to another lineage and so is not a version of [text]. That
     *   is a programming error.
     */
    public fun tokensFor(snapshot: TextSnapshot): TokenizeResult {
        require(snapshot.lineage === text.lineage) { "the snapshot is not a version of the session's text" }
        return tokens.value.takeIf { it.version == snapshot.version } ?: support.tokenizer.tokenize(snapshot)
    }

    /**
     * All diagnostics of the document. They are the lexer diagnostics from [tokens], the PARSER diagnostics and the
     * standalone DSL_TOOLS diagnostics from [parse], and every external set. Every range is first mapped to the newest
     * snapshot the session has seen, with `EditableText.mapRange`, the one range-mapping rule. A diagnostic whose range
     * does not map, because that part of the history was evicted, is dropped. [mergeDiagnostics] then merges the rest.
     * It is the one merge rule, and core-workspace shares it.
     *
     * An external set is mapped from the snapshot of the previous merge. So a set reported once, such as the FILE
     * findings, lives until its source replaces or removes it, however long the edit history grows. The
     * [VersionedDiagnostics.snapshot] of each merge is the snapshot that all its ranges were mapped to.
     */
    public val diagnostics: StateFlow<VersionedDiagnostics>

    /**
     * Replaces the external diagnostics of [source], computed for [snapshot]. [ExternalDiagnosticSources] lists the
     * sources of the core modules. An empty list removes the diagnostics of the source.
     *
     * A [snapshot] whose `TextSnapshot.lineage` differs from that of [text] is ignored, because its version cannot be
     * mapped into this text. That happens, for example, for an analysis of the file that started before the file was
     * closed and reopened.
     *
     * It may be called from any thread. core-workspace calls it after an analysis on its analysis dispatcher. The
     * merged [diagnostics] are recomputed in the session's scope.
     */
    public fun setExternalDiagnostics(source: String, snapshot: TextSnapshot, diagnostics: List<Diagnostic>)

    /** Cancels all background work of this session. Idempotent. */
    override fun close()
}

/**
 * Creates a [DocumentSession] for [text] and starts its pipelines in a child scope of [scope].
 *
 * @param id the identity of the document, which is its absolute path. The session publishes it as
 *   [DocumentSession.id].
 * @param support the tokenizer and parser to use.
 * @param text the document text. The session never edits it.
 * @param scope the scope whose child scope runs all work of the session.
 * @param config the tuning of the session.
 */
public fun DocumentSession(
    id: DocumentId,
    support: LanguageSupport,
    text: EditableText,
    scope: CoroutineScope,
    config: DocumentSessionConfig = DocumentSessionConfig(),
): DocumentSession = DefaultDocumentSession(id, support, text, scope, config)

/** The external diagnostic sources that the core modules pass to [DocumentSession.setExternalDiagnostics]. */
public object ExternalDiagnosticSources {
    /**
     * The project analysis of core-workspace. It reports the findings of the dsl-tools readers, descriptors and
     * contexts.
     */
    public const val PROJECT: String = "project"

    /**
     * Loading the file. It reports malformed UTF-8 as a WARNING and mixed line separators as INFO, both with the
     * source IDE. It is set on open and replaced on every reload. A successful save clears it, because the written
     * file is valid UTF-8 with one separator.
     */
    public const val FILE: String = "file"
}
