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
import io.karpfen.ide.core.document.language.LanguageSupport
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextSnapshot
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

/**
 * Default [DocumentSession]: tokenize pipeline (mapLatest), debounced parse scheduler, diagnostics merger (maps every
 * source to the newest snapshot, then [mergeDiagnostics]; never a merge rule of its own).
 *
 * A coordinator (Facade) that wires single-purpose collaborators and implements nothing itself:
 * - [TokenizePipeline] — [tokens], the initial snapshot synchronously, later versions without debounce, latest wins;
 * - [ParseScheduler] — [parse] and [lastGoodParse], the first snapshot at once, later versions debounced and
 *   cancellable;
 * - [ExternalDiagnosticsStore] — the external sets of [setExternalDiagnostics], thread-safe, other lineages ignored;
 * - [DiagnosticsPublisher] with a [DiagnosticsMerger] — [diagnostics], every source mapped with `EditableText.mapRange`
 *   to the newest snapshot (external sets from their [ExternalDiagnosticsAnchors]) and merged with [mergeDiagnostics].
 *
 * All of them run in a child scope of [parentScope] (a [SupervisorJob], so the session never cancels its parent);
 * [close] cancels that scope, and the flows keep their last values.
 */
internal class DefaultDocumentSession(
    override val id: DocumentId,
    override val support: LanguageSupport,
    override val text: EditableText,
    parentScope: CoroutineScope,
    config: DocumentSessionConfig,
) : DocumentSession {

    private val scope = CoroutineScope(
        parentScope.coroutineContext +
            SupervisorJob(parentScope.coroutineContext[Job]) +
            CoroutineName("DocumentSession(${id.fileName})"),
    )
    private val tokenizing = TokenizePipeline(text, support.tokenizer, config.tokenizeDispatcher)
    private val parsing = ParseScheduler(text, support.parser, config)
    private val external = ExternalDiagnosticsStore(text.lineage)
    private val publisher = DiagnosticsPublisher(DiagnosticsMerger(text, ExternalDiagnosticsAnchors()), tokenizing.tokens.value)

    init {
        tokenizing.start(scope)
        parsing.start(scope)
        publisher.start(scope, text.snapshot, tokenizing.tokens, parsing.parse, external.sets)
    }

    override val tokens: StateFlow<TokenizeResult>
        get() = tokenizing.tokens

    override val parse: StateFlow<ParseOutcome?>
        get() = parsing.parse

    override val lastGoodParse: StateFlow<ParseOutcome?>
        get() = parsing.lastGoodParse

    override val diagnostics: StateFlow<VersionedDiagnostics>
        get() = publisher.diagnostics

    override fun setExternalDiagnostics(source: String, snapshot: TextSnapshot, diagnostics: List<Diagnostic>) {
        external.set(source, snapshot, diagnostics)
    }

    override fun close() {
        scope.cancel()
    }
}
