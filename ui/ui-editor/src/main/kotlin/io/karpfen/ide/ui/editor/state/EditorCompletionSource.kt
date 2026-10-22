package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.completion.CompletionSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The editor's [CompletionSource]: switches to [backgroundDispatcher] and asks
 * [languageServices] for the items at the requested offset of `LanguageDocument.of(session, snapshot)`, which tokenizes
 * [snapshot] when the session's tokens lag. The dispatcher is injected here only; the completion model never names one.
 */
internal class EditorCompletionSource(
    private val session: DocumentSession,
    private val languageServices: LanguageServices,
    private val backgroundDispatcher: CoroutineDispatcher,
) : CompletionSource {

    override suspend fun items(snapshot: TextSnapshot, offset: Int): List<CompletionItem> =
        withContext(backgroundDispatcher) { languageServices.complete(LanguageDocument.of(session, snapshot), offset) }
}
