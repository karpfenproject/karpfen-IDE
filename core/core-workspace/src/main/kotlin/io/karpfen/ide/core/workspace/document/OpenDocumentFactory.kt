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

import io.karpfen.ide.core.document.language.LanguageSupport
import io.karpfen.ide.core.document.parse.SemanticMode
import io.karpfen.ide.core.document.session.DocumentId
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.document.session.ExternalDiagnosticSources
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.workspace.files.FileKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.time.TimeSource

/**
 * Creates the [DefaultOpenDocument] of a loaded file: an `EditableText` on the injected [timeSource], a
 * `SemanticMode.SYNTAX_ONLY` session (whatever [sessionConfig] says) in a child scope of [scope], and the loading
 * findings as external source `ExternalDiagnosticSources.FILE` of the initial snapshot.
 *
 * Sessions never convert with dsl-tools: the project analysis is the only place that converts files, so each file is
 * converted once, open or not, and opening a file never changes what is reported.
 */
internal class OpenDocumentFactory(
    private val scope: CoroutineScope,
    private val sessionConfig: DocumentSessionConfig,
    private val timeSource: TimeSource,
    private val conflicts: ConflictResolver,
) {

    /** The document of [loaded] (absolute, normalized path) with [support]'s tokenizer and parser. */
    fun create(loaded: LoadedSource, kind: FileKind, support: LanguageSupport): DefaultOpenDocument {
        val documentScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val text = EditableText(loaded.text, timeSource)
        val session = DocumentSession(
            DocumentId(loaded.path),
            support,
            text,
            documentScope,
            sessionConfig.copy(semanticMode = SemanticMode.SYNTAX_ONLY),
        )
        if (loaded.diagnostics.isNotEmpty()) {
            session.setExternalDiagnostics(ExternalDiagnosticSources.FILE, text.snapshot.value, loaded.diagnostics)
        }
        return DefaultOpenDocument(session, kind, loaded, documentScope, conflicts)
    }
}
