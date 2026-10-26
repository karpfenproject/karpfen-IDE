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

import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.lang.BracketMatch
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The bracket pair at the primary caret ([EditorViewModel.bracketMatch]): follows the caret model's state (which also
 * changes with every text version), asks `LanguageServices.matchingBracket` on [backgroundDispatcher] (latest wins) and
 * publishes the versioned result in [scope]. Carets that lag the text are skipped; the caret model catches up and
 * publishes again.
 */
internal class BracketTracker(
    private val session: DocumentSession,
    caret: CaretModel,
    private val languageServices: LanguageServices,
    scope: CoroutineScope,
    private val backgroundDispatcher: CoroutineDispatcher,
) {

    private val published = MutableStateFlow(BracketMatchSnapshot(session.text.snapshot.value.version, null))

    /** The latest bracket match. */
    val bracketMatch: StateFlow<BracketMatchSnapshot> = published.asStateFlow()

    init {
        scope.launch {
            caret.state.collectLatest { carets ->
                val snapshot = session.text.snapshot.value
                if (carets.version != snapshot.version) return@collectLatest
                val offset = carets.primary.position
                val match = withContext(backgroundDispatcher) { matchAt(snapshot, offset) }
                published.value = BracketMatchSnapshot(snapshot.version, match)
            }
        }
    }

    /** The bracket pair at [offset] of [snapshot]; null if there is none or the language services failed (logged). */
    private fun matchAt(snapshot: TextSnapshot, offset: Int): BracketMatch? =
        try {
            languageServices.matchingBracket(LanguageDocument.of(session, snapshot), offset)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("bracket matching failed at {} of {}", offset, session.id, e)
            null
        }

    /** The logger. */
    private companion object {
        /** Reports failures of the language services; bracket matching then shows nothing. */
        val logger: Logger = LoggerFactory.getLogger(BracketTracker::class.java)
    }
}
