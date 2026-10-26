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

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.context.ContextResolver
import io.karpfen.ide.core.workspace.document.SourceLoader
import io.karpfen.ide.core.workspace.files.WorkspaceScanner
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** The production analyzer. */
internal fun analyzer(): ProjectAnalyzer = ProjectAnalyzer(defaultLanguageRegistry(), DslReader())

/** Sources reading every file from disk like the workspace does for unopened files; counts the loads. */
internal class DiskSources : AnalysisSources {
    /** The number of [load] calls so far. */
    val loads = AtomicInteger()
    private val loader = SourceLoader()

    override suspend fun load(path: Path): SourceText? {
        loads.incrementAndGet()
        return runCatching { loader.load(path) }.getOrNull()?.let {
            SourceText(TextSnapshot(it.text, 0), parse = null, tokens = null, fileDiagnostics = it.diagnostics)
        }
    }
}

/** The contexts of the folder [root] as the workspace resolves them (no descriptors). */
internal fun resolve(root: Path): ContextResolution {
    val tree = WorkspaceScanner(WorkspaceOptions.DEFAULT_IGNORED_DIRECTORIES).scan(root)
    return ContextResolver().resolve(tree, emptyMap())
}

/** Every DSL_TOOLS diagnostic of [analysis], by file. */
internal fun dslToolsDiagnostics(analysis: ProjectAnalysis): Map<Path, List<Diagnostic>> =
    analysis.diagnostics.mapValues { (_, list) -> list.filter { it.source == DiagnosticSource.DSL_TOOLS } }.filterValues { it.isNotEmpty() }
