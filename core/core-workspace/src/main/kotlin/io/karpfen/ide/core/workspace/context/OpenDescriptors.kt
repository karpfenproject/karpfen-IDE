package io.karpfen.ide.core.workspace.context

import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.document.DocumentManager
import io.karpfen.ide.core.workspace.document.openTexts
import io.karpfen.ide.core.workspace.files.FileKind
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/** The environment descriptors open in editors, as the context tracking sees unsaved edits. */
internal fun interface OpenDescriptors {
    /**
     * The current editor text of every open descriptor by path: emitted at once, and again whenever a descriptor is
     * opened, edited or closed.
     */
    fun texts(): Flow<Map<Path, TextSnapshot>>
}

/** The [OpenDescriptors] of [documents]: its open documents that are environment descriptors. */
internal fun OpenDescriptors(documents: DocumentManager): OpenDescriptors =
    OpenDescriptors { documents.openTexts { it.fileKind == FileKind.ENV_DESCRIPTOR } }
