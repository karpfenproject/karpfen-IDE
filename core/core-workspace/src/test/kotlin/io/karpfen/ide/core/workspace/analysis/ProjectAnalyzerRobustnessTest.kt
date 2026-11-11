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

import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.Mutations
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.context.ModelingContext
import io.karpfen.ide.core.workspace.context.StateMachineBinding
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.TestReporter
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Covers the robustness of the analyzer. It never throws on mutated contexts, which use fixed seeds. It lets dsl-tools
 * read only files without syntax errors, because a read of broken text would report DSL_TOOLS findings on it. It reads
 * models only against a metamodel without errors. It keeps every diagnostic inside the text of its file.
 */
class ProjectAnalyzerRobustnessTest {

    private val root = Path.of("/ws").toAbsolutePath()
    private val files = Examples.files().filter { it.directory == DescriptorExamples.CONTEXT_DIRECTORY }
    private fun pathOf(name: String): Path = root.resolve(name)

    private val context = ModelingContext(
        directory = root,
        descriptor = null,
        metamodel = pathOf("cleaning_robot.kmeta"),
        events = pathOf("EVENTS.kmeta"),
        models = listOf(pathOf("cleaning_robot.kmodel")),
        stateMachines = listOf(StateMachineBinding(pathOf("cleaning_robot.kstates"), null)),
    )

    @Test
    fun `mutated contexts analyze without exceptions or DSL_TOOLS findings on broken files`(reporter: TestReporter) =
        runTest(timeout = 300.seconds) {
            val analyzer = analyzer()
            val mark = TimeSource.Monotonic.markNow()
            var runs = 0
            for ((index, mutated) in files.withIndex()) {
                for (mutation in Mutations.random(mutated.text, seed = 1_000L + index, count = MUTATIONS_PER_FILE)) {
                    val texts = files.associate { pathOf(it.fileName) to if (it === mutated) mutation.text else it.text }
                    val sources = AnalysisSources { path -> texts[path]?.let { SourceText(TextSnapshot(it, 0), parse = null) } }
                    val analysis = analyzer.analyze(ContextResolution(listOf(context), emptyMap()), sources, generation = 1)
                    assertWellFormed(analysis, "$mutation of ${mutated.fileName}")
                    runs++
                }
            }
            reporter.publishEntry("analyzer robustness", "$runs mutated contexts analyzed in ${mark.elapsedNow()}")
        }

    /** Asserts the invariants of the class documentation on the single context of [analysis]. */
    private fun assertWellFormed(analysis: ProjectAnalysis, label: String) {
        val result = analysis.contexts.single()
        for ((path, file) in result.files) {
            val dslTools = analysis.diagnosticsFor(path).filter { it.source == DiagnosticSource.DSL_TOOLS }
            if (!file.parse.isSyntaxClean) assertEquals(emptyList(), dslTools, "$label: a broken file is never read")
            (file.ownDiagnostics + analysis.diagnosticsFor(path)).forEach { diagnostic ->
                assertTrue(diagnostic.range.end <= file.snapshot.length, "$label: ${diagnostic.range} outside ${path.fileName}")
            }
        }
        val metamodelErrors = analysis.diagnosticsFor(pathOf("cleaning_robot.kmeta"))
            .filter { it.source == DiagnosticSource.DSL_TOOLS && it.severity == Severity.ERROR }
        if (result.metamodel == null || metamodelErrors.isNotEmpty()) {
            assertEquals(emptyMap(), result.models, "$label: no model without a metamodel free of errors")
            assertEquals(null, result.events, "$label: no events without a metamodel free of errors")
        }
    }

    private companion object {
        /** Mutations of each file of the example context. */
        const val MUTATIONS_PER_FILE = 150
    }
}
