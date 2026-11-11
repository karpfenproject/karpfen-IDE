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
package io.karpfen.ide.core.workspace.fixtures

import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.core.workspace.analysis.ProjectAnalyzer
import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.core.workspace.document.OpenResult
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import java.nio.file.Path
import kotlin.time.TestTimeSource

/**
 * Workspaces on the virtual time of [scope]. Every dispatcher is a test dispatcher, and the clock is a
 * [TestTimeSource].
 */
public class TestWorkspaces(private val scope: TestScope) {
    /** The one test dispatcher every workspace of the test runs its I/O, analysis and sessions on. */
    public val dispatcher: TestDispatcher = StandardTestDispatcher(scope.testScheduler)

    /** The fake watcher of every workspace of the test. */
    public val watcher: FakeFileWatcher = FakeFileWatcher()

    /** Options on [dispatcher] and a fresh [TestTimeSource], watching only if [watch]. */
    public fun options(watch: Boolean = false): WorkspaceOptions = WorkspaceOptions(
        watch = watch,
        ioDispatcher = dispatcher,
        analysisDispatcher = dispatcher,
        sessionConfig = DocumentSessionConfig(tokenizeDispatcher = dispatcher, parseDispatcher = dispatcher),
        timeSource = TestTimeSource(),
    )

    /** Opens [root] in the test's background scope, watching through [watcher] if [watch]. */
    public fun open(root: Path, watch: Boolean = false, analyzer: ProjectAnalyzer? = null): Workspace {
        val languages = defaultLanguageRegistry()
        return Workspace(
            root, scope.backgroundScope, options(watch), languages,
            fileWatcher = watcher,
            analyzer = analyzer ?: ProjectAnalyzer(languages, DslReader()),
        )
    }
}

/** Opens [path] in the workspace, failing the test if it does not open. */
public suspend fun Workspace.openDocument(path: Path): OpenDocument =
    when (val result = documents.open(path)) {
        is OpenResult.Opened -> result.document
        else -> error("expected $path to open, was $result")
    }
