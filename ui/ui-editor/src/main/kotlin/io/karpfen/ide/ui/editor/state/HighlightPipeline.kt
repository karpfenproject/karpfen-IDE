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
 * The highlight pipeline of one editor ([EditorViewModel.highlights]).
 *
 * Follows the session's tokens and parse; whenever the tokens belong to the current text, it computes on
 * [backgroundDispatcher] (latest wins) and publishes in [scope]:
 * - parse of the tokens' version: `LanguageServices.highlight` (lexical styles overlaid with the semantic ones);
 * - parse lagging (every keystroke until the debounced parse arrives): the fresh lexical spans, overlaid
 *   ([overlayHighlights], the one overlay rule) with the semantic-style spans of the previously published snapshot,
 *   mapped to the tokens' version with `EditableText.mapRange` (null or empty results dropped), so the semantic colours
 *   of untouched names do not flicker while typing.
 *
 * Tokens of an older version than the text are skipped: the session's tokenizer always catches up with the newest
 * version, and spans are only ever computed for a text whose tokens they came from.
 */
internal class HighlightPipeline(
    private val session: DocumentSession,
    private val languageServices: LanguageServices,
    scope: CoroutineScope,
    private val backgroundDispatcher: CoroutineDispatcher,
) {

    private val published = MutableStateFlow(HighlightSnapshot(session.tokens.value.version, emptyList()))

    /** The latest highlight result (initially empty for the initial tokens' version). */
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
     * The highlights of [snapshot] from [tokens] and [parse]; with a lagging parse the lexical spans carry the semantic
     * spans of [previous] over. Null when the language services failed (logged), so the previous result stays.
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

    /** The semantic-style spans of [previous] mapped to [version] (spans that do not map or collapse are dropped). */
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
        /** Reports failures of the language services; the previous highlights then stay. */
        val logger: Logger = LoggerFactory.getLogger(HighlightPipeline::class.java)
    }
}
