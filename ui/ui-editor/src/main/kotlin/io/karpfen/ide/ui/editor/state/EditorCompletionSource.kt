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
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.completion.CompletionSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The editor's [CompletionSource]: switches to [backgroundDispatcher] and asks [languageServices] for the items at the
 * requested offset of `LanguageDocument.of(session, snapshot)`, which tokenizes [snapshot] when the session's tokens
 * lag. The dispatcher is injected here only; the completion model never names one.
 */
internal class EditorCompletionSource(
    private val session: DocumentSession,
    private val languageServices: LanguageServices,
    private val backgroundDispatcher: CoroutineDispatcher,
) : CompletionSource {

    override suspend fun items(snapshot: TextSnapshot, offset: Int): List<CompletionItem> =
        withContext(backgroundDispatcher) { languageServices.complete(LanguageDocument.of(session, snapshot), offset) }
}
