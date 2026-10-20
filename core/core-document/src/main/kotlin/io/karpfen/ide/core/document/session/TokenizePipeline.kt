package io.karpfen.ide.core.document.session

import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.document.syntax.Tokenizer
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Keeps [tokens] equal to the tokens of the latest version of [text] (spec §3.4): the initial snapshot is tokenized
 * synchronously in the constructor; every later version is tokenized on [dispatcher] without debounce, latest wins
 * (`mapLatest` semantics: a newer snapshot cancels the tokenization of an older one, whose result is never
 * published).
 *
 * A tokenizer that throws (the built-in ones never do) is logged and yields an empty result for that version, so one
 * defective tokenizer call cannot stop the pipeline.
 */
internal class TokenizePipeline(
    private val text: EditableText,
    private val tokenizer: Tokenizer,
    private val dispatcher: CoroutineDispatcher,
) {

    private val state = MutableStateFlow(tokenizeSafely(text.snapshot.value))

    /** Tokens of the newest version tokenized so far. */
    val tokens: StateFlow<TokenizeResult> = state.asStateFlow()

    /** Starts following [text] in [scope]; the returned job ends with the scope. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        text.snapshot
            .filter { snapshot -> snapshot.version != state.value.version }
            .collectLatest { snapshot -> state.value = withContext(dispatcher) { tokenizeSafely(snapshot) } }
    }

    private fun tokenizeSafely(snapshot: TextSnapshot): TokenizeResult =
        try {
            tokenizer.tokenize(snapshot)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logger.error("Tokenizing text version {} failed", snapshot.version, failure)
            TokenizeResult.empty(snapshot.version)
        }

    private companion object {
        private val logger = LoggerFactory.getLogger(TokenizePipeline::class.java)
    }
}
