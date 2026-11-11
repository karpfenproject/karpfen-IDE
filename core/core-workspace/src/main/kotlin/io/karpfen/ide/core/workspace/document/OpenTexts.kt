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
package io.karpfen.ide.core.workspace.document

import io.karpfen.ide.core.text.TextSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.nio.file.Path

/**
 * The current text of every open document that [accepts], by path. It is emitted at once, and again whenever such a
 * document is opened, edited or closed. An equal map is not emitted again, so opening a document that [accepts]
 * rejects emits nothing. This is the only way the workspace follows what is typed in its editors. The analysis is
 * triggered by every open text, and the context tracking follows the texts of the open descriptors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun DocumentManager.openTexts(accepts: (OpenDocument) -> Boolean = { true }): Flow<Map<Path, TextSnapshot>> =
    openDocuments.flatMapLatest { open ->
        val documents = open.filter(accepts)
        if (documents.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(documents.map { it.session.text.snapshot }) { snapshots -> documents.map { it.path }.zip(snapshots).toMap() }
        }
    }.distinctUntilChanged()
