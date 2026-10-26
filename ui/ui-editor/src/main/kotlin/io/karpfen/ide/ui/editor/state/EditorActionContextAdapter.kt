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
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.ui.editor.model.action.AutoClosedPairs
import io.karpfen.ide.ui.editor.model.action.ClipboardPort
import io.karpfen.ide.ui.editor.model.action.EditorActionContext
import io.karpfen.ide.ui.editor.model.action.EditorUiPort
import io.karpfen.ide.ui.editor.model.caret.CaretModel

/**
 * The [EditorActionContext] of one editor (Adapter from the editor state's parts to what actions may use).
 *
 * [tokens] is read lazily as `session.tokensFor(text.snapshot.value)` on every access: the tokens of exactly the
 * current text, tokenized synchronously when the session's pipeline lags (e.g. toggle comment right after Enter), so an
 * action never compares offsets of the current text with EVAL regions of an older version. [pageLines] is the number of
 * fully visible lines of [viewport], [lineComment] the line comment of the session's language support.
 */
internal class EditorActionContextAdapter(
    private val session: DocumentSession,
    override val caret: CaretModel,
    override val autoPairs: AutoClosedPairs,
    private val viewport: EditorViewport,
    override val clipboard: ClipboardPort,
    override val ui: EditorUiPort,
) : EditorActionContext {

    override val text: EditableText get() = session.text

    override val tokens: TokenizeResult get() = session.tokensFor(session.text.snapshot.value)

    override val pageLines: Int get() = viewport.state.value.fullyVisibleLines()

    override val lineComment: String? get() = session.support.lineComment
}
