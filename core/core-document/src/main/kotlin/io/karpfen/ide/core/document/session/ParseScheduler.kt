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

import io.karpfen.ide.core.document.parse.DocumentParser
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Parses the versions of [text]. The first snapshot the scheduler sees is parsed at once. That is the snapshot the
 * session started with, unless the text changed before the scheduler ran. Every later version is parsed after
 * [DocumentSessionConfig.parseDebounce] of quiet time. A newer version cancels the pending debounce or the running
 * parse of an older one, so the latest wins and a cancelled outcome is never published. Parsing runs on
 * [DocumentSessionConfig.parseDispatcher] with [DocumentSessionConfig.semanticMode].
 *
 * [parse] is null until the first parse completes. [lastGoodParse] is the latest published outcome without syntax
 * diagnostics. The built-in parsers never throw. If a parser does throw, the failure is logged and the previous
 * outcome stays published.
 */
internal class ParseScheduler(
    private val text: EditableText,
    private val parser: DocumentParser,
    private val config: DocumentSessionConfig,
) {

    private val current = MutableStateFlow<ParseOutcome?>(null)
    private val lastGood = MutableStateFlow<ParseOutcome?>(null)

    /** The latest published parse outcome. */
    val parse: StateFlow<ParseOutcome?> = current.asStateFlow()

    /** The latest published outcome that is [ParseOutcome.isSyntaxClean]. */
    val lastGoodParse: StateFlow<ParseOutcome?> = lastGood.asStateFlow()

    /** Starts following [text] in [scope]. The returned job ends with the scope. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        text.snapshot.withIndex().collectLatest { (index, snapshot) ->
            if (index > 0) delay(config.parseDebounce)
            val outcome = withContext(config.parseDispatcher) { parseSafely(snapshot) } ?: return@collectLatest
            current.value = outcome
            if (outcome.isSyntaxClean) lastGood.value = outcome
        }
    }

    /** Parses [snapshot]. A parser exception is logged and yields null, while cancellation propagates. */
    private fun parseSafely(snapshot: TextSnapshot): ParseOutcome? =
        try {
            parser.parse(snapshot, config.semanticMode)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logger.error("Parsing text version {} failed", snapshot.version, failure)
            null
        }

    private companion object {
        private val logger = LoggerFactory.getLogger(ParseScheduler::class.java)
    }
}
