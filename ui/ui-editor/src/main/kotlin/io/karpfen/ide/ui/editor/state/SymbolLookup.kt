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

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Looks up hover content and definition targets at an offset of the current text. It is internal to the editor state,
 * whose public roles offer intents only.
 *
 * A language request first waits for a parse of the text version it refers to, so providers normally see a current
 * parse. The wait is at most [parseWait] on the caller's clock, which is virtual in tests. Then the request runs on
 * [backgroundDispatcher]. Nothing here throws. Failures are logged and yield no result.
 */
internal class SymbolLookup(
    private val session: DocumentSession,
    private val languageServices: LanguageServices,
    private val backgroundDispatcher: CoroutineDispatcher,
    private val parseWait: Duration = PARSE_WAIT,
) {

    /** Returns the declarations of the symbol at [offset] of the current text, or an empty list if there are none. */
    suspend fun definitions(offset: Int): List<Location> {
        val snapshot = session.text.snapshot.value
        val document = currentDocument(snapshot)
        return safely(emptyList()) { languageServices.definition(document, offset.coerceIn(0, snapshot.length)) }
    }

    /**
     * Returns what the hover tooltip shows at [offset] of the current text. If there are diagnostics at the offset, it
     * shows them, and no parse wait is needed. Otherwise it shows the language hover. Returns null if there is neither.
     */
    suspend fun hoverAt(offset: Int): HoverContent? {
        val snapshot = session.text.snapshot.value
        val diagnostics = withContext(backgroundDispatcher) { diagnosticsAt(snapshot, offset) }
        if (diagnostics.isNotEmpty()) {
            val range = diagnostics.map { it.range }.reduce(TextRange::union)
            return HoverContent(range, diagnostics, hover = null)
        }
        val document = currentDocument(snapshot)
        val hover = safely(null) { languageServices.hover(document, offset.coerceIn(0, snapshot.length)) } ?: return null
        return HoverContent(hover.range, emptyList(), hover)
    }

    /**
     * Returns the session's diagnostics whose range covers [offset] after it is mapped into [snapshot] with
     * `EditableText.mapRange`. A non-empty range covers [offset] when it contains the character at [offset]. An empty
     * range covers the character at its position and the one before it, because a marker such as "missing `}`" is drawn
     * there. Diagnostics of a newer text than [snapshot] do not map and are skipped.
     */
    private fun diagnosticsAt(snapshot: TextSnapshot, offset: Int): List<Diagnostic> {
        val current = session.diagnostics.value
        return current.diagnostics.mapNotNull { diagnostic ->
            val range = session.text.mapRange(diagnostic.range, current.snapshot, snapshot.version) ?: return@mapNotNull null
            val hit = if (range.isEmpty) offset == range.start || offset == range.start - 1 else offset in range
            if (hit) diagnostic.copy(range = range) else null
        }
    }

    /** Returns [snapshot] as a language document, after waiting a bounded time for a parse of its version. */
    private suspend fun currentDocument(snapshot: TextSnapshot): LanguageDocument {
        withTimeoutOrNull(parseWait) { session.parse.first { it != null && it.version >= snapshot.version } }
        return withContext(backgroundDispatcher) { LanguageDocument.of(session, snapshot) }
    }

    /** Runs [request] on the background dispatcher. A failure is logged and yields [fallback]. */
    private suspend fun <T> safely(fallback: T, request: () -> T): T = withContext(backgroundDispatcher) {
        try {
            request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("language request failed for {}", session.id, e)
            fallback
        }
    }

    /** The parse wait and the logger. */
    companion object {
        /** How long hover and definition wait for a parse of the current text before using what is there. */
        val PARSE_WAIT: Duration = 1.seconds

        /** Reports failed language requests. They yield no result. */
        private val logger: Logger = LoggerFactory.getLogger(SymbolLookup::class.java)
    }
}
