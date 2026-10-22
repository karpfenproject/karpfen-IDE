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
 * The current text of every open document that [accepts], by path: emitted at once, and again whenever such a document
 * is opened, edited or closed (an equal map is not repeated, so opening a document [accepts] rejects emits nothing).
 * The one way the workspace follows what is typed in its editors: the analysis triggers on every open text, the context
 * tracking on the texts of the open descriptors.
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
