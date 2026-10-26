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

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import java.nio.file.Path
import kotlin.time.TestTimeSource

/** A [DefaultDocumentManager] on the test's virtual time: every dispatcher is a test dispatcher of [scope]. */
internal fun documentManager(
    scope: TestScope,
    sourceLoader: SourceLoader = SourceLoader(),
    fileStore: TextFileStore = TextFileStore(),
    sessionConfig: DocumentSessionConfig? = null,
    timeSource: TestTimeSource = TestTimeSource(),
): DefaultDocumentManager {
    val dispatcher = StandardTestDispatcher(scope.testScheduler)
    return DefaultDocumentManager(
        scope.backgroundScope,
        defaultLanguageRegistry(),
        sourceLoader,
        fileStore,
        dispatcher,
        sessionConfig ?: DocumentSessionConfig(tokenizeDispatcher = dispatcher, parseDispatcher = dispatcher),
        timeSource,
    )
}

/** Opens [path], failing the test if it does not open. */
internal suspend fun DocumentManager.openOrFail(path: Path): OpenDocument =
    when (val result = open(path)) {
        is OpenResult.Opened -> result.document
        else -> error("expected $path to open, was $result")
    }

/** Types [text] at [offset] as the user would. */
internal fun OpenDocument.type(offset: Int, text: String) {
    session.text.apply(listOf(TextEdit.insert(offset, text)), EditOrigin.Typing)
}

/** The current text of the document. */
internal val OpenDocument.currentText: String get() = session.text.snapshot.value.text

/** The codes of the session's merged diagnostics. */
internal val OpenDocument.diagnosticCodes: List<String> get() = session.diagnostics.value.diagnostics.map(Diagnostic::code)
