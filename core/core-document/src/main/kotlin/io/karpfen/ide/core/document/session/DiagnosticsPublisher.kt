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

import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.TextSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Publishes the merged [diagnostics] of a document: recomputed with [merger] in the session's scope whenever the text,
 * the tokens, the parse outcome or an external set changes.
 *
 * @param initialTokens the tokens of the initial snapshot; the initial value is their merge (no parse, no externals),
 *   computed synchronously.
 */
internal class DiagnosticsPublisher(private val merger: DiagnosticsMerger, initialTokens: TokenizeResult) {

    private val state = MutableStateFlow(merger.merge(initialTokens, parse = null, external = emptyMap()))

    /** The latest merged diagnostics. */
    val diagnostics: StateFlow<VersionedDiagnostics> = state.asStateFlow()

    /** Starts merging the given sources in [scope]; the returned job ends with the scope. */
    fun start(
        scope: CoroutineScope,
        snapshots: Flow<TextSnapshot>,
        tokens: Flow<TokenizeResult>,
        parse: Flow<ParseOutcome?>,
        external: Flow<Map<String, ExternalDiagnosticSet>>,
    ): Job = scope.launch {
        combine(snapshots, tokens, parse, external) { _, currentTokens, currentParse, externalSets ->
            merger.merge(currentTokens, currentParse, externalSets)
        }.collect { state.value = it }
    }
}
