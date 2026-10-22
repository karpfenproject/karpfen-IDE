package io.karpfen.ide.core.workspace.analysis

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.context.DescriptorSource
import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.core.workspace.document.documentManager
import io.karpfen.ide.core.workspace.document.openOrFail
import io.karpfen.ide.core.workspace.document.type
import io.karpfen.ide.core.workspace.files.FileKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers pushing project diagnostics into open sessions: diagnostics of a version of the document's text are mapped
 * forward, those of the identical disk text reach a document opened later, and those of another text or of another
 * lineage of the text (a document closed and reopened since) are never pushed. A descriptor's findings are pushed for
 * the descriptor text they were read from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectDiagnosticsPublisherTest {

    @TempDir
    lateinit var dir: Path

    private val text = "type \"Robot\" \"A robot\" {\n    has(\"arm\", \"Arm\")\n}\n"
    private val publisher = ProjectDiagnosticsPublisher()

    /** A DSL_TOOLS error at [range], by default on the unknown type `Arm`. */
    private fun projectDiagnostic(range: TextRange = TextRange.ofLength(text.indexOf("Arm\""), 3)) =
        Diagnostic(range, Severity.ERROR, "Unknown class type 'Arm'", "kmeta.unknown-target-type", DiagnosticSource.DSL_TOOLS)

    /** An analysis of generation [generation] that analyzed [path] as [snapshot] with the project [diagnostics]. */
    private fun analysisOf(path: Path, snapshot: TextSnapshot, vararg diagnostics: Diagnostic, generation: Long = 1): PublishedAnalysis {
        val parse = defaultLanguageRegistry().support(KarpfenLanguage.KMETA).parser.parse(snapshot)
        val file = AnalyzedFile(path, FileKind.KMETA, snapshot, parse)
        return PublishedAnalysis(ProjectAnalysis(generation, emptyList(), mapOf(path to diagnostics.toList()), mapOf(path to file)), emptyMap())
    }

    /** The DSL_TOOLS diagnostics of the document's session. */
    private fun OpenDocument.projectFindings(): List<Diagnostic> =
        session.diagnostics.value.diagnostics.filter { it.source == DiagnosticSource.DSL_TOOLS }

    /** Writes the metamodel to `robot.kmeta` and returns its path. */
    private fun file(): Path = dir.resolve("robot.kmeta").toAbsolutePath().normalize().also { Files.writeString(it, text) }

    @Test
    fun `diagnostics computed for a version of the document's text are pushed and mapped forward`() = runTest {
        val document = documentManager(this).openOrFail(file())
        val analyzed = document.session.text.snapshot.value
        document.type(0, "// edited after the analysis started\n")

        publisher.push(analysisOf(document.path, analyzed, projectDiagnostic()), document)
        runCurrent()

        val pushed = document.projectFindings().single()
        assertEquals("Arm", document.session.text.snapshot.value.text.substring(pushed.range.start, pushed.range.end))
    }

    @Test
    fun `an analysis of the identical disk text reaches a document opened after it`() = runTest {
        val path = file()
        val analysis = analysisOf(path, TextSnapshot(text, 0), projectDiagnostic())
        val document = documentManager(this).openOrFail(path)

        publisher.push(analysis, document)
        runCurrent()

        assertEquals(listOf(projectDiagnostic()), document.projectFindings())
    }

    @Test
    fun `an analysis of another text of another lineage is not pushed`() = runTest {
        val path = file()
        val document = documentManager(this).openOrFail(path)
        val stale = analysisOf(path, TextSnapshot("// other\n$text", 0), projectDiagnostic(TextRange(0, 2)))

        publisher.push(stale, document)
        runCurrent()

        assertEquals(emptyList(), document.projectFindings())
    }

    /**
     * The analysis starts with the edited text of a document that is closed and reopened before it publishes. Neither
     * the publisher nor a naive push of the analyzed snapshot straight into the session reaches the reopened document.
     */
    @Test
    fun `a PROJECT push of an analysis started before a close and reopen is ignored`() = runTest {
        val manager = documentManager(this)
        val path = file()
        val before = manager.openOrFail(path)
        before.type(0, "// unsaved\n")
        val analyzed = before.session.text.snapshot.value
        manager.close(path)
        val reopened = manager.openOrFail(path)

        val stale = analysisOf(path, analyzed, projectDiagnostic(TextRange.ofLength(0, 10)))
        publisher.push(stale, reopened)
        reopened.session.setExternalDiagnostics("project", analyzed, stale.analysis.diagnosticsFor(path))
        runCurrent()

        assertEquals(emptyList(), reopened.projectFindings(), "a snapshot of the closed document's lineage is ignored")
    }

    @Test
    fun `a document the analysis did not see has its project diagnostics cleared`() = runTest {
        val path = file()
        val document = documentManager(this).openOrFail(path)
        publisher.push(analysisOf(path, document.session.text.snapshot.value, projectDiagnostic()), document)
        runCurrent()
        assertTrue(document.projectFindings().isNotEmpty())

        publisher.push(PublishedAnalysis(ProjectAnalysis(2, emptyList(), emptyMap()), emptyMap()), document)
        runCurrent()

        assertEquals(emptyList(), document.projectFindings())
    }

    @Test
    fun `started, it pushes every new analysis and into newly opened documents`() = runTest {
        val manager = documentManager(this)
        val path = file()
        val analyses = MutableStateFlow(PublishedAnalysis.EMPTY)
        publisher.start(backgroundScope, analyses, manager.openDocuments)
        analyses.value = analysisOf(path, TextSnapshot(text, 0), projectDiagnostic())
        runCurrent()

        val document = manager.openOrFail(path)
        runCurrent()
        assertEquals(listOf(projectDiagnostic()), document.projectFindings(), "pushed on opening")

        analyses.value = analysisOf(path, document.session.text.snapshot.value, generation = 2)
        runCurrent()
        assertEquals(emptyList(), document.projectFindings(), "pushed for the new analysis")
    }

    @Test
    fun `descriptor findings read from the editor text are pushed for that text and mapped forward`() = runTest {
        val path = dir.resolve("robot.kenv.json").toAbsolutePath().normalize()
        Files.writeString(path, "{\"metamodel\": \"a.kmeta\", \"model\": \"b.kmodel\", \"tickDelay\": 5}")
        val document = documentManager(this).openOrFail(path)
        val read = document.session.text.snapshot.value
        val start = read.text.indexOf("tickDelay")
        val range = TextRange.ofLength(start, "tickDelay".length)
        val unknownKey = Diagnostic(range, Severity.WARNING, "Unknown key", "descriptor-unknown-key", DiagnosticSource.IDE)
        document.type(0, " ")
        val analysis = ProjectAnalysis(1, emptyList(), mapOf(path to listOf(unknownKey)))

        publisher.push(PublishedAnalysis(analysis, mapOf(path to DescriptorSource(read, emptyList()))), document)
        runCurrent()

        val pushed = document.session.diagnostics.value.diagnostics.single()
        assertEquals("tickDelay", document.session.text.snapshot.value.text.substring(pushed.range.start, pushed.range.end))
    }
}
