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
package io.karpfen.ide.core.workspace.analysis

import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.document.DocumentManager
import io.karpfen.ide.core.workspace.document.SourceLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * The [AnalysisSources] of a workspace. It prefers the editor text over the disk text. An open document is analyzed
 * with its session's current snapshot, its tokens from `DocumentSession.tokensFor`, and its parse when the parse has
 * that version. Any other file is loaded through the workspace's [sourceLoader] on [ioDispatcher]. It goes into a
 * fresh snapshot at version 0 with a lineage of its own, together with the loading findings.
 *
 * A file that vanished since the scan yields null, and a rescan follows. A file that cannot be read throws, as
 * [AnalysisSources.load] describes. The class is thread-safe. It only reads snapshots and state flows, so the analysis
 * may call it from its own dispatcher.
 */
internal class WorkspaceAnalysisSources(
    private val documents: DocumentManager,
    private val sourceLoader: SourceLoader,
    private val ioDispatcher: CoroutineDispatcher,
) : AnalysisSources {

    override suspend fun load(path: Path): SourceText? {
        documents.get(path)?.let { document ->
            val session = document.session
            val snapshot = session.text.snapshot.value
            return SourceText(snapshot, session.parse.value?.takeIf { it.version == snapshot.version }, session.tokensFor(snapshot))
        }
        return try {
            val loaded = withContext(ioDispatcher) { sourceLoader.load(path) }
            SourceText(TextSnapshot(loaded.text, 0), parse = null, tokens = null, fileDiagnostics = loaded.diagnostics)
        } catch (e: NoSuchFileException) {
            logger.debug("Not analyzing {}, which vanished: {}", path, e.toString())
            null
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(WorkspaceAnalysisSources::class.java)
    }
}
