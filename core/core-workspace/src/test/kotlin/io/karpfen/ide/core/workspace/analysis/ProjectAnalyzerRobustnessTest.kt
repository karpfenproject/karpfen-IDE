package io.karpfen.ide.core.workspace.analysis

import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.Mutations
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.context.ModelingContext
import io.karpfen.ide.core.workspace.context.StateMachineBinding
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.TestReporter
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The analyzer never throws on mutated contexts (fixed seeds), converts only syntax-clean files (so dsl-tools writes
 * nothing to `System.err`) and keeps every diagnostic inside its file's text.
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
    fun `mutated contexts analyze without exceptions, stderr output or DSL_TOOLS findings on broken files`(reporter: TestReporter) =
        runTest(timeout = 300.seconds) {
            LoggerFactory.getLogger(javaClass) // initialize SLF4J before capturing (its "no provider" notice goes to stderr)
            val analyzer = analyzer()
            val mark = TimeSource.Monotonic.markNow()
            var runs = 0
            val stderr = capturingStandardError {
                for ((index, mutated) in files.withIndex()) {
                    for (mutation in Mutations.random(mutated.text, seed = 1_000L + index, count = MUTATIONS_PER_FILE)) {
                        val texts = files.associate { pathOf(it.fileName) to if (it === mutated) mutation.text else it.text }
                        val sources = AnalysisSources { path -> texts[path]?.let { SourceText(TextSnapshot(it, 0), parse = null) } }
                        val analysis = analyzer.analyze(ContextResolution(listOf(context), emptyMap()), sources, generation = 1)
                        assertWellFormed(analysis, "$mutation of ${mutated.fileName}")
                        runs++
                    }
                }
            }
            reporter.publishEntry("analyzer robustness", "$runs mutated contexts analyzed in ${mark.elapsedNow()}")
            assertEquals("", stderr)
        }

    private fun assertWellFormed(analysis: ProjectAnalysis, label: String) {
        val result = analysis.contexts.single()
        for ((path, file) in result.files) {
            val dslTools = analysis.diagnosticsFor(path).filter { it.source == DiagnosticSource.DSL_TOOLS }
            if (!file.parse.isSyntaxClean) assertEquals(emptyList(), dslTools, "$label: a broken file is never converted")
            (file.ownDiagnostics + analysis.diagnosticsFor(path)).forEach { diagnostic ->
                assertTrue(diagnostic.range.end <= file.snapshot.length, "$label: ${diagnostic.range} outside ${path.fileName}")
            }
        }
        if (result.metamodel == null) assertEquals(emptyMap(), result.models, "$label: no model without metamodel")
    }

    private companion object {
        const val MUTATIONS_PER_FILE = 150
    }
}
