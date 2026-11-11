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
package io.karpfen.ide.core.workspace.context

import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.document.DocumentManager
import io.karpfen.ide.core.workspace.document.openTexts
import io.karpfen.ide.core.workspace.files.FileKind
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/**
 * The environment descriptors that are open in editors. Through this seam the context tracking sees unsaved edits.
 */
internal fun interface OpenDescriptors {
    /**
     * The current editor text of every open descriptor by path. It is emitted at once, and again whenever a descriptor
     * is opened, edited or closed.
     */
    fun texts(): Flow<Map<Path, TextSnapshot>>
}

/** The [OpenDescriptors] of [documents], which are its open documents that are environment descriptors. */
internal fun OpenDescriptors(documents: DocumentManager): OpenDescriptors =
    OpenDescriptors { documents.openTexts { it.fileKind == FileKind.ENV_DESCRIPTOR } }
