package io.karpfen.ide.core.workspace.analysis

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.parse.SemanticMode
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.context.ContextResolver
import io.karpfen.ide.core.workspace.context.ModelingContext
import io.karpfen.ide.core.workspace.context.StateMachineBinding
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import io.karpfen.ide.core.workspace.environment.EnvironmentDescriptorReader
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceScanner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.io.TempDir
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Covers the project analysis on real example folders: every example context reads without findings, broken files are
 * never read and block the reads that depend on them, semantic errors land on the offending element of the right file
 * next to the partial model, a metamodel with errors blocks the reads that depend on it, editor text replaces disk
 * text, loose files stay syntax-only, and unreadable, failing or vanished files are reported or skipped. The analysis
 * is cancellable between files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultProjectAnalyzerTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()

    /** Runs one analysis of [resolution] whose time is not reported, so class loading does not count. */
    private suspend fun warmUp(resolution: ContextResolution) {
        analyzer().analyze(resolution, DiskSources(), generation = 1)
    }

    /** [text] without its last closing brace, a syntax error at the end of the file. */
    private fun withoutClosingBrace(text: String): String = text.trimEnd().removeSuffix("}")

    @Test
    fun `every example context analyzes with zero DSL_TOOLS findings and reads every file`(reporter: TestReporter) = runTest(timeout = 120.seconds) {
        Examples.copyTo(root)
        val resolution = resolve(root)
        warmUp(resolution)

        val mark = TimeSource.Monotonic.markNow()
        val analysis = analyzer().analyze(resolution, DiskSources(), generation = 2)
        val elapsed = mark.elapsedNow()

        assertEquals(Examples.contexts().size, analysis.contexts.size)
        assertEquals(emptyMap(), dslToolsDiagnostics(analysis))
        assertEquals(emptyMap(), analysis.diagnostics, "no project diagnostics at all")
        assertEquals(emptyList(), analysis.looseFiles.keys.toList())
        for (context in analysis.contexts) {
            val name = context.context.directory.fileName
            assertNotNull(context.metamodel, "metamodel of $name")
            assertEquals(context.context.events != null, context.events != null, "events of $name")
            assertEquals(context.context.models.toSet(), context.models.keys, "models of $name")
            assertEquals(context.context.stateMachines.map { it.file }.toSet(), context.stateMachines.keys, "state machines of $name")
            context.files.values.forEach { file -> assertEquals(emptyList(), file.ownDiagnostics, "${file.path}") }
        }
        assertEquals(2, analysis.generation)
        reporter.publishEntry(
            "analysis of every example context",
            "${analysis.files.size} files in ${analysis.contexts.size} contexts analyzed in $elapsed (after one warm-up run)",
        )
    }

    @Test
    fun `the explicit example context analyzes cleanly with events converted against the domain types`() = runTest(timeout = 60.seconds) {
        val descriptor = DescriptorExamples.copyExplicitContext(root.resolve("ctx")).toAbsolutePath().normalize()
        val tree = WorkspaceScanner(emptySet()).scan(root)
        val read = EnvironmentDescriptorReader().read(descriptor, Files.readString(descriptor))
        val resolution = ContextResolver().resolve(tree, mapOf(descriptor to read))

        val analysis = analyzer().analyze(resolution, DiskSources(), generation = 1)

        val context = analysis.contexts.single()
        assertEquals(descriptor, context.context.descriptor)
        val events = assertNotNull(context.events)
        assertTrue(events.types.any { it.name == "teleport" } && events.types.any { it.name == "Vector" }, "domain types are base types")
        assertEquals(1, context.models.size)
        assertEquals(1, context.stateMachines.size)
        assertEquals(emptyMap(), analysis.diagnostics)
    }

    @Test
    fun `a broken metamodel and a broken model report PARSER errors only and block dependent reads`() = runTest(timeout = 60.seconds) {
        val context = root.resolve("robot")
        Files.createDirectories(context)
        Examples.files().filter { it.directory == DescriptorExamples.CONTEXT_DIRECTORY }.forEach { file ->
            val text = when (file.fileName) {
                "cleaning_robot.kmeta" -> withoutClosingBrace(file.text)
                "cleaning_robot.kmodel" -> file.text.replaceFirst("has(\"robot\") ->", "has(\"robot\") -> ->")
                else -> file.text
            }
            Files.writeString(context.resolve(file.fileName), text)
        }
        val analysis = analyzer().analyze(resolve(root), DiskSources(), generation = 1)

        assertEquals(emptyMap(), dslToolsDiagnostics(analysis), "dsl-tools must not read broken text: it would report its syntax errors")
        val result = analysis.contexts.single()
        assertNull(result.metamodel)
        assertNull(result.events, "the events need the domain metamodel")
        assertEquals(emptyMap(), result.models)
        assertEquals(1, result.stateMachines.size, "state machines are read on their own")
        for (name in listOf("cleaning_robot.kmeta", "cleaning_robot.kmodel")) {
            val own = assertNotNull(analysis.file(context.resolve(name))).ownDiagnostics
            assertTrue(own.isNotEmpty() && own.all { it.source == DiagnosticSource.PARSER }, "$name: $own")
        }
    }

    @Test
    fun `a semantic error is a DSL_TOOLS diagnostic at the offending key of the right file, next to the partial model`() = runTest(timeout = 60.seconds) {
        val context = root.resolve("robot")
        Files.createDirectories(context)
        Files.writeString(context.resolve("robot.kmeta"), "type \"Robot\" \"A robot\" {\n    prop(\"speed\", \"number\")\n}\n")
        val model = "make object \"r\":\"Robot\" {\n    prop(\"speed\") -> \"1\"\n    prop(\"velocity\") -> \"2\"\n}\n"
        val modelPath = context.resolve("robot.kmodel").toAbsolutePath().normalize()
        Files.writeString(modelPath, model)

        val analysis = analyzer().analyze(resolve(root), DiskSources(), generation = 1)

        val error = analysis.diagnosticsFor(modelPath).single()
        assertEquals(DiagnosticSource.DSL_TOOLS, error.source)
        assertEquals("kmodel.unknown-property", error.code)
        assertEquals(Severity.ERROR, error.severity)
        assertEquals(TextRange.ofLength(model.indexOf("velocity"), "velocity".length), error.range)
        val partial = assertNotNull(analysis.contexts.single().models[modelPath])
        assertEquals(listOf("speed"), partial.objects.single().properties.map { it.key }, "the unknown property is left out")
        assertNotNull(analysis.contexts.single().metamodel)
    }

    @Test
    fun `a metamodel with a DSL_TOOLS error keeps its partial metamodel and blocks the reads that depend on it`() = runTest(timeout = 60.seconds) {
        val context = root.resolve("robot")
        Files.createDirectories(context)
        val metamodel = "type \"Robot\" \"A robot\" {\n    prop(\"speed\", \"nmber\")\n    prop(\"name\", \"string\")\n}\n"
        val metamodelPath = context.resolve("robot.kmeta").toAbsolutePath().normalize()
        Files.writeString(metamodelPath, metamodel)
        Files.writeString(context.resolve("robot.kmodel"), "make object \"r\":\"Robot\" {\n    prop(\"speed\") -> \"1\"\n}\n")
        Files.writeString(context.resolve("EVENTS.kmeta"), "type \"start\" \"s\" {\n}\n")

        val analysis = analyzer().analyze(resolve(root), DiskSources(), generation = 1)

        val error = analysis.diagnosticsFor(metamodelPath).single()
        assertEquals("kmeta.unknown-property-type", error.code)
        assertEquals("nmber", metamodel.substring(error.range.start, error.range.end))
        assertEquals(setOf(metamodelPath), dslToolsDiagnostics(analysis).keys, "no follow-up error in the model")
        val result = analysis.contexts.single()
        assertEquals(listOf("name"), assertNotNull(result.metamodel).rootClass.simpleProperties.map { it.key })
        assertNull(result.events)
        assertEquals(emptyMap(), result.models)
    }

    @Test
    fun `editor text supplied by the sources is analyzed instead of disk text, with its snapshot, tokens and parse`() = runTest {
        val path = root.resolve("ctx/robot.kmeta")
        val text = EditableText("type \"Robot\" \"edited\" {\n}\n")
        val snapshot = text.snapshot.value
        val support = defaultLanguageRegistry().support(KarpfenLanguage.KMETA)
        val parse = support.parser.parse(snapshot, SemanticMode.SYNTAX_ONLY)
        val tokens = support.tokenizer.tokenize(snapshot)
        val context = ModelingContext(path.parent, null, path, null, emptyList(), emptyList())

        val analysis = analyzer().analyze(
            ContextResolution(listOf(context), emptyMap()),
            AnalysisSources { if (it == path) SourceText(snapshot, parse, tokens) else null },
            generation = 7,
        )

        val file = assertNotNull(analysis.file(path))
        assertSame(snapshot, file.snapshot)
        assertSame(parse, file.parse)
        assertEquals(FileKind.KMETA, file.kind)
        assertEquals("Robot", analysis.contexts.single().metamodel?.types?.single()?.name)
        assertEquals(7, analysis.generation)
    }

    @Test
    fun `loose files are analyzed syntax-only and never read by dsl-tools`() = runTest {
        val loose = root.resolve("loose.kmeta")
        Files.writeString(loose, "type \"Robot\" \"A robot\" {\n    has(\"arm\", \"MissingType\")\n}\n")
        val brokenLoose = root.resolve("broken.kstates")
        Files.writeString(brokenLoose, "STATEMACHINE ATTACHED TO \"Robot\" {\n")

        val analysis = analyzer().analyze(ContextResolution(emptyList(), emptyMap(), listOf(loose, brokenLoose)), DiskSources(), generation = 1)

        assertEquals(listOf(loose, brokenLoose), analysis.looseFiles.keys.toList())
        assertEquals(emptyMap(), analysis.diagnostics, "a loose metamodel with a semantic error is not read")
        assertTrue(analysis.looseFiles.getValue(brokenLoose).ownDiagnostics.any { it.source == DiagnosticSource.PARSER })
    }

    @Test
    fun `a file named by two contexts is loaded once per run`() = runTest {
        val metamodel = root.resolve("a.kmeta")
        Files.writeString(metamodel, "type \"A\" \"a\" {\n}\n")
        val contexts = List(2) { ModelingContext(root, root.resolve("$it.kenv.json"), metamodel, null, emptyList(), emptyList()) }
        val sources = DiskSources()

        val analysis = analyzer().analyze(ContextResolution(contexts, emptyMap()), sources, generation = 1)

        assertEquals(1, sources.loads.get())
        assertSame(analysis.contexts[0].files[metamodel], analysis.contexts[1].files[metamodel])
    }

    @Test
    fun `missing files are absent, resolution diagnostics are merged, a failing source is an internal error`() = runTest {
        val machine = root.resolve("m.kstates")
        val failing = root.resolve("failing.kmodel")
        Files.writeString(machine, Examples.file("${DescriptorExamples.CONTEXT_DIRECTORY}/cleaning_robot.kstates").text)
        val info = Diagnostic(TextRange.EMPTY, Severity.INFO, "context finding", WorkspaceDiagnosticCodes.CONTEXT_NO_METAMODEL, DiagnosticSource.IDE)
        val context = ModelingContext(root, null, root.resolve("missing.kmeta"), null, listOf(failing), listOf(StateMachineBinding(machine, null)))
        val sources = AnalysisSources { path ->
            if (path == failing) throw IllegalStateException("source exploded") else DiskSources().load(path)
        }

        val analysis = analyzer().analyze(ContextResolution(listOf(context), mapOf(machine to listOf(info, info))), sources, generation = 3)

        val result = analysis.contexts.single()
        assertEquals(setOf(machine), result.files.keys)
        assertEquals(1, result.stateMachines.size)
        assertEquals(listOf(info), analysis.diagnosticsFor(machine), "sorted and without duplicates")
        assertEquals(listOf(DiagnosticCodes.INTERNAL_ERROR), analysis.diagnosticsFor(failing).map { it.code })
    }

    @Test
    fun `a file that cannot be read is an unreadable error on it, one that vanished is skipped`() = runTest {
        val machine = root.resolve("m.kstates")
        val locked = root.resolve("locked.kmeta")
        val vanished = root.resolve("vanished.kmodel")
        Files.writeString(machine, Examples.file("${DescriptorExamples.CONTEXT_DIRECTORY}/cleaning_robot.kstates").text)
        val context = ModelingContext(root, null, locked, null, listOf(vanished), listOf(StateMachineBinding(machine, null)))
        val sources = AnalysisSources { path ->
            when (path) {
                locked -> throw AccessDeniedException(path.toString())
                vanished -> null
                else -> DiskSources().load(path)
            }
        }

        val analysis = analyzer().analyze(ContextResolution(listOf(context), emptyMap()), sources, generation = 1)

        assertEquals(setOf(machine), analysis.contexts.single().files.keys)
        val finding = analysis.diagnosticsFor(locked).single()
        assertEquals(WorkspaceDiagnosticCodes.UNREADABLE to Severity.ERROR, finding.code to finding.severity)
        assertEquals("Cannot read this file (access denied), so it was not analyzed.", finding.message)
        assertEquals(emptyList(), analysis.diagnosticsFor(vanished))
    }

    @Test
    fun `a file whose language does not fit its role is not read`() = runTest {
        val machine = root.resolve("robot.kstates")
        Files.writeString(machine, Examples.file("${DescriptorExamples.CONTEXT_DIRECTORY}/cleaning_robot.kstates").text)
        val context = ModelingContext(root, root.resolve("x.kenv.json"), machine, null, listOf(machine), emptyList())

        val analysis = analyzer().analyze(ContextResolution(listOf(context), emptyMap()), DiskSources(), 1)

        assertNull(analysis.contexts.single().metamodel)
        assertEquals(emptyMap(), analysis.diagnostics)
    }

    @Test
    fun `the analysis is cancellable between files`() = runTest {
        Examples.copyTo(root)
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        val sources = AnalysisSources { path ->
            loads++
            gate.await()
            DiskSources().load(path)
        }

        val running = async { analyzer().analyze(resolve(root), sources, generation = 1) }
        runCurrent()
        running.cancel()
        gate.complete(Unit)
        runCurrent()

        assertTrue(running.isCancelled)
        assertEquals(1, loads, "no file after the cancellation")
    }
}
