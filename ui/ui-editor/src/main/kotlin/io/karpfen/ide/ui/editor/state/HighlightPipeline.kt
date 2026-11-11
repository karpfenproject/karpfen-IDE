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
package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.lang.HighlightSpan
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.overlayHighlights
import io.karpfen.ide.core.text.TextSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The highlight pipeline of one editor, which feeds [EditorViewModel.highlights].
 *
 * It follows the tokens and the parse of the session. Whenever the tokens belong to the current text, it computes the
 * highlights on [backgroundDispatcher] and publishes them in [scope]. The latest computation wins.
 *
 * If the parse has the same version as the tokens, the result is `LanguageServices.highlight`, which overlays the
 * lexical styles with the semantic ones.
 *
 * If the parse lags behind, which happens on every keystroke until the debounced parse arrives, the result is
 * different. The fresh lexical spans are overlaid with the semantic spans of the previously published snapshot. Those
 * spans are mapped to the tokens' version with `EditableText.mapRange`, and spans that do not map or become empty are
 * dropped. The overlay uses [overlayHighlights], the single overlay rule. So the semantic colours of untouched names
 * do not flicker while the user types.
 *
 * Tokens of a version older than the text are skipped. The tokenizer of the session always catches up with the newest
 * version, and spans are only ever computed for the text their tokens came from.
 */
internal class HighlightPipeline(
    private val session: DocumentSession,
    private val languageServices: LanguageServices,
    scope: CoroutineScope,
    private val backgroundDispatcher: CoroutineDispatcher,
) {

    private val published = MutableStateFlow(HighlightSnapshot(session.tokens.value.version, emptyList()))

    /** The latest highlight result. At the start it is empty and has the version of the initial tokens. */
    val highlights: StateFlow<HighlightSnapshot> = published.asStateFlow()

    init {
        scope.launch {
            combine(session.tokens, session.parse) { tokens, parse -> tokens to parse }
                .collectLatest { (tokens, parse) ->
                    val snapshot = session.text.snapshot.value
                    if (tokens.version != snapshot.version) return@collectLatest
                    val previous = published.value
                    val next = withContext(backgroundDispatcher) { compute(snapshot, tokens, parse, previous) }
                    if (next != null) published.value = next
                }
        }
    }

    /**
     * Computes the highlights of [snapshot] from [tokens] and [parse]. With a lagging parse, the lexical spans carry
     * the semantic spans of [previous] over. Returns null when the language services failed, so the previous result
     * stays. A failure is logged.
     */
    private fun compute(
        snapshot: TextSnapshot,
        tokens: TokenizeResult,
        parse: ParseOutcome?,
        previous: HighlightSnapshot,
    ): HighlightSnapshot? {
        val document = LanguageDocument(
            path = session.id.path,
            language = session.language,
            text = snapshot,
            tokens = tokens,
            parse = parse,
            lastGoodParse = session.lastGoodParse.value,
        )
        val spans = try {
            languageServices.highlight(document)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("highlighting failed for version {} of {}", snapshot.version, session.id, e)
            return null
        }
        if (parse != null && parse.version == tokens.version) return HighlightSnapshot(tokens.version, spans)
        return HighlightSnapshot(tokens.version, overlayHighlights(spans, carriedSemantics(previous, tokens.version)))
    }

    /** Returns the semantic spans of [previous] mapped to [version]. Spans that do not map or collapse are dropped. */
    private fun carriedSemantics(previous: HighlightSnapshot, version: Long): List<HighlightSpan> {
        val text = session.text
        return previous.spans.mapNotNull { span ->
            if (!span.style.isSemantic) return@mapNotNull null
            val mapped = text.mapRange(span.range, previous.version, text.lineage, version)
            mapped?.takeUnless { it.isEmpty }?.let { HighlightSpan(it, span.style) }
        }
    }

    /** The logger. */
    private companion object {
        /** Reports failures of the language services. The previous highlights then stay. */
        val logger: Logger = LoggerFactory.getLogger(HighlightPipeline::class.java)
    }
}
